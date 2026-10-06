import Foundation
import Testing
@testable import MapRoulette

private let lifecycleURL = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().appendingPathComponent("fixtures/task-completion.json")
nonisolated(unsafe) private let root = try! JSONSerialization.jsonObject(with: Data(contentsOf: lifecycleURL)) as! [String: Any]
private func data(_ value: Any) -> Data {
    try! JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
}
private func body(_ name: String?) -> Data { name.map { data(root[$0]!) } ?? Data() }
private func rows(_ name: String) -> [[String: Any]] { root[name] as! [[String: Any]] }
private func ok(_ name: String? = nil, _ status: Int = 200) -> HTTPResponse? {
    HTTPResponse(status: status, body: body(name))
}

/// Replays responses in order; a nil response simulates a connection failure after sending.
private actor Script: Transport {
    var queue: [HTTPResponse?]
    var requests: [HTTPRequest] = []
    init(_ responses: HTTPResponse?...) { queue = responses }
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        requests.append(request)
        guard let next = queue.removeFirst() else { throw URLError(.networkConnectionLost) }
        return next
    }
    func calls() -> [String] {
        requests.map { "\($0.method.rawValue) " + $0.url.path.replacingOccurrences(of: "/api/v2/", with: "") }
    }
}
private func client(_ script: Script) throws -> MapRouletteClient {
    try MapRouletteClient(transport: script, apiKey: { "synthetic-user-key" })
}
private let taskID = try! TaskID(101)

private func call(_ client: MapRouletteClient, _ row: [String: Any]) async throws {
    switch row["call"] as! String {
    case "start": _ = try await client.startTask(taskID)
    case "refresh": _ = try await client.refreshTaskLock(taskID)
    case "release": try await client.releaseTask(taskID)
    case "skip": try await client.skipTask(taskID)
    default:
        let resolution = TaskResolution(rawValue: row["resolution"] as? Int ?? 1)!
        try await client.resolveTask(taskID, as: resolution)
    }
}
private func failure(_ operation: () async throws -> Void) async -> MapRouletteError? {
    do { try await operation(); Issue.record("Expected failure"); return nil }
    catch let error as MapRouletteError { return error }
    catch { Issue.record("Unexpected \(error)"); return nil }
}
private func problemName(_ problem: WriteProblem?) -> String? {
    switch problem {
    case nil: nil
    case .lockedByOtherUser?: "lockedByOtherUser"
    case .alreadyHoldingTask?: "alreadyHoldingTask"
    case .lockLost?: "lockLost"
    case .invalidTransition?: "invalidTransition"
    case .insufficientScope?: "insufficientScope"
    case .outcomeUnknown?: "outcomeUnknown"
    case .choice?: "choice"
    }
}

@Test func writeRoutesAreBareWithOneCredential() async throws {
    for row in rows("routes") {
        for bearer in [false, true] {
            let script = Script(HTTPResponse(status: row["status"] as! Int, body: body(row["body"] as? String)))
            let client = bearer
                ? try MapRouletteClient(transport: script, accessToken: { "synthetic-access-token" })
                : try client(script)
            try await call(client, row)
            let requests = await script.requests
            #expect(requests.count == 1)
            let request = requests[0]
            #expect(request.method.rawValue == row["method"] as! String)
            #expect(request.url.absoluteString == "https://maproulette.org" + (row["path"] as! String))
            #expect(request.body == nil); #expect(request.headers["Content-Type"] == nil)
            if bearer {
                #expect(request.headers["Authorization"] == "Bearer synthetic-access-token"); #expect(request.headers["apiKey"] == nil)
            } else {
                #expect(request.headers["apiKey"] == "synthetic-user-key"); #expect(request.headers["Authorization"] == nil)
            }
        }
    }
    #expect(TaskResolution.allCases.map(\.rawValue) == [1, 2, 5, 6])
}

@Test func startReturnsLockAndBundleMembers() async throws {
    let lock = try await client(Script(ok("start_200"))).startTask(taskID)
    #expect(lock.task.id == taskID); #expect(lock.primaryTaskID == taskID); #expect(lock.bundledTaskIDs.isEmpty)
    let bundle = try await client(Script(ok("start_200_bundle"))).startTask(taskID)
    #expect(bundle.primaryTaskID == (try TaskID(100))); #expect(bundle.bundledTaskIDs == [try TaskID(100), try TaskID(102)])
}

