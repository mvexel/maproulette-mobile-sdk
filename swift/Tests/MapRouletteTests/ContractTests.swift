import Foundation
import Testing
@testable import MapRoulette

private let fixtureURL = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().appendingPathComponent("fixtures/contract.json")
private func fixture(_ name: String) throws -> Data {
    let root = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as! [String: Any]
    return try JSONSerialization.data(withJSONObject: root[name]!)
}
private func json(_ data: Data) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: data) }
private func field(_ value: JSONValue, _ name: String) -> JSONValue? {
    guard case .object(let fields) = value else { return nil }; return fields[name]
}
private actor Fake: Transport {
    var response: HTTPResponse
    var requests: [HTTPRequest] = []
    init(_ name: String) throws { response = HTTPResponse(status: 200, body: try fixture(name)) }
    func set(_ response: HTTPResponse) { self.response = response }
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse { requests.append(request); return response }
    func last() -> HTTPRequest { requests.last! }
}
private func query(_ request: HTTPRequest, _ key: String) -> String? {
    URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == key }?.value
}
private func errorKind(_ expected: ErrorKind, _ operation: () async throws -> Void) async throws {
    do { try await operation(); Issue.record("Expected \(expected)") }
    catch let error as MapRouletteError { #expect(error.kind == expected) }
}

@Test func challengeShapesOptionalFieldsAndTags() async throws {
    let wire = try Fake("challenge_direct"); let client = MapRouletteClient(transport: wire)
    let direct = try await client.getChallenge(ChallengeID(42))
    #expect(direct.projectID.value == 7); #expect(direct.description == nil); #expect(direct.tags == nil)
    await wire.set(HTTPResponse(status: 200, body: try fixture("challenges_search")))
    let page = try await client.searchChallenges(pageSize: 2)
    #expect(page.items[0].projectID == direct.projectID); #expect(page.items[0].name == direct.name)
    #expect(page.items[1].enabled == nil); #expect(page.items[1].tags == ["mobile", "cycling"])
    await wire.set(HTTPResponse(status: 200, body: try fixture("tags")))
    let tags = try await client.getChallengeTags(ChallengeID(42))
    #expect(tags.count == 1); #expect(tags[0].id == 8); #expect(tags[0].name == "cycling")
}

@Test func challengeOffsetsEncodingAndContinuationBinding() async throws {
    let wire = try Fake("challenges_search"); let client = MapRouletteClient(transport: wire)
    let filter = ChallengeFilter(tags: ["bike & repair", "café"], text: "backrest?")
    let first = try await client.searchChallenges(filter: filter, pageSize: 2)
    _ = try await client.searchChallenges(filter: filter, pageSize: 2, after: first.next)
    let request = await wire.last()
    #expect(query(request, "page") == "2"); #expect(query(request, "ct") == "bike & repair,café")
    #expect(query(request, "cs") == "backrest?"); #expect(query(request, "cLocal") == "1")
    #expect(query(request, "ca") == "false"); #expect(query(request, "ce") == "true")
    try await errorKind(.validation) { _ = try await client.searchChallenges(filter: filter, pageSize: 1, after: first.next) }
    let another = MapRouletteClient(transport: wire)
    try await errorKind(.validation) { _ = try await another.searchChallenges(filter: filter, pageSize: 2, after: first.next) }
    await wire.set(HTTPResponse(status: 200, body: Data("[]".utf8)))
    let empty = try await client.searchChallenges(filter: filter, pageSize: 2, after: first.next)
    #expect(empty.next == nil)
}

@Test func taskGeometryUnknownStatusAndPageNumber() async throws {
    let wire = try Fake("task"); let client = MapRouletteClient(transport: wire)
    let task = try await client.getTask(TaskID(101)); let raw = try json(fixture("task"))
    #expect(task.status?.code == 73); #expect(task.status?.knownName == nil)
    #expect(task.geometry == field(raw, "geometries")); #expect(task.cooperativeWork == field(raw, "cooperativeWork"))
    #expect(task.instruction == "")
    await wire.set(HTTPResponse(status: 200, body: try fixture("tasks")))
    let first = try await client.listTasks(ChallengeID(42), pageSize: 2)
    #expect(first.items[0].status?.knownName == "Fixed"); #expect(first.items[1].status == nil)
    _ = try await client.listTasks(ChallengeID(42), pageSize: 2, after: first.next)
    #expect(query(await wire.last(), "page") == "1")
}

@Test func spatialEnvelopeFiltersAndChallengeIsolation() async throws {
    let wire = try Fake("task_summaries"); let client = MapRouletteClient(transport: wire)
    var filter = try TaskFilter(challengeIDs: [ChallengeID(42)], bounds: Bounds(west: 4, south: 52, east: 5, north: 53), statuses: nil)
    let first = try await client.findTasksInBounds(filter: filter, pageSize: 2)
    #expect(first.total == 12); #expect(first.items[1].status == nil); #expect(first.items[1].point == nil)
    let point = try #require(first.items[0].point)
    #expect(point == .object(["lat": .number(52.3), "lng": .number(4.9)]))
    #expect(field(point, "lat") == .number(52.3))
    #expect(field(point, "lng") == .number(4.9))
    #expect(field(point, "coordinates") == nil)
    _ = try await client.findTasksInBounds(filter: filter, pageSize: 2, after: first.next)
    let request = await wire.last()
    #expect(query(request, "cid") == "42"); #expect(query(request, "cId") == nil)
    #expect(query(request, "tStatus") == "-1"); #expect(query(request, "page") == "1")
    filter.challengeIDs = [try ChallengeID(99)]
    try await errorKind(.protocolFailure) { _ = try await client.findTasksInBounds(filter: filter, pageSize: 2) }
}

@Test func spatialSearchAcrossAllChallengesOmitsChallengeFilter() async throws {
    let wire = try Fake("task_summaries_multiple_challenges")
    let client = MapRouletteClient(transport: wire)
    let filter = try TaskFilter(bounds: Bounds(west: 4, south: 52, east: 5, north: 53))
    let first = try await client.findTasksInBounds(filter: filter, pageSize: 2)
    #expect(first.items.map { $0.challengeID.value } == [42, 99])
    #expect(first.total == 2)
    _ = try await client.findTasksInBounds(filter: filter, pageSize: 2, after: first.next)
    let request = await wire.last()
    #expect(query(request, "cid") == nil)
    #expect(query(request, "tStatus") == "0,3,6")
    #expect(query(request, "ca") == "false")
    #expect(query(request, "cLocal") == "1")
    #expect(query(request, "page") == "1")
    var selected = filter
    selected.challengeIDs = [try ChallengeID(42)]
    try await errorKind(.validation) {
        _ = try await client.findTasksInBounds(filter: selected, pageSize: 2, after: first.next)
    }
    try await errorKind(.protocolFailure) {
        _ = try await client.findTasksInBounds(filter: selected, pageSize: 2)
    }
}

@Test func errorsAndCredentialRedaction() async throws {
    let wire = try Fake("identity")
    let client = MapRouletteClient(transport: wire, apiKey: { "fixture-secret-do-not-expose" })
    let identity = try await client.getCurrentUser()
    #expect(identity.id == 900); #expect(!identity.guest)
    #expect(!String(describing: identity).contains("secret"))
    #expect(await wire.last().headers["apiKey"] == "fixture-secret-do-not-expose")
    #expect(!String(describing: await wire.last()).contains("secret"))
    let cases: [(Int, ErrorKind)] = [(401,.authentication),(403,.permission),(404,.notFound),(409,.conflict),(429,.rateLimit),(503,.server),(302,.http)]
    for (status, kind) in cases {
        await wire.set(HTTPResponse(status: status, headers: ["retry-after": "17"], body: Data((status == 404 ? "" : "fixture-secret-do-not-expose").utf8)))
        do { _ = try await client.getTask(TaskID(101)); Issue.record("Expected HTTP failure") }
        catch let error as MapRouletteError {
            #expect(error.kind == kind); #expect(error.status == status); #expect(error.retryAfter == "17")
            #expect(!String(describing: error).contains("secret"))
        }
    }
    for invalid in [Data("not json".utf8), try fixture("malformed_challenge")] {
        await wire.set(HTTPResponse(status: 200, body: invalid))
        try await errorKind(.protocolFailure) { _ = try await client.getChallenge(ChallengeID(42)) }
    }
}

private actor UserCredential {
    var key: String?
    init(_ key: String?) { self.key = key }
    func set(_ key: String?) { self.key = key }
}

@Test func credentialsBelongToEachUserAndAnonymousRequestsStayAnonymous() async throws {
    let wire = try Fake("challenge_direct")
    let credential = UserCredential("synthetic-first-user-key")
    let first = MapRouletteClient(transport: wire, apiKey: { await credential.key })
    let second = MapRouletteClient(transport: wire, apiKey: { "synthetic-second-user-key" })
    let anonymous = MapRouletteClient(transport: wire)
    _ = try await first.getChallenge(ChallengeID(42))
    _ = try await second.getChallenge(ChallengeID(42))
    _ = try await anonymous.getChallenge(ChallengeID(42))
    await credential.set("synthetic-rotated-first-user-key")
    _ = try await first.getChallenge(ChallengeID(42))
    await credential.set(nil)
    _ = try await first.getChallenge(ChallengeID(42))
    _ = try await second.getChallenge(ChallengeID(42))
    let requests = await wire.requests
    #expect(requests.map { $0.headers["apiKey"] } == ["synthetic-first-user-key", "synthetic-second-user-key", nil,
        "synthetic-rotated-first-user-key", nil, "synthetic-second-user-key"])
    #expect(!requests[2].headers.keys.contains("apiKey"))
    #expect(!requests[4].headers.keys.contains("apiKey"))
}

