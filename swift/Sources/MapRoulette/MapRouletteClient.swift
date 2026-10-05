import Foundation

/// Explicit read operations only; no locks, implicit pagination, retries or persistence.
public final class MapRouletteClient: Sendable {
  private let base: URL
  private let transport: any Transport
  private let apiKey: @Sendable () async throws -> String?
  private let owner = UUID()
  public init(
    serviceURL: URL = URL(string: "https://maproulette.org/api/v2/")!,
    transport: any Transport = URLSessionTransport(),
    apiKey: @escaping @Sendable () async throws -> String? = { nil }
  ) throws {
    guard let c = URLComponents(url: serviceURL, resolvingAgainstBaseURL: false),
      c.scheme == "https"
        || (c.scheme == "http"
          && ["localhost", "127.0.0.1", "[::1]", "::1"].contains(c.host ?? "")),
      c.host?.isEmpty == false, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil
    else { throw MapRouletteError(.validation) }
    base = serviceURL
    self.transport = transport
    self.apiKey = apiKey
  }
  public func searchChallenges(
    filter: ChallengeFilter = ChallengeFilter(), pageSize: Int = 50, after: Continuation? = nil
  ) async throws -> Page<Challenge> {
    guard
      filter.tags.allSatisfy({
        !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !$0.contains(",")
      })
    else { throw MapRouletteError(.validation) }
    var params = [
      "ct": filter.tags.joined(separator: ","), "cLocal": String(filter.localSurvey.rawValue),
      "ca": String(filter.includeArchived), "ce": String(filter.onlyEnabled),
      "pe": String(filter.onlyEnabled), "sort": "id", "order": "ASC",
    ]
    params["cs"] = filter.text
    return try await page(
      path: "challenges/extendedFind", params: params, size: pageSize, after: after, offset: true,
      parse: challenge)
  }
  public func getChallenge(_ id: ChallengeID) async throws -> Challenge {
    let result = try challenge(await request("challenge/\(id.value)"))
    guard result.id == id else { throw MapRouletteError(.protocolFailure) }
    return result
  }
  public func getChallengeTags(_ id: ChallengeID) async throws -> [ChallengeTag] {
    try await request("challenge/\(id.value)/tags").array().map { value in
      let o = try value.object()
      return try ChallengeTag(id: o.required("id").integer(), name: o.required("name").string())
    }
  }
  public func listTasks(_ id: ChallengeID, pageSize: Int = 50, after: Continuation? = nil)
    async throws -> Page<MapRouletteTask>
  {
    let result = try await page(
      path: "challenge/\(id.value)/tasks", params: [:], size: pageSize, after: after, offset: false,
      parse: task)
    guard result.items.allSatisfy({ $0.challengeID == id }) else {
      throw MapRouletteError(.protocolFailure)
    }
    return result
  }
  public func getTask(_ id: TaskID) async throws -> MapRouletteTask {
    let result = try task(await request("task/\(id.value)"))
    guard result.id == id else { throw MapRouletteError(.protocolFailure) }
    return result
  }
  public func getCurrentUser() async throws -> UserIdentity {
    let o = try await request("user/whoami").object()
    return try UserIdentity(id: o.required("id").integer(), guest: o.required("guest").boolean())
  }
  public func findTasksInBounds(filter: TaskFilter, pageSize: Int = 50, after: Continuation? = nil)
    async throws -> Page<TaskSummary>
  {
    guard filter.statuses.map({ !$0.isEmpty && $0.allSatisfy { $0 >= 0 && $0 <= Int32.max } }) ?? true
    else { throw MapRouletteError(.validation) }
    let b = filter.bounds
    var params = [
      "cLocal": "1",
      "ca": String(filter.includeArchived),
      "tStatus": filter.statuses?.map(String.init).joined(separator: ",") ?? "-1",
      "includeTotal": "true", "sort": "id", "order": "ASC",
    ]
    if !filter.challengeIDs.isEmpty {
      params["cid"] = filter.challengeIDs.map { String($0.value) }.joined(separator: ",")
    }
    let result = try await page(
      path: "tasks/box/\(b.west)/\(b.south)/\(b.east)/\(b.north)", params: params, size: pageSize,
      after: after, offset: false, envelope: true, parse: summary)
    guard filter.challengeIDs.isEmpty || result.items.allSatisfy({ filter.challengeIDs.contains($0.challengeID) }) else {
      throw MapRouletteError(.protocolFailure)
    }
    return result
  }
  /// Bounded, unordered map markers. A full result may be truncated; no pagination or total.
  /// This read-only backend operation uses PUT and includes tasks locked by other users.
  /// All-challenge discovery filters to enabled challenges/projects; selected IDs bypass that filter.
  public func findTaskMarkers(filter: TaskFilter, limit: Int = 100) async throws -> [TaskSummary] {
    guard (1...1000).contains(limit),
      filter.statuses.map({ !$0.isEmpty && $0.allSatisfy { $0 >= 0 && $0 <= Int32.max } }) ?? true
    else { throw MapRouletteError(.validation) }
    var params = ["cLocal": "1", "ca": String(filter.includeArchived),
      "tStatus": filter.statuses?.map(String.init).joined(separator: ",") ?? "-1",
      "excludeLocked": "true", "limit": String(limit)]
    if !filter.challengeIDs.isEmpty {
      params["cid"] = filter.challengeIDs.map { String($0.value) }.joined(separator: ",")
    }
    let b = filter.bounds
    if filter.challengeIDs.isEmpty {
      params["ce"] = "true"
      params["pe"] = "true"
    }
    let response = try await request("markers/box/\(b.west)/\(b.south)/\(b.east)/\(b.north)",
      params: params, method: .put, body: Data("{}".utf8))
    let rows = try response.array()
    guard rows.count <= limit else { throw MapRouletteError(.protocolFailure) }
    let markers = try rows.map(summary)
    guard filter.challengeIDs.isEmpty || markers.allSatisfy({ filter.challengeIDs.contains($0.challengeID) })
    else { throw MapRouletteError(.protocolFailure) }
    return markers
  }