@Test func errorMappingsCarryLifecycleProblemsWithoutBodies() async throws {
    for row in rows("errors") {
        let script = Script(HTTPResponse(status: row["status"] as! Int, headers: ["Retry-After": "5"], body: body(row["body"] as? String)))
        let client = try client(script)
        let error = try #require(await failure { try await call(client, row) })
        #expect(error.kind.rawValue == (row["kind"] as! String) || (error.kind == .protocolFailure && row["kind"] as! String == "protocol"), "\(row)")
        #expect(problemName(error.problem) == row["problem"] as? String, "\(row)")
        #expect(await script.requests.count == 1)
        #expect(!String(describing: error).contains("other-mapper")); #expect(!String(describing: error).contains("synthetic"))
    }
    let held = await failure { _ = try await client(Script(ok("start_409_own", 409))).startTask(taskID) }
    #expect(held?.problem == .alreadyHoldingTask(lockedTaskID: try TaskID(555), challengeID: try ChallengeID(43),
        challengeName: "Survey shops", startedAt: "2026-10-05T11:58:00.000Z"))
    let minimal = await failure { _ = try await client(Script(ok("start_409_own_minimal", 409))).startTask(taskID) }
    #expect(minimal?.problem == .alreadyHoldingTask(lockedTaskID: try TaskID(555), challengeID: nil, challengeName: nil, startedAt: nil))
    let other = await failure { _ = try await client(Script(ok("start_403_other", 403))).startTask(taskID) }
    #expect(other?.problem == .lockedByOtherUser(message: "Task is currently locked by user other-mapper"))
}

@Test func interruptedWritesAreUnknownAndNeverRetried() async throws {
    for row in rows("routes") {
        let script = Script(nil)
        let client = try client(script)
        let error = await failure { try await call(client, row) }
        #expect(error?.kind == .network); #expect(error?.problem == .outcomeUnknown)
        #expect(await script.requests.count == 1)
    }
}

@Test func commitHappyPathStartsThenWritesStatus() async throws {
    let script = Script(ok("start_200"), ok(nil, 204))
    try await client(script).commitResolution(taskID, as: .alreadyFixed)
    #expect(await script.calls() == ["GET task/101/start", "PUT task/101/5"])
}

@Test func commitStopsWhenAnotherUserHoldsTheLock() async throws {
    let script = Script(ok("start_403_other", 403))
    let client = try client(script)
    let error = await failure { try await client.commitResolution(taskID, as: .fixed) }
    guard case .lockedByOtherUser? = error?.problem else { Issue.record("Expected lockedByOtherUser"); return }
    #expect(await script.calls() == ["GET task/101/start"])
}

@Test func commitReleasesOnceWhenStatusWriteFails() async throws {
    let rejected = Script(ok("start_200"), ok("resolve_400_invalid", 400), ok("task"))
    let first = try client(rejected)
    let error = await failure { try await first.commitResolution(taskID, as: .tooHard) }
    #expect(error?.problem == .invalidTransition)
    #expect(await rejected.calls() == ["GET task/101/start", "PUT task/101/6", "GET task/101/release"])
    // A failing best-effort release never replaces the original error.
    let interrupted = Script(ok("start_200"), nil, nil)
    let second = try client(interrupted)
    let unknown = await failure { try await second.commitResolution(taskID, as: .fixed) }
    #expect(unknown?.problem == .outcomeUnknown); #expect(unknown?.kind == .network)
    #expect(await interrupted.calls() == ["GET task/101/start", "PUT task/101/1", "GET task/101/release"])
}

@Test func commitReleasesAfterInterruptedStartWithoutWriting() async throws {
    let script = Script(nil, ok("task"))
    let client = try client(script)
    let error = await failure { try await client.commitResolution(taskID, as: .fixed) }
    #expect(error?.problem == .outcomeUnknown)
    #expect(await script.calls() == ["GET task/101/start", "GET task/101/release"])
}