private actor SuspendedTransport: Transport {
    var started = false
    var stopped = false
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        started = true
        defer { stopped = true }
        try await Task.sleep(for: .seconds(30))
        throw MapRouletteError(.network)
    }
}
@Test func cancellationIsNotNetworkFailure() async throws {
    let wire = SuspendedTransport(); let client = MapRouletteClient(transport: wire)
    let operation = Task { try await client.getTask(TaskID(101)) }
    while !(await wire.started) { await Task.yield() }
    operation.cancel()
    do { _ = try await operation.value; Issue.record("Expected cancellation") }
    catch is CancellationError { }
    #expect(await wire.stopped)
}

@Test func malformedIdentitiesAndCredentialValidation() async throws {
    let wire = try Fake("challenge_direct"); let client = MapRouletteClient(transport: wire)
    try await errorKind(.protocolFailure) { _ = try await client.getChallenge(ChallengeID(99)) }
    await wire.set(HTTPResponse(status: 200, body: try fixture("task")))
    try await errorKind(.protocolFailure) { _ = try await client.getTask(TaskID(99)) }
    await wire.set(HTTPResponse(status: 200, body: try fixture("tasks")))
    try await errorKind(.protocolFailure) { _ = try await client.listTasks(ChallengeID(99), pageSize: 2) }
    await wire.set(HTTPResponse(status: 200, body: Data("{\"id\":900,\"guest\":\"false\"}".utf8)))
    try await errorKind(.protocolFailure) { _ = try await client.getCurrentUser() }
    let invalidCredential = MapRouletteClient(transport: wire, apiKey: { "\nsecret" })
    try await errorKind(.validation) { _ = try await invalidCredential.getTask(TaskID(101)) }
}

