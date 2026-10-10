import Foundation
import Testing
@testable import MapRoulette

private let fixturesDir = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().appendingPathComponent("fixtures")
nonisolated(unsafe) private let guest = try! JSONSerialization.jsonObject(
    with: Data(contentsOf: fixturesDir.appendingPathComponent("guest.json"))) as! [String: Any]
nonisolated(unsafe) private let choice = try! JSONSerialization.jsonObject(
    with: Data(contentsOf: fixturesDir.appendingPathComponent("choice.json"))) as! [String: Any]
private func data(_ value: Any) -> Data { try! JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]) }
private func ok(_ value: Any, status: Int = 200) -> HTTPResponse { HTTPResponse(status: status, body: data(value)) }

private actor Script: Transport {
    var queue: [HTTPResponse]
    var requests: [HTTPRequest] = []
    init(_ responses: [HTTPResponse]) { queue = responses }
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        requests.append(request)
        guard !queue.isEmpty else { Issue.record("unexpected \(request.url)"); throw URLError(.badURL) }
        return queue.removeFirst()
    }
    func calls() -> [String] { requests.map { "\($0.method.rawValue) \($0.url.path)" } }
}
private func client(_ script: Script, environment: MapRouletteEnvironment = .staging, deletion: Bool = false)
    -> MapRouletteClient
{
    MapRouletteClient(environment: environment, transport: script, allowElementDeletion: deletion,
                      accessToken: { "synthetic-guest-token" })
}
private func slcTask(deletion: Bool = false) async throws -> MapRouletteTask {
    var task = choice["task"] as! [String: Any]
    task["cooperativeWork"] = choice["slc"]
    return try await client(Script([ok(task)]), deletion: deletion).getTask(try TaskID(101))
}
private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

@Test func guestStatusDecodes() async throws {
    let script = Script([ok(guest["status"]!), ok(guest["status_claimed"]!)])
    let c = client(script)
    let status = try await c.getGuestStatus()
    #expect(status.state == .active && status.email == .pending && status.pendingCount == 14)
    #expect(status.publishedCount == 0 && status.claimedAs == nil)
    #expect(status.expiresAt == date("2026-11-09T18:00:00Z"))
    let claimed = try await c.getGuestStatus()
    #expect(claimed.state == .claimed && claimed.claimedAs == ClaimedAccount(displayName: "rosa_slc", osmID: 123))
    #expect(abs(claimed.expiresAt.timeIntervalSince(date("2026-11-09T18:00:00Z")) - 0.25) < 0.001)
    #expect(await script.calls() == ["GET /api/v2/mobile-guest/me", "GET /api/v2/mobile-guest/me"])
    #expect(await script.requests.allSatisfy { $0.headers["Authorization"] == "Bearer synthetic-guest-token" })
}

@Test func pendingSubmitPostsCanonicalBodyWithoutLocking() async throws {
    let task = try await slcTask()
    let script = Script([ok(guest["pending_submit"]!)])
    let result = try await client(script).submitPendingChoice(task, .answers(["material": "wood", "backrest": "yes"]))
    #expect(result.taskID == task.id && result.state == .pending)
    #expect(result.holdUntil == date("2026-10-17T18:00:00Z"))
    // No start/lock: one POST to the pending route.
    #expect(await script.calls() == ["POST /api/v2/task/101/choice/pending"])
    let body = await script.requests[0].body.map { String(decoding: $0, as: UTF8.self) }
    #expect(body == #"{"answers":{"backrest":"yes","material":"wood"}}"#)
}

@Test func pendingGoneOutcomeIsStoredWithoutDeletion() async throws {
    let task = try await slcTask(deletion: true)
    let script = Script([ok(guest["pending_submit"]!)])
    let c = client(script, deletion: true)
    let gone = try #require(c.choiceOutcomes(task).first { $0.deletesElement })
    _ = try await c.submitPendingChoice(task, .outcome(gone))
    let body = await script.requests[0].body.map { String(decoding: $0, as: UTF8.self) }
    #expect(body == "{\"outcome\":\"\(gone.id)\"}")
}

@Test func pendingSubmitValidatesLocallyAndRequiresWrites() async throws {
    let task = try await slcTask()
    let script = Script([])
    await #expect(throws: MapRouletteError.self) {
        try await client(script).submitPendingChoice(task, .answers(["material": "gold"]))
    }
    await #expect(throws: MapRouletteError.self) {
        try await client(script, environment: .production).submitPendingChoice(task, .answers(["backrest": "yes"]))
    }
    await #expect(throws: MapRouletteError.self) { try await client(script, environment: .production).deleteGuest() }
    #expect(await script.requests.isEmpty)
}

@Test func pendingSubmitMapsChoiceProblems() async throws {
    let task = try await slcTask()
    let script = Script([ok(["error": "task_ineligible", "reason": "key_changed", "detail": "x"], status: 409)])
    do {
        _ = try await client(script).submitPendingChoice(task, .answers(["backrest": "yes"]))
        Issue.record("expected failure")
    } catch let error as MapRouletteError {
        #expect(error.kind == .conflict && error.problem == .choice(.taskIneligible(reason: .keyChanged)))
        #expect(error.reason == "task_ineligible")
    }
}