@Test func commitRecoversOwnStaleLockOnce() async throws {
    let locked = Script(ok("start_409_own", 409), ok("stale_locked"), ok("task"), ok("start_200"), ok(nil, 204))
    try await client(locked).commitResolution(taskID, as: .notAnIssue)
    #expect(await locked.calls() == ["GET task/101/start", "GET task/555", "GET task/555/release", "GET task/101/start", "PUT task/101/2"])

    let resolved = Script(ok("start_409_own", 409), ok("stale_resolved"), ok("start_200"), ok(nil, 204))
    try await client(resolved).commitResolution(taskID, as: .fixed)
    #expect(await resolved.calls() == ["GET task/101/start", "GET task/555", "GET task/101/start", "PUT task/101/1"])

    let missing = Script(ok("start_409_own", 409), HTTPResponse(status: 404, body: Data()), ok("start_200"), ok(nil, 204))
    try await client(missing).commitResolution(taskID, as: .fixed)
    #expect(await missing.calls() == ["GET task/101/start", "GET task/555", "GET task/101/start", "PUT task/101/1"])

    let again = Script(ok("start_409_own", 409), ok("stale_locked"), ok("task"), ok("start_409_own", 409))
    let againClient = try client(again)
    let error = await failure { try await againClient.commitResolution(taskID, as: .fixed) }
    guard case .alreadyHoldingTask? = error?.problem else { Issue.record("Expected alreadyHoldingTask"); return }
    #expect(await again.calls() == ["GET task/101/start", "GET task/555", "GET task/555/release", "GET task/101/start"])

    let retryLost = Script(ok("start_409_own", 409), ok("stale_locked"), ok("task"), nil, ok("task"))
    let retryClient = try client(retryLost)
    let lost = await failure { try await retryClient.commitResolution(taskID, as: .fixed) }
    #expect(lost?.problem == .outcomeUnknown)
    #expect(await retryLost.calls() == ["GET task/101/start", "GET task/555", "GET task/555/release", "GET task/101/start", "GET task/101/release"])

    let unreadable = Script(ok("start_409_own", 409), nil)
    let unreadableClient = try client(unreadable)
    let original = await failure { try await unreadableClient.commitResolution(taskID, as: .fixed) }
    #expect(original?.kind == .conflict); #expect(await unreadable.requests.count == 2)
}

@Test func commitRequiresWritePermissionAndValidSession() async throws {
    for (name, status, problem) in [("gate_403_scope", 403, WriteProblem.insufficientScope), ("gate_401_invalid", 401, nil)] {
        let script = Script(ok(name, status))
        let client = try client(script)
        let error = await failure { try await client.commitResolution(taskID, as: .fixed) }
        #expect(error?.problem == problem); #expect(await script.requests.count == 1)
    }
}

@Test func readModelLockFieldsAndWriteScope() async throws {
    let task = try await client(Script(ok("task_lock_fields"))).getTask(taskID)
    #expect(task.lockedBy == 901); #expect(task.completedBy == 900)
    #expect(task.mappedOn == "2026-10-05T12:00:00.000Z"); #expect(task.reviewStatus == 0); #expect(task.bundleID == nil)
    let plain = try await client(Script(ok("task"))).getTask(taskID)
    #expect(plain.lockedBy == nil); #expect(plain.completedBy == nil)
    let writer = try await MapRouletteClient(transport: Script(ok("identity_mobile_write")),
        accessToken: { "synthetic-access-token" }).getCurrentUser()
    #expect(writer.scopes == ["tasks:read", "tasks:write"]); #expect(writer.canWriteTasks)
    #expect(!UserIdentity(id: 900, guest: false, scopes: ["tasks:read"]).canWriteTasks)
    #expect(UserIdentity(id: 900, guest: false).canWriteTasks); #expect(!UserIdentity(id: 900, guest: true).canWriteTasks)
}

private func decode(_ task: [String: Any]) async throws -> MapRouletteTask {
    try await client(Script(HTTPResponse(status: 200, body: data(task)))).getTask(taskID)
}
private func kindTask(_ name: String) -> [String: Any] {
    rows("kinds").first { $0["name"] as? String == name }!["task"] as! [String: Any]
}
private func elementName(_ element: OSMElementRef?) -> String? {
    element.map { "\($0.type.rawValue)/\($0.id)" }
}
private func kindName(_ kind: ElementTagEdit.Kind) -> String {
    switch kind { case .modify: "modify"; case .create: "create"; case .delete: "delete"; case .unknown: "unknown" }
}