@Test func boundedMarkersUseReadOnlyPutWithoutPagination() async throws {
    let wire = try Fake("markers"); let client = MapRouletteClient(transport: wire)
    let filter = try TaskFilter(bounds: Bounds(west: 4, south: 52, east: 5, north: 53))
    let markers = try await client.findTaskMarkers(filter: filter, limit: 2)
    #expect(markers.map { $0.challengeID.value } == [42, 99])
    let request = await wire.last()
    #expect(request.method == .put); #expect(request.body == Data("{}".utf8))
    #expect(request.headers["Content-Type"] == "application/json")
    #expect(request.url.path.contains("/markers/box/"))
    for absent in ["cid", "sort", "page", "includeTotal"] { #expect(query(request, absent) == nil) }
    for enabled in ["ce", "pe", "excludeLocked"] { #expect(query(request, enabled) == "true") }
    #expect(query(request, "cLocal") == "1"); #expect(query(request, "tStatus") == "0,3,6")
    var selected = filter
    selected.challengeIDs = [try ChallengeID(42), try ChallengeID(99)]
    selected.statuses = nil
    _ = try await client.findTaskMarkers(filter: selected)
    let selectedRequest = await wire.last()
    #expect(query(selectedRequest, "cid") == "42,99"); #expect(query(selectedRequest, "tStatus") == "-1")
    #expect(query(selectedRequest, "ce") == nil); #expect(query(selectedRequest, "pe") == nil)
    for invalid in [0, 1001] {
        try await errorKind(.validation) { _ = try await client.findTaskMarkers(filter: filter, limit: invalid) }
    }
    try await errorKind(.protocolFailure) { _ = try await client.findTaskMarkers(filter: filter, limit: 1) }
    selected.challengeIDs = [try ChallengeID(42)]
    try await errorKind(.protocolFailure) { _ = try await client.findTaskMarkers(filter: selected) }
}

private actor CredentialProbe {
    var value: String?
    var calls = 0
    init(_ value: String?) { self.value = value }
    func read() -> String? { calls += 1; return value }
    func set(_ value: String?) { self.value = value }
}

@Test func bearerIdentityUsesConfiguredOriginAndSingleCredentialSnapshot() async throws {
    let wire = try Fake("identity_mobile")
    let token = CredentialProbe("synthetic-first-access-token")
    let key = CredentialProbe(nil)
    let client = MapRouletteClient(
        environment: try MapRouletteEnvironment(serviceURL: URL(string: "https://example.org:9443/nested/api/v2/")!),
        transport: wire, apiKey: { await key.read() }, accessToken: { await token.read() })
    let identity = try await client.getCurrentUser()
    #expect(identity.id == 900); #expect(!identity.guest)
    let request = await wire.last()
    #expect(request.url.absoluteString == "https://example.org:9443/oauth/mobile/me")
    #expect(request.headers["Authorization"] == "Bearer synthetic-first-access-token")
    #expect(request.headers["apiKey"] == nil)
    #expect(await token.calls == 1); #expect(await key.calls == 1)
    #expect(!String(describing: request).contains("synthetic"))
    #expect(!String(describing: identity).contains("token"))

    await wire.set(HTTPResponse(status: 200, body: try fixture("challenge_direct")))
    await token.set("synthetic-rotated-access-token")
    _ = try await client.getChallenge(ChallengeID(42))
    #expect(await wire.last().url.path == "/nested/api/v2/challenge/42")
    #expect(await wire.last().headers["Authorization"] == "Bearer synthetic-rotated-access-token")
    #expect(await token.calls == 2); #expect(await key.calls == 2)

    await token.set(nil)
    await wire.set(HTTPResponse(status: 200, body: try fixture("identity")))
    _ = try await client.getCurrentUser()
    #expect(await wire.last().url.path == "/nested/api/v2/user/whoami")
    #expect(await wire.last().headers["Authorization"] == nil)
    #expect(await wire.last().headers["apiKey"] == nil)
    #expect(await token.calls == 3); #expect(await key.calls == 3)
}

@Test func unlabeledTrailingClosureIsTheAPIKey() async throws {
    let wire = try Fake("identity")
    let client = MapRouletteClient(transport: wire) { "legacy-trailing-key" }
    _ = try await client.getCurrentUser()
    #expect(await wire.last().url.path == "/api/v2/user/whoami")
    #expect(await wire.last().headers["apiKey"] == "legacy-trailing-key")
    #expect(await wire.last().headers["Authorization"] == nil)
}

@Test func bearerConflictsInvalidTokensAndErrorsNeverFallback() async throws {
    let wire = try Fake("identity_mobile")
    let key = CredentialProbe("legacy-secret")
    let token = CredentialProbe("synthetic-access-token")
    let conflict = MapRouletteClient(transport: wire,
        apiKey: { await key.read() }, accessToken: { await token.read() })
    try await errorKind(.validation) { _ = try await conflict.getCurrentUser() }
    #expect(await wire.requests.isEmpty)
    #expect(await token.calls == 1); #expect(await key.calls == 1)
    for invalid in ["", " ", "token with spaces", "token\n", "token\r", "token:wrong"] {
        let client = MapRouletteClient(transport: wire, accessToken: { invalid })
        try await errorKind(.validation) { _ = try await client.getCurrentUser() }
    }
    #expect(await wire.requests.isEmpty)
    let client = MapRouletteClient(transport: wire, accessToken: { "synthetic-access-token" })
    for (status, kind) in [(401, ErrorKind.authentication), (403, .permission), (404, .notFound), (302, .http)] {
        await wire.set(HTTPResponse(status: status, body: Data("secret response".utf8)))
        let count = await wire.requests.count
        try await errorKind(kind) { _ = try await client.getCurrentUser() }
        #expect(await wire.requests.count == count + 1)
        #expect(await wire.last().url.path == "/oauth/mobile/me")
        #expect(await wire.last().headers["apiKey"] == nil)
    }
}

@Test func malformedMobileIdentitiesFailAndProviderErrorsRemainDistinct() async throws {
    let wire = try Fake("identity_mobile")
    let client = MapRouletteClient(transport: wire, accessToken: { "synthetic-access-token" })
    let original = try JSONSerialization.jsonObject(with: fixture("identity_mobile")) as! [String:Any]
    for (key, value) in [("id", 0 as Any), ("osmId", "12345" as Any),
                         ("displayName", NSNull() as Any), ("scope", "tasks:write" as Any)] {
        var changed = original; changed[key] = value
        await wire.set(HTTPResponse(status: 200, body: try JSONSerialization.data(withJSONObject: changed)))
        try await errorKind(.protocolFailure) { _ = try await client.getCurrentUser() }
    }
    enum ProviderFailure: Error { case unavailable }
    let failing = MapRouletteClient(transport: wire, accessToken: { throw ProviderFailure.unavailable })
    let count = await wire.requests.count
    do { _ = try await failing.getCurrentUser(); Issue.record("Expected provider failure") }
    catch ProviderFailure.unavailable { }
    #expect(await wire.requests.count == count)
}

@Test func bearerProvidersRemainIsolatedAcrossClients() async throws {
    let wire = try Fake("challenge_direct")
    let credential = CredentialProbe("first-user-token")
    let first = MapRouletteClient(transport: wire, accessToken: { await credential.read() })
    let second = MapRouletteClient(transport: wire, accessToken: { "second-user-token" })
    _ = try await first.getChallenge(ChallengeID(42))
    _ = try await second.getChallenge(ChallengeID(42))
    await credential.set(nil)
    _ = try await first.getChallenge(ChallengeID(42))
    _ = try await second.getChallenge(ChallengeID(42))
    let requests = await wire.requests
    #expect(requests.map { $0.headers["Authorization"] } ==
        ["Bearer first-user-token", "Bearer second-user-token", nil, "Bearer second-user-token"])
    #expect(requests.allSatisfy { $0.headers["apiKey"] == nil })
}
