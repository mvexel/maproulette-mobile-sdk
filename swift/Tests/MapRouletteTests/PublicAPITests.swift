import Foundation
import Testing
import MapRoulette  // Deliberately not @testable: this file only compiles against the public API.

private let repositoryRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent()

private actor Recorder: Transport {
    var requests: [HTTPRequest] = []
    let response: HTTPResponse
    init(_ response: HTTPResponse) { self.response = response }
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse { requests.append(request); return response }
}

/// Apps build fakes from these value types (as Kotlin apps do with the data classes).
@Test func choiceValueTypesHavePublicInitializers() {
    let eligibility = ChoiceEligibility(eligible: false, deleteAllowed: false, reason: .keyChanged)
    #expect(eligibility.reason == .keyChanged && !eligibility.eligible)
    let result = ChoiceResult(status: TaskStatus(code: 1), changesetID: 99)
    #expect(result.status.code == 1 && result.changesetID == 99)
}

@Test func modelsCanBeBuiltComparedAndIdentified() throws {
    let taskID = try TaskID(7), next = try TaskID(8)
    let task = MapRouletteTask(id: taskID, challengeID: try ChallengeID(3), name: "node/1")
    let same = MapRouletteTask(id: taskID, challengeID: try ChallengeID(3), name: "node/1")
    #expect(task == same && task.id == taskID && Set([task, same]).count == 1)
    let challenge = Challenge(id: try ChallengeID(3), projectID: try ProjectID(1), name: "Benches")
    let summary = TaskSummary(id: taskID, challengeID: challenge.id, title: "node/1")
    let identity = UserIdentity(id: 900, guest: false, scopes: ["tasks:read"])
    let lock = TaskLock(task: task, primaryTaskID: task.id)
    let page = Page(items: [summary], total: 1)
    #expect(page.next == nil && page == Page(items: [summary], total: 1))
    #expect(challenge.id.description == "3" && summary.id < next)
    #expect(!identity.canWriteTasks && lock.bundledTaskIDs.isEmpty)
    let question = ChoiceQuestion(
        id: "backrest", prompt: "Backrest?", expect: ["backrest": nil],
        options: [ChoiceOption(id: "yes", label: "Yes", setTags: ["backrest": "yes"])])
    #expect(question.options.map(\.id) == ["yes"])
    #expect(MapRouletteError(.validation, reason: "x") == MapRouletteError(.validation, reason: "x"))
}

@Test func identifiersAreCodableAsBareNumbers() throws {
    let ids = [try TaskID(5), try TaskID(9)]
    let data = try JSONEncoder().encode(ids)
    #expect(String(decoding: data, as: UTF8.self) == "[5,9]")
    let decoded = try JSONDecoder().decode([TaskID].self, from: data)
    #expect(decoded == ids)
    #expect(throws: DecodingError.self) { try JSONDecoder().decode([ChallengeID].self, from: Data("[0]".utf8)) }
}

@Test func versionMatchesTheRepositoryVersionFileAndUserAgent() async throws {
    let file = try String(contentsOf: repositoryRoot.appendingPathComponent("VERSION"), encoding: .utf8)
    #expect(MapRouletteSDK.version == file.trimmingCharacters(in: .whitespacesAndNewlines))
    let wire = Recorder(HTTPResponse(status: 200, body: Data(#"{"id":1,"guest":true}"#.utf8)))
    _ = try await MapRouletteClient(transport: wire, apiKey: { "k" }).getCurrentUser()
    #expect(await wire.requests.first?.headers["User-Agent"] == "MapRoulette-Mobile-SDK/\(MapRouletteSDK.version)")
}

@Test func environmentsAndTheWriteAllowlist() async throws {
    #expect(MapRouletteEnvironment.production.serviceURL.absoluteString == "https://maproulette.org/api/v2/")
    #expect(MapRouletteEnvironment.staging.serviceURL.absoluteString == "https://mr-api.osm.lol/api/v2/")
    #expect(!MapRouletteEnvironment.production.allowsWrites && MapRouletteEnvironment.staging.allowsWrites)
    let table = try JSONSerialization.jsonObject(
        with: Data(contentsOf: repositoryRoot.appendingPathComponent("fixtures/environments.json"))) as! [String: Any]
    for row in table["cases"] as! [[String: Any]] {
        let url = row["url"] as! String
        do {
            let environment = try MapRouletteEnvironment(serviceURL: URL(string: url)!)
            #expect(row["valid"] as! Bool, "accepted \(url)")
            #expect(environment.allowsWrites == row["writes"] as? Bool, "\(url)")
            #expect(environment.serviceURL.absoluteString.hasSuffix("/"), "\(url)")
        } catch let error as MapRouletteError {
            #expect(!(row["valid"] as! Bool), "rejected \(url)")
            #expect(error.kind == .validation && error.reason?.contains("https") == true)
        }
    }
    // Writes to production fail before any request is sent; reads still work.
    let wire = Recorder(HTTPResponse(status: 200, body: Data()))
    let client = MapRouletteClient(transport: wire, accessToken: { "synthetic-access-token" })
    do {
        try await client.skipTask(try TaskID(1))
        Issue.record("skip was sent to production")
    } catch let error as MapRouletteError {
        #expect(error.kind == .validation && error.reason?.contains("staging") == true)
        #expect(!String(describing: error).contains("synthetic"))
    }
    let task = MapRouletteTask(
        id: try TaskID(1), challengeID: try ChallengeID(1), name: "node/1", status: TaskStatus(code: 0))
    do {
        _ = try await client.submitChoice(task, .answers(["q": "a"]))
        Issue.record("submitChoice was sent to production")
    } catch let error as MapRouletteError {
        #expect(error.kind == .validation && error.reason?.contains("staging") == true)
    }
    #expect(await wire.requests.isEmpty)
}

@Test func validationErrorsCarryReasons() async throws {
    let client = MapRouletteClient(transport: Recorder(HTTPResponse(status: 200, body: Data("[]".utf8))))
    do {
        _ = try await client.searchChallenges(pageSize: 200)
        Issue.record("accepted pageSize 200")
    } catch let error as MapRouletteError {
        #expect(error.reason == "pageSize must be 1...100")
        #expect(error.description == "MapRoulette validation: pageSize must be 1...100")
    }
}
