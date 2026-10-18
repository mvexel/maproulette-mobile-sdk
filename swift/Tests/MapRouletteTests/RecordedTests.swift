import Foundation
import Testing
@testable import MapRoulette

// Replays every fixtures/recorded/ file (real dev responses, see scripts/record-fixtures.py) through
// the public client: each request must match the recorded method, path and query, successful
// exchanges must decode, and recorded errors must map to an error kind other than protocolFailure.

private let recordedDir = URL(fileURLWithPath: #filePath)
  .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
  .deletingLastPathComponent().appendingPathComponent("fixtures/recorded")

private struct Exchange: Sendable {
  let method: String, path: String, query: [String: String], status: Int, body: Data

  init(_ e: [String: Any]) throws {
    method = e["method"] as! String
    path = e["path"] as! String
    query = (e["query"] as? [String: String]) ?? [:]
    status = e["status"] as! Int
    switch e["body"] {
    case nil, is NSNull: body = Data()
    case let text as String: body = Data(text.utf8)
    case let value?: body = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
    }
  }
}

private actor Replay: Transport {
  let file: String
  var queue: [Exchange]
  init(_ file: String, _ exchanges: [Exchange]) {
    self.file = file
    queue = exchanges
  }
  func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
    guard !queue.isEmpty else {
      Issue.record("\(file): unexpected \(request.method.rawValue) \(request.url)")
      throw URLError(.badURL)
    }
    let e = queue.removeFirst()
    #expect(request.method.rawValue == e.method, "\(file)")
    #expect(request.url.path == e.path, "\(file)")
    let items = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    let sent = Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
    #expect(sent == e.query, "\(file): \(request.url)")
    return HTTPResponse(status: e.status, body: e.body)
  }
  func remaining() -> Int { queue.count }
}

private func int(_ args: [String: Any], _ name: String) -> Int { args[name] as! Int }
private func int64(_ args: [String: Any], _ name: String) -> Int64 { Int64(args[name] as! Int) }

private func filter(_ args: [String: Any]) throws -> TaskFilter {
  let b = (args["bounds"] as! [Any]).map { ($0 as! NSNumber).doubleValue }
  return TaskFilter(
    challengeIDs: try (args["challengeIds"] as! [Int]).map { try ChallengeID(Int64($0)) },
    bounds: try Bounds(west: b[0], south: b[1], east: b[2], north: b[3]),
    statuses: args["statuses"] as? [Int],
    choiceOnly: (args["choiceOnly"] as? Bool) ?? false)
}

/// Runs the recorded call; returns true when the SDK was expected to stop before the last exchange.
private func call(_ name: String, _ args: [String: Any], _ wire: Replay) async throws -> Bool {
  let client = MapRouletteClient(environment: .staging, transport: wire, accessToken: { "recorded-token" })
  switch name {
  case "searchChallenges":
    _ = try await client.searchChallenges(pageSize: int(args, "pageSize"))
  case "getChallenge":
    let id = try ChallengeID(int64(args, "id"))
    #expect(try await client.getChallenge(id).id == id)
  case "getChallengeTags":
    _ = try await client.getChallengeTags(try ChallengeID(int64(args, "id")))
  case "getTask":
    let id = try TaskID(int64(args, "id"))
    #expect(try await client.getTask(id).id == id)
  case "listTasks":
    var after: PageCursor?
    for _ in 0..<int(args, "pages") {
      let page = try await client.listTasks(
        try ChallengeID(int64(args, "id")), pageSize: int(args, "pageSize"), after: after)
      guard let next = page.next else { return true }
      after = next
    }
  case "listAllTasks":
    var ids: [TaskID] = []
    for try await page in client.listAllTasks(
      try ChallengeID(int64(args, "id")), concurrency: int(args, "concurrency"))
    {
      ids += page.items.map(\.id)
    }
    #expect(Set(ids).count == ids.count)
  case "findTasksInBounds":
    var after: PageCursor?
    for _ in 0..<int(args, "pages") {
      let page = try await client.findTasksInBounds(
        filter: try filter(args), pageSize: int(args, "pageSize"), after: after)
      #expect(page.total != nil)
      guard let next = page.next else { return true }
      after = next
    }
  case "findTaskMarkers":
    _ = try await client.findTaskMarkers(filter: try filter(args), limit: int(args, "limit"))
  case "startGuest":
    let configuration = try MobileSignInConfiguration(
      environment: .staging, clientID: args["clientId"] as! String,
      redirectURI: URL(string: "app.test:/oauth2redirect")!)
    let signIn = MobileSignIn(configuration: configuration, store: InMemoryCredentialStore(), transport: wire)
    let guest = try await signIn.startGuest()
    #expect(try await signIn.accessToken(generation: guest.generation) != nil)
  case "getGuestStatus":
    _ = try await client.getGuestStatus()
  case "submitPendingChoice":
    let task = try await client.getTask(try TaskID(int64(args, "task")))
    let answers = args["answers"] as! [String: String]
    #expect(try await client.submitPendingChoice(task, .answers(answers)).taskID == task.id)
  case "listPendingChoices":
    _ = try await client.listPendingChoices(pageSize: int(args, "pageSize"))
  case "withdrawPendingChoice":
    try await client.withdrawPendingChoice(try TaskID(int64(args, "task")))
  case "deleteGuest":
    try await client.deleteGuest()
  case "getCurrentUser":
    _ = try await client.getCurrentUser()
  case "checkChoice":
    _ = try await client.checkChoice(try TaskID(int64(args, "task")))
  default:
    Issue.record("no replay for recorded call \(name)")
  }
  return false
}

@Test func everyRecordedResponseReplays() async throws {
  let files = try FileManager.default.contentsOfDirectory(at: recordedDir, includingPropertiesForKeys: nil)
    .filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
  #expect(!files.isEmpty)
  for url in files {
    let file = url.lastPathComponent
    let doc = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    // Shape-only recordings (routes the SDK has no client for) are compared by the drift job alone.
    if (doc["decode"] as? Bool) == false { continue }
    let exchanges = try (doc["exchanges"] as! [[String: Any]]).map(Exchange.init)
    let wire = Replay(file, exchanges)
    let succeeds = exchanges.allSatisfy { (200..<300).contains($0.status) }
    do {
      let stoppedEarly = try await call(doc["call"] as! String, doc["args"] as! [String: Any], wire)
      #expect(succeeds, "\(file): expected an error for status \(exchanges.last!.status)")
      if !stoppedEarly { #expect(await wire.remaining() == 0, "\(file): exchanges not requested") }
    } catch let error as MapRouletteError {
      #expect(!succeeds, "\(file): \(error)")
      #expect(error.kind != .protocolFailure, "\(file): an error response didn't decode")
      #expect(await wire.remaining() == 0, "\(file): failed before its last exchange")
    }
  }
}
