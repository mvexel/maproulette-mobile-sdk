import Foundation
import Testing
@testable import MapRoulette

private let choiceURL = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().appendingPathComponent("fixtures/choice.json")
nonisolated(unsafe) private let fixtures = try! JSONSerialization.jsonObject(with: Data(contentsOf: choiceURL)) as! [String: Any]
private func data(_ value: Any) -> Data {
    try! JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
}
private func rows(_ name: String) -> [[String: Any]] { fixtures[name] as! [[String: Any]] }
private func same(_ a: Any, _ b: Any) -> Bool { NSArray(object: a).isEqual(NSArray(object: b)) }
private let taskID = try! TaskID(101)

/// Replays responses in order; a nil response simulates a connection failure after sending.
private actor Script: Transport {
    var queue: [HTTPResponse?]
    var requests: [HTTPRequest] = []
    init(_ responses: [HTTPResponse?]) { queue = responses }
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        requests.append(request)
        guard !queue.isEmpty else { Issue.record("unexpected \(request.url)"); throw URLError(.badURL) }
        guard let next = queue.removeFirst() else { throw URLError(.networkConnectionLost) }
        return next
    }
    func calls() -> [String] { requests.map { "\($0.method.rawValue) \($0.url.path)" } }
}
private func response(_ row: Any) -> HTTPResponse? {
    guard let pair = row as? [Any] else { return nil }
    return HTTPResponse(status: pair[0] as! Int, body: pair[1] is NSNull ? Data() : data(pair[1]))
}
private func client(_ script: Script, deletion: Bool = false) throws -> MapRouletteClient {
    try MapRouletteClient(transport: script, allowElementDeletion: deletion, accessToken: { "synthetic-access-token" })
}
private func taskJSON(_ payload: Any?, _ extra: [String: Any] = [:]) -> [String: Any] {
    var task = fixtures["task"] as! [String: Any]
    task.merge(extra) { $1 }
    if let payload { task["cooperativeWork"] = payload }
    return task
}
private func decode(_ task: [String: Any]) async throws -> MapRouletteTask {
    try await client(Script([HTTPResponse(status: 200, body: data(task))])).getTask(taskID)
}
private func slcTask() async throws -> MapRouletteTask { try await decode(taskJSON(fixtures["slc"])) }
private func null(_ value: String?) -> Any { value ?? NSNull() }

private func choiceJSON(element: OSMElementRef, match: [String: String], questions: [ChoiceQuestion]) -> [String: Any] {
    [
        "element": "\(element.type.rawValue)/\(element.id)", "match": match,
        "questions": questions.map { q in
            [
                "id": q.id, "prompt": q.prompt, "description": null(q.description),
                "expect": q.expect.mapValues { null($0) },
                "options": q.options.map { o in
                    ["id": o.id, "label": o.label, "description": null(o.description), "setTags": o.setTags, "unsetTags": o.unsetTags] as [String: Any]
                },
            ] as [String: Any]
        },
    ]
}
private func outcomeJSON(_ o: ChoiceOutcome) -> [String: Any] {
    ["id": o.id, "label": o.label, "description": null(o.description), "resolution": o.resolution.rawValue, "deletesElement": o.deletesElement]
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
    case .choice(let choice)?:
        switch choice {
        case .taskIneligible: "taskIneligible"
        case .elementInUse: "elementInUse"
        case .osmReauthRequired: "osmReauthRequired"
        case .osmScopeRequired: "osmScopeRequired"
        case .osmUnavailable: "osmUnavailable"
        case .submissionPending: "submissionPending"
        case .statusPending: "statusPending"
        case .invalidSubmission: "invalidSubmission"
        case .unsupportedTask: "unsupportedTask"
        }
    }
}
private func reasonName(_ reason: IneligibleReason?) -> String? {
    switch reason {
    case nil: nil
    case .elementGone?: "elementGone"
    case .matchFailed?: "matchFailed"
    case .keyChanged?: "keyChanged"
    case .unknown?: "unknown"
    }
}
private func kindName(_ kind: ErrorKind) -> String { kind == .protocolFailure ? "protocol" : kind.rawValue }
private func failure<T>(_ operation: () async throws -> T) async -> MapRouletteError? {
    do { _ = try await operation(); Issue.record("Expected failure"); return nil }
    catch let error as MapRouletteError { return error }
    catch { Issue.record("Unexpected \(error)"); return nil }
}
private func submission(_ task: MapRouletteTask, _ row: [String: Any], deletion: Bool) -> ChoiceSubmission {
    let s = row["submission"] as! [String: Any]
    if let answers = s["answers"] as? [String: String] { return .answers(answers) }
    let id = s["outcome"] as! String
    let decodedWith = s["decodedWithDeletion"] as? Bool ?? deletion
    return .outcome(
        task.choiceOutcomes(allowElementDeletion: decodedWith).first { $0.id == id }
            ?? ChoiceOutcome(id: id, label: "Synthetic", description: nil, resolution: .notAnIssue, deletesElement: false))
}