@Test func taskKindsDecodeLeniently() async throws {
    for row in rows("kinds") {
        let task = try await decode(row["task"] as! [String: Any])
        let name = row["name"] as! String
        switch (row["work"] as! String, task.work()) {
        case ("standard", .standard): break
        case ("tagFix", .tagFix(let version, let edits)):
            #expect(version == row["version"] as! Int, "\(name)")
            let expected = row["edits"] as! [[String: Any]]
            #expect(edits.map { elementName($0.element) } == expected.map { $0["element"] as? String }, "\(name)")
            #expect(edits.map { kindName($0.kind) } == expected.map { $0["kind"] as! String }, "\(name)")
            #expect(edits.map(\.setTags) == expected.map { $0["setTags"] as! [String: String] }, "\(name)")
            #expect(edits.map(\.unsetTags) == expected.map { $0["unsetTags"] as! [String] }, "\(name)")
        case ("changeFile", .changeFile(let format, let encoding)):
            #expect(format == row["format"] as? String); #expect(encoding == row["encoding"] as? String)
        case ("unknown", .unknown(let raw)):
            #expect(raw == task.cooperativeWork, "\(name)")
        case (let expected, let actual):
            Issue.record("\(name): expected \(expected), got \(actual)")
        }
        let support: String = switch task.mobileSupport() {
        case .inPlace: "inPlace"; case .unsupported: "unsupported"
        }
        #expect(support == row["support"] as! String, "\(name)")
    }
    for row in rows("challenge_kinds") {
        var challenge: [String: Any] = ["id": 42, "parent": 7, "name": "c"]
        challenge["cooperativeType"] = row["cooperativeType"]
        let decoded = try await client(Script(HTTPResponse(status: 200, body: data(challenge)))).getChallenge(ChallengeID(42))
        let kind: String = switch decoded.cooperativeKind {
        case .none: "none"; case .tags: "tags"; case .changeFile: "changeFile"; case .unknown: "unknown"
        }
        #expect(kind == row["kind"] as! String)
    }
}

@Test func allowedResolutionsFollowKindAndStatus() async throws {
    for row in rows("allowed") {
        var raw = kindTask(row["kind"] as! String)
        raw["status"] = row["status"]
        let task = try await decode(raw)
        #expect(Set(task.allowedResolutions().map(\.rawValue)) == Set(row["resolutions"] as! [Int]), "\(row)")
        #expect(task.canSkip() == row["skip"] as! Bool, "\(row)")
    }
}

@Test func verifyResolutionAppliesReReadRules() async throws {
    for row in rows("verify") {
        var raw = root["task"] as! [String: Any]
        for key in ["status", "lockedBy", "completedBy"] { raw[key] = row[key] }
        let task = try await decode(raw)
        let check = task.verifyResolution(target: TaskResolution(rawValue: row["target"] as! Int)!, me: 900)
        #expect("\(check)" == row["expect"] as! String, "\(row)")
    }
}

@Test func instructionsResolveSubstituteAndListFormFields() async throws {
    let fixture = root["instruction"] as! [String: Any]
    let task = try await decode(fixture["task"] as! [String: Any])
    let challengeJSON: [String: Any] = ["id": 42, "parent": 7, "name": "c", "instruction": fixture["challenge"]!]
    let challenge = try await client(Script(HTTPResponse(status: 200, body: data(challengeJSON)))).getChallenge(ChallengeID(42))
    let instruction = task.resolvedInstruction(challenge: challenge)
    #expect(instruction.markdown == fixture["challenge"] as! String)
    let properties = task.templateProperties()
    #expect(properties == fixture["properties"] as! [String: String])
    #expect(instruction.render(properties) == fixture["rendered"] as! String)
    let fields: [FormField] = (fixture["formFields"] as! [[String: Any]]).map {
        $0["type"] as! String == "select"
            ? .select(name: $0["name"] as! String, label: $0["label"] as! String, values: $0["values"] as! [String])
            : .checkbox(name: $0["name"] as! String, label: $0["label"] as! String)
    }
    #expect(instruction.formFields == fields)
    for row in rows("instruction_choice") {
        var raw = root["task"] as! [String: Any]
        raw["instruction"] = row["task"]
        let choice = try await decode(raw)
        var parentJSON: [String: Any] = ["id": 42, "parent": 7, "name": "c"]
        parentJSON["instruction"] = row["challenge"]
        let parent = try await client(Script(HTTPResponse(status: 200, body: data(parentJSON)))).getChallenge(ChallengeID(42))
        #expect(choice.resolvedInstruction(challenge: parent).markdown == row["expect"] as! String)
    }
    for row in rows("osm_identity") {
        var raw = root["task"] as! [String: Any]
        raw["geometries"] = ["type": "FeatureCollection", "features": [row["feature"]!]]
        let identified = try await decode(raw).templateProperties()
        #expect(identified["#osmId"] == row["osmId"] as? String, "\(row)")
        #expect(identified["#osmType"] == row["osmType"] as? String, "\(row)")
    }
}