  private func page<T: Sendable>(
    path: String, params: [String: String], size: Int, after: Continuation?, offset: Bool,
    envelope: Bool = false, parse: (JSONValue) throws -> T
  ) async throws -> Page<T> {
    guard (1...100).contains(size) else { throw MapRouletteError(.validation) }
    let key =
      path + "?"
      + params.sorted { $0.key < $1.key }.map {
        "\($0.key.count):\($0.key)\($0.value.count):\($0.value)"
      }.joined() + "&size=\(size)"
    guard after == nil || (after?.owner == owner && after?.key == key) else {
      throw MapRouletteError(.validation)
    }
    let position = after?.position ?? 0
    var query = params
    query["limit"] = String(size)
    query["page"] = String(position)
    let body = try await request(path, params: query)
    let rows: [JSONValue]
    let total: Int64?
    if envelope {
      let o = try body.object()
      rows = try o.required("tasks").array()
      total = try o.optional("total")?.integer()
    } else {
      rows = try body.array()
      total = nil
    }
    guard rows.count <= size, total.map({ $0 >= 0 }) ?? true else {
      throw MapRouletteError(.protocolFailure)
    }
    let nextPosition = position.addingReportingOverflow(offset ? rows.count : 1)
    guard !nextPosition.overflow else { throw MapRouletteError(.protocolFailure) }
    let next =
      rows.count == size
      ? Continuation(owner: owner, key: key, position: nextPosition.partialValue) : nil
    return try Page(items: rows.map(parse), next: next, total: total)
  }
  private func request(_ path: String, params: [String: String] = [:], method: HTTPMethod = .get,
    body: Data? = nil) async throws -> JSONValue {
    try Task.checkCancellation()
    guard
      var url = URLComponents(
        url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)
    else { throw MapRouletteError(.validation) }
    let items = params.filter { !$0.value.isEmpty }.sorted { $0.key < $1.key }.map {
      URLQueryItem(name: $0.key, value: $0.value)
    }
    if !items.isEmpty { url.queryItems = items }
    guard let target = url.url else { throw MapRouletteError(.validation) }
    var headers = ["Accept": "application/json", "User-Agent": "MapRoulette-Mobile-SDK/0.1"]
    if body != nil { headers["Content-Type"] = "application/json" }
    if let key = try await apiKey() {
      guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !key.contains("\r"),
        !key.contains("\n")
      else { throw MapRouletteError(.validation) }
      headers["apiKey"] = key
    }
    let response: HTTPResponse
    do {
      response = try await transport.execute(HTTPRequest(url: target, headers: headers, method: method, body: body))
      try Task.checkCancellation()
    } catch is CancellationError { throw CancellationError() } catch let error as URLError
      where error.code == .cancelled
    { throw CancellationError() } catch let error as MapRouletteError { throw error } catch {
      throw MapRouletteError(.network)
    }
    guard response.status == 200 else {
      let kind: ErrorKind
      switch response.status {
      case 401: kind = .authentication
      case 403: kind = .permission
      case 404: kind = .notFound
      case 409: kind = .conflict
      case 429: kind = .rateLimit
      case 500...599: kind = .server
      default: kind = .http
      }
      throw MapRouletteError(
        kind, status: response.status,
        retryAfter: response.headers.first { $0.key.lowercased() == "retry-after" }?.value)
    }
    do { return try JSONDecoder().decode(JSONValue.self, from: response.body) } catch {
      throw MapRouletteError(.protocolFailure)
    }
  }
}