@Test func slcExampleDecodesWithDeletionOffAndOn() async throws {
    let task = try await slcTask()
    for deletion in [false, true] {
        guard case .choice(let element, let match, let questions, let outcomes) = task.work(allowElementDeletion: deletion) else {
            Issue.record("not a choice task"); continue
        }
        #expect(same(choiceJSON(element: element, match: match, questions: questions), fixtures["slc_expect"]!))
        let expected = (fixtures["outcomes"] as! [String: Any])[deletion ? "deletion_on" : "deletion_off"]!
        let all = task.choiceOutcomes(allowElementDeletion: deletion)
        #expect(same(all.map(outcomeJSON), expected))
        #expect(Array(all.dropLast()) == outcomes)
    }
    #expect(task.choiceOutcomes() == task.choiceOutcomes(allowElementDeletion: false))
    #expect(try await decode(taskJSON(nil)).choiceOutcomes().isEmpty)
}

@Test func payloadValidationRules() async throws {
    for row in rows("valid") {
        let task = try await decode(taskJSON(row["payload"]))
        guard case .choice = task.work() else { Issue.record("\(row["name"]!) did not decode"); continue }
    }
    var rules = Set<Int>()
    for row in rows("invalid") {
        let task = try await decode(taskJSON(row["payload"]))
        for deletion in [false, true] {
            guard case .unknown(let raw) = task.work(allowElementDeletion: deletion) else {
                Issue.record("\(row["name"]!) decoded"); continue
            }
            #expect(raw == task.cooperativeWork)
        }
        #expect(task.mobileSupport() == .unsupported, "\(row["name"]!)")
        #expect(task.choiceOutcomes().isEmpty)
        rules.insert(row["rule"] as! Int)
    }
    #expect(rules == [1, 2, 3, 4, 5])
}

@Test func onlyActionableUnbundledChoiceTasksAreInPlace() async throws {
    let invalid = rows("invalid")[0]["payload"]!
    for row in rows("support") {
        let payload: Any? = switch row["payload"] as? String { case "slc": fixtures["slc"]; case "invalid": invalid; default: nil }
        var extra: [String: Any] = ["status": row["status"]!]
        extra["bundleId"] = row["bundleId"]
        let task = try await decode(taskJSON(payload, extra))
        let name = row["name"] as! String
        #expect((task.mobileSupport() == .inPlace ? "inPlace" : "unsupported") == row["support"] as! String, "\(name)")
        #expect(task.canSkip() == row["skip"] as! Bool, "\(name)")
        #expect(task.allowedResolutions().isEmpty, "\(name)")
    }
}

@Test func submissionBodiesAreCanonical() async throws {
    let ok = (rows("flows")[0]["responses"] as! [Any]).map(response)
    for row in rows("submissions") {
        let deletion = row["deletion"] as! Bool
        let task = try await slcTask()
        let script = Script(ok)
        _ = try await client(script, deletion: deletion).submitChoice(task, submission(task, row, deletion: deletion))
        let name = row["name"] as! String
        #expect(await script.calls() == ["GET /api/v2/task/101/start", "POST /api/v2/task/101/choice"], "\(name)")
        let requests = await script.requests
        #expect(requests[1].body.map { String(decoding: $0, as: UTF8.self) } == row["body"] as? String, "\(name)")
        #expect(requests[1].headers["Content-Type"] == "application/json")
        #expect(requests[1].headers["Authorization"] == "Bearer synthetic-access-token"); #expect(requests[1].headers["apiKey"] == nil)
        #expect(requests[0].body == nil)
    }
}

@Test func invalidSubmissionsSendNothing() async throws {
    for row in rows("invalid_submissions") {
        let payload: Any? = row.keys.contains("payload")
            ? (row["payload"] as? String == "invalid" ? rows("invalid")[0]["payload"] : nil) : fixtures["slc"]
        var extra: [String: Any] = [:]
        extra["bundleId"] = row["bundleId"]
        let task = try await decode(taskJSON(payload, extra))
        let deletion = row["clientDeletion"] as? Bool ?? false
        let script = Script([])
        let error = await failure { try await client(script, deletion: deletion).submitChoice(task, submission(task, row, deletion: deletion)) }
        #expect(error?.kind == .validation, "\(row["name"]!)")
        #expect(await script.requests.isEmpty, "\(row["name"]!)")
    }
}