/// Raw text of an object under "float_numbers"; JSONSerialization would normalize 1.0/1e5 literals.
private func floatFixture(_ key: String) -> String {
    let text = try! String(contentsOf: lifecycleURL, encoding: .utf8)
    let section = text.range(of: "\"float_numbers\"")!
    let start = text.range(of: "\"\(key)\": {", range: section.upperBound..<text.endIndex)!.upperBound
    var depth = 0
    var index = text.index(before: start)
    repeat {
        if text[index] == "{" { depth += 1 } else if text[index] == "}" { depth -= 1 }
        index = text.index(after: index)
    } while depth > 0
    return String(text[text.index(before: start)..<index])
}

@Test func integerFieldsWrittenAsFloatsDecodeAsWholeNumbers() async throws {
    let fixture = root["float_numbers"] as! [String: Any]
    let raw = floatFixture("task")
    #expect(raw.contains("1.01e2"))
    let expect = fixture["expect"] as! [String: Any]
    let task = try await client(Script(HTTPResponse(status: 200, body: Data(raw.utf8)))).getTask(taskID)
    #expect(task.challengeID.value == expect["challengeId"] as! Int64)
    #expect(task.status?.code == expect["status"] as? Int)
    #expect(task.lockedBy == expect["lockedBy"] as? Int64)
    #expect(task.completedBy == expect["completedBy"] as? Int64)
    #expect(task.reviewStatus == expect["reviewStatus"] as? Int)
    #expect(task.mappedOn == expect["mappedOn"] as? String)
    guard case .tagFix(let version, _) = task.work() else { Issue.record("Expected tagFix"); return }
    #expect(version == expect["workVersion"] as! Int)
    #expect(task.templateProperties() == expect["properties"] as! [String: String])
    let lock = try await client(Script(HTTPResponse(status: 200, body: Data(floatFixture("start").utf8)))).startTask(taskID)
    #expect(lock.primaryTaskID == taskID); #expect(lock.bundledTaskIDs == [try TaskID(100), try TaskID(102)])
    let conflict = await failure {
        _ = try await client(Script(HTTPResponse(status: 409, body: Data(floatFixture("conflict").utf8)))).startTask(taskID)
    }
    #expect(conflict?.problem == .alreadyHoldingTask(lockedTaskID: try TaskID(555), challengeID: try ChallengeID(43), challengeName: nil, startedAt: nil))
    for row in fixture["rejected"] as! [[String: String]] {
        let field = row["field"]!
        let changed = raw.replacingOccurrences(of: "\"\(field)\":\\s*[^,]+,", with: "\"\(field)\": \(row["value"]!),",
            options: .regularExpression, range: raw.range(of: "\"\(field)\":\\s*[^,]+,", options: .regularExpression))
        let reader = try client(Script(HTTPResponse(status: 200, body: Data(changed.utf8))))
        let error = await failure { _ = try await reader.getTask(taskID) }
        #expect(error?.kind == .protocolFailure, "\(row)")
    }
    var unknownRaw = root["task"] as! [String: Any]
    unknownRaw["cooperativeWork"] = fixture["unknown_work"]
    guard case .unknown = try await decode(unknownRaw).work() else { Issue.record("Expected unknown"); return }
}