@Test func withdrawSendsDelete() async throws {
    let script = Script([HTTPResponse(status: 204, body: Data()), ok(["error": "not_found"], status: 404)])
    let c = client(script)
    try await c.withdrawPendingChoice(try TaskID(101))
    await #expect(throws: MapRouletteError.self) { try await c.withdrawPendingChoice(try TaskID(101)) }
    #expect(await script.calls() == ["DELETE /api/v2/task/101/choice/pending", "DELETE /api/v2/task/101/choice/pending"])
}

@Test func pendingListPagesWithServerCursor() async throws {
    let last = ["items": [] as [Any], "next": NSNull()] as [String: Any]
    let script = Script([ok(guest["pending_list"]!), ok(last)])
    let c = client(script)
    let first = try await c.listPendingChoices(pageSize: 3)
    #expect(first.items.map(\.state) == [.pending, .published, .skippedStale])
    #expect(first.items[1].changesetID == 1_234_567 && first.items[1].droppedQuestionIDs == ["bench"])
    #expect(first.items[2].holdUntil == nil && first.items[2].changesetID == nil)
    #expect(first.items[0].challengeID == (try ChallengeID(7)))
    let next = try #require(first.next)
    let second = try await c.listPendingChoices(pageSize: 3, after: next)
    #expect(second.items.isEmpty && second.next == nil)
    let queries = await script.requests.map { URLComponents(url: $0.url, resolvingAgainstBaseURL: false)!.queryItems ?? [] }
    #expect(queries[0].first { $0.name == "after" } == nil && queries[0].first { $0.name == "limit" }?.value == "3")
    #expect(queries[1].first { $0.name == "after" }?.value == "c2")
    // A cursor is bound to its page size and client.
    await #expect(throws: MapRouletteError.self) { try await c.listPendingChoices(pageSize: 5, after: next) }
    await #expect(throws: MapRouletteError.self) { try await client(Script([])).listPendingChoices(pageSize: 3, after: next) }
}

@Test func setEmailSendsJSONAndChecksShape() async throws {
    let script = Script([ok(guest["status"]!)])
    let c = client(script)
    let status = try await c.setGuestEmail(" rosa@example.org ")
    #expect(status.email == .pending)
    #expect(await script.calls() == ["PUT /api/v2/mobile-guest/email"])
    let sent = try JSONSerialization.jsonObject(with: try #require(await script.requests[0].body)) as? [String: String]
    #expect(sent == ["email": "rosa@example.org"])
    for bad in ["rosa", "@example.org", "a@b@c", String(repeating: "a", count: 250) + "@b.cd"] {
        await #expect(throws: MapRouletteError.self) { try await c.setGuestEmail(bad) }
    }
}

@Test func deleteGuestSendsDelete() async throws {
    let script = Script([HTTPResponse(status: 204, body: Data())])
    try await client(script).deleteGuest()
    #expect(await script.calls() == ["DELETE /api/v2/mobile-guest"])
}

@Test func guestErrorsCarryOnlyTheCode() async throws {
    for row in guest["errors"] as! [[String: Any]] {
        let script = Script([ok(row["body"]!, status: row["status"] as! Int)])
        do {
            _ = try await client(script).getGuestStatus()
            Issue.record("expected failure for \(row)")
        } catch let error as MapRouletteError {
            #expect(error.kind.rawValue == row["kind"] as! String)
            #expect(error.reason == row["reason"] as? String)
            #expect(!error.description.contains("example.org"))
        }
    }
}

@Test func choiceOnlyFilterExcludesPendingByDefault() async throws {
    let bounds = try Bounds(west: -111.91, south: 40.75, east: -111.87, north: 40.78)
    for exclude in [true, false] {
        let script = Script([ok([] as [Any]), ok(["total": 0, "tasks": [] as [Any]])])
        let c = client(script)
        _ = try await c.findTaskMarkers(filter: TaskFilter(bounds: bounds, choiceOnly: true, excludePending: exclude))
        _ = try await c.findTasksInBounds(filter: TaskFilter(bounds: bounds, choiceOnly: true, excludePending: exclude))
        for request in await script.requests {
            let items = URLComponents(url: request.url, resolvingAgainstBaseURL: false)!.queryItems ?? []
            #expect(items.first { $0.name == "excludePending" }?.value == (exclude ? "true" : nil))
        }
    }
    let script = Script([ok([] as [Any])])
    _ = try await client(script).findTaskMarkers(filter: TaskFilter(bounds: bounds))
    let items = URLComponents(url: await script.requests[0].url, resolvingAgainstBaseURL: false)!.queryItems ?? []
    #expect(!items.contains { $0.name == "excludePending" })
}