@Test func errorMappingsReleaseTheLock() async throws {
    let start = HTTPResponse(status: 200, body: data(fixtures["start"]!))
    let released = HTTPResponse(status: 200, body: data(fixtures["task"]!))
    for row in rows("errors") {
        let name = row["name"] as! String
        let task = try await slcTask()
        let failed = HTTPResponse(status: row["status"] as! Int, body: row["body"] is NSNull ? Data() : data(row["body"]!))
        let script = Script([start, failed, released])
        let error = await failure { try await client(script).submitChoice(task, .answers(["backrest": "yes"])) }
        #expect(error.map { kindName($0.kind) } == row["kind"] as? String, "\(name)")
        #expect(problemName(error?.problem) == row["problem"] as? String, "\(name)")
        switch error?.problem {
        case .choice(.taskIneligible(let reason))?:
            #expect(reasonName(reason) == row["reason"] as? String, "\(name)")
        case .choice(.invalidSubmission(let detail))?:
            #expect(detail == row["detail"] as? String, "\(name)")
        default: break
        }
        var calls = ["GET /api/v2/task/101/start", "POST /api/v2/task/101/choice"]
        if row["release"] as! Bool { calls.append("GET /api/v2/task/101/release") }
        #expect(await script.calls() == calls, "\(name)")
        #expect(!String(describing: error).contains("synthetic")); #expect(!String(describing: error).contains("bench"))
    }
}

@Test func submitFlowsRetryOnlyWhereIdempotent() async throws {
    for row in rows("flows") {
        let name = row["name"] as! String
        let deletion = row["deletion"] as? Bool ?? false
        var extra: [String: Any] = [:]
        extra["status"] = row["taskStatus"]
        let task = try await decode(taskJSON(fixtures["slc"], extra))
        let script = Script((row["responses"] as! [Any]).map(response))
        let submit = { try await client(script, deletion: deletion).submitChoice(task, submission(task, row, deletion: deletion)) }
        if let result = row["result"] as? [String: Any] {
            let got = try await submit()
            #expect(got.status.code == result["status"] as! Int, "\(name)")
            #expect(got.changesetID == (result["changesetId"] as? Int).map(Int64.init), "\(name)")
        } else {
            let expected = row["error"] as! [String: Any]
            let error = await failure(submit)
            #expect(error.map { kindName($0.kind) } == expected["kind"] as? String, "\(name)")
            #expect(problemName(error?.problem) == expected["problem"] as? String, "\(name)")
            if let changeset = expected["changesetId"] as? Int {
                #expect(error?.problem == .choice(.statusPending(changesetID: Int64(changeset))), "\(name)")
            }
        }
        #expect(await script.calls() == row["calls"] as! [String], "\(name)")
    }
}

@Test func checkChoiceMapsEligibility() async throws {
    for row in rows("check") {
        let name = row["name"] as! String
        let script = Script([HTTPResponse(status: row["status"] as! Int, body: row["body"] is NSNull ? Data() : data(row["body"]!))])
        if let expect = row["expect"] as? [String: Any] {
            let got = try await client(script).checkChoice(taskID)
            #expect(got.eligible == expect["eligible"] as! Bool, "\(name)")
            #expect(got.deleteAllowed == expect["deleteAllowed"] as! Bool, "\(name)")
            #expect(reasonName(got.reason) == expect["reason"] as? String, "\(name)")
        } else {
            let expected = row["error"] as! [String: Any]
            let error = await failure { try await client(script).checkChoice(taskID) }
            #expect(error.map { kindName($0.kind) } == expected["kind"] as? String, "\(name)")
            #expect(problemName(error?.problem) == expected["problem"] as? String, "\(name)")
        }
        let requests = await script.requests
        #expect(await script.calls() == ["GET /api/v2/task/101/choice/check"])
        #expect(requests[0].body == nil); #expect(requests[0].url.query == nil)
    }
}

@Test func identityReportsOsmTagfixScope() async throws {
    for row in rows("identity") {
        var me = fixtures["me"] as! [String: Any]
        me["scope"] = row["scope"]
        let identity = try await client(Script([HTTPResponse(status: 200, body: data(me))])).getCurrentUser()
        #expect(identity.canWriteTasks == row["canWriteTasks"] as! Bool, "\(row)")
        #expect(identity.canEditOsm == row["canEditOsm"] as! Bool, "\(row)")
    }
    #expect(!UserIdentity(id: 900, guest: false).canEditOsm)
}

@Test func choiceOnlyFilterAddsCooperativeTypeParameters() async throws {
    let expected = fixtures["markers_query"] as! [String: String]
    let bounds = try Bounds(west: -111.91, south: 40.75, east: -111.87, north: 40.78)
    for choiceOnly in [false, true] {
        let script = Script([HTTPResponse(status: 200, body: Data("[]".utf8)),
                             HTTPResponse(status: 200, body: Data(#"{"total":0,"tasks":[]}"#.utf8))])
        let client = try client(script)
        _ = try await client.findTaskMarkers(filter: TaskFilter(bounds: bounds, choiceOnly: choiceOnly))
        _ = try await client.findTasksInBounds(filter: TaskFilter(bounds: bounds, choiceOnly: choiceOnly))
        for request in await script.requests {
            let items = URLComponents(url: request.url, resolvingAgainstBaseURL: false)!.queryItems ?? []
            for (key, value) in expected {
                #expect(items.first { $0.name == key }?.value == (choiceOnly ? value : nil), "\(request.url)")
            }
        }
    }
}