extension JSONValue {
  fileprivate func object() throws -> [String: JSONValue] {
    guard case .object(let v) = self else { throw MapRouletteError(.protocolFailure) }
    return v
  }
  fileprivate func array() throws -> [JSONValue] {
    guard case .array(let v) = self else { throw MapRouletteError(.protocolFailure) }
    return v
  }
  fileprivate func string() throws -> String {
    guard case .string(let v) = self else { throw MapRouletteError(.protocolFailure) }
    return v
  }
  fileprivate func integer() throws -> Int64 {
    guard case .integer(let v) = self else { throw MapRouletteError(.protocolFailure) }
    return v
  }
  fileprivate func boolean() throws -> Bool {
    guard case .bool(let v) = self else { throw MapRouletteError(.protocolFailure) }
    return v
  }
}
extension Dictionary where Key == String, Value == JSONValue {
  fileprivate func required(_ key: String) throws -> JSONValue {
    guard let value = self[key] else { throw MapRouletteError(.protocolFailure) }
    return value
  }
  fileprivate func optional(_ key: String) -> JSONValue? {
    guard let value = self[key], value != .null else { return nil }
    return value
  }
  fileprivate func optionalObject(_ key: String) throws -> JSONValue? {
    if let value = optional(key) {
      _ = try value.object()
      return value
    }
    return nil
  }
  fileprivate func status() throws -> TaskStatus? {
    guard let value = optional("status") else { return nil }
    let code = try value.integer()
    guard code >= Int32.min, code <= Int32.max else { throw MapRouletteError(.protocolFailure) }
    return TaskStatus(code: Int(code))
  }
}
private func challenge(_ value: JSONValue) throws -> Challenge {
  let o = try value.object()
  let parent = try o.required("parent")
  let project: Int64
  if case .object(let p) = parent {
    project = try p.required("id").integer()
  } else {
    project = try parent.integer()
  }
  let id = try o.required("id").integer()
  guard id > 0, project > 0 else { throw MapRouletteError(.protocolFailure) }
  return try Challenge(
    id: ChallengeID(id), projectID: ProjectID(project), name: o.required("name").string(),
    instruction: o.optional("instruction")?.string(),
    description: o.optional("description")?.string(),
    tags: o.optional("tags")?.array().map { try $0.string() },
    enabled: o.optional("enabled")?.boolean(), archived: o.optional("isArchived")?.boolean(),
    requiresLocal: o.optional("requiresLocal")?.boolean())
}
private func task(_ value: JSONValue) throws -> MapRouletteTask {
  let o = try value.object()
  let geometry = try o.required("geometries")
  let g = try geometry.object()
  guard try g.required("type").string() == "FeatureCollection" else {
    throw MapRouletteError(.protocolFailure)
  }
  _ = try g.required("features").array()
  let id = try o.required("id").integer()
  let parent = try o.required("parent").integer()
  guard id > 0, parent > 0 else { throw MapRouletteError(.protocolFailure) }
  return try MapRouletteTask(
    id: TaskID(id), challengeID: ChallengeID(parent), name: o.required("name").string(),
    instruction: o.optional("instruction")?.string(), status: o.status(), geometry: geometry,
    location: o.optionalObject("location"), cooperativeWork: o.optionalObject("cooperativeWork"))
}
private func summary(_ value: JSONValue) throws -> TaskSummary {
  let o = try value.object()
  let id = try o.required("id").integer()
  let parent = try o.required("parentId").integer()
  guard id > 0, parent > 0 else { throw MapRouletteError(.protocolFailure) }
  return try TaskSummary(
    id: TaskID(id), challengeID: ChallengeID(parent), title: o.required("title").string(),
    status: o.status(), point: o.optionalObject("point"))
}
