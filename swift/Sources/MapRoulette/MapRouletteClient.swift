import Foundation

/// Reads, skip and choice submission, plus the low-level task-lifecycle writes (start, refresh,
/// release and status 1/2/5/6) behind `@_spi(LowLevelTaskLifecycle)`. No implicit pagination,
/// retries or persistence; writes are never resent, except the identical choice submission that
/// `submitChoice` documents. Writes go only to environments whose `allowsWrites` is true.
public final class MapRouletteClient: Sendable {
  /// The deployment this client talks to.
  public let environment: MapRouletteEnvironment
  /// Whether choice "gone" outcomes may delete the OSM element (default false). This is a cap:
  /// when false, no delete is ever sent and "gone" resolves to Not an issue. When true, use
  /// `choiceOutcomes(_:)` and, per task, `ChoiceOutcome.withoutDeletion()` to submit "gone"
  /// without deleting.
  public let allowElementDeletion: Bool
  private let base: URL
  private let transport: any Transport
  private let apiKey: @Sendable () async throws -> String?
  private let accessToken: @Sendable () async throws -> String?
  private let owner = UUID()

  /// Credential providers run before every request, so a rotated or signed-out credential takes
  /// effect immediately. Supply at most one of them: a request fails with `.validation` when both
  /// return a value. Pass them by label.
  public init(
    environment: MapRouletteEnvironment = .production,
    transport: any Transport = URLSessionTransport(),
    allowElementDeletion: Bool = false,
    apiKey: @escaping @Sendable () async throws -> String? = { nil },
    accessToken: @escaping @Sendable () async throws -> String? = { nil }
  ) {
    self.environment = environment
    base = environment.serviceURL
    self.transport = transport
    self.apiKey = apiKey
    self.accessToken = accessToken
    self.allowElementDeletion = allowElementDeletion
  }

  /// The task's kind, decoded with this client's `allowElementDeletion`.
  public func work(_ task: MapRouletteTask) -> TaskWork {
    task.work(allowElementDeletion: allowElementDeletion)
  }

  /// The task's declared outcomes plus the built-in Too hard, decoded with this client's
  /// `allowElementDeletion`; empty when the task is not a valid choice task. Every outcome, and
  /// its `withoutDeletion()` form, can be passed to `submitChoice`.
  public func choiceOutcomes(_ task: MapRouletteTask) -> [ChoiceOutcome] {
    task.choiceOutcomes(allowElementDeletion: allowElementDeletion)
  }

  /// Searches challenges, ordered by id. Anonymous reads work; `pageSize` must be 1...100.
  /// Pass `page.next` as `after` for the next page, with the same filter and page size.
  public func searchChallenges(
    filter: ChallengeFilter = ChallengeFilter(), pageSize: Int = 50, after: PageCursor? = nil
  ) async throws -> Page<Challenge> {
    guard
      filter.tags.allSatisfy({
        !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !$0.contains(",")
      })
    else {
      throw MapRouletteError(.validation, reason: "challenge tags must be non-blank and contain no comma")
    }
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
  /// Reads one challenge. `.notFound` when it does not exist or is not visible to the caller.
  public func getChallenge(_ id: ChallengeID) async throws -> Challenge {
    let result = try challenge(await request("challenge/\(id.value)"))
    guard result.id == id else { throw MapRouletteError(.protocolFailure) }
    return result
  }
  /// Reads a challenge's labels (not OSM tags).
  public func getChallengeTags(_ id: ChallengeID) async throws -> [ChallengeTag] {
    try await request("challenge/\(id.value)/tags").array().map { value in
      let o = try value.object()
      return try ChallengeTag(id: o.required("id").integer(), name: o.required("name").string())
    }
  }
  /// Lists a challenge's tasks in server order. `pageSize` must be 1...100; continue with `next`.
  public func listTasks(_ id: ChallengeID, pageSize: Int = 50, after: PageCursor? = nil)
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
  /// Reads one task, including its lock holder and completion fields.
  public func getTask(_ id: TaskID) async throws -> MapRouletteTask {
    let result = try task(await request("task/\(id.value)"))
    guard result.id == id else { throw MapRouletteError(.protocolFailure) }
    return result
  }
  /// The caller's identity. Needs a credential: an API key reads `user/whoami`, a bearer token
  /// reads `oauth/mobile/me` (fork backend) and reports the grant's scopes. `.authentication`
  /// when the credential is missing or rejected.
  public func getCurrentUser() async throws -> UserIdentity {
    let credential = try await credentials()
    let mobile = credential.bearer != nil
    let o = try await request(
      mobile ? "oauth/mobile/me" : "user/whoami",
      credential: credential, originRelative: mobile
    ).object()
    if mobile {
      let id = try o.required("id").integer()
      let osmID = try o.required("osmId").integer()
      _ = try o.required("displayName").string()
      let scopes = try o.required("scope").string().split(
        separator: " ", omittingEmptySubsequences: false
      ).map(String.init)
      guard id > 0, osmID > 0, scopes.contains("tasks:read"), !scopes.contains("") else {
        throw MapRouletteError(.protocolFailure)
      }
      return UserIdentity(id: id, guest: false, scopes: Set(scopes))
    }
    return try UserIdentity(id: o.required("id").integer(), guest: o.required("guest").boolean())
  }
  /// Tasks whose location is inside `filter.bounds`, ordered by id, with `total`. `pageSize`
  /// must be 1...100; continue with `next`.
  public func findTasksInBounds(filter: TaskFilter, pageSize: Int = 50, after: PageCursor? = nil)
    async throws -> Page<TaskSummary>
  {
    guard
      filter.statuses.map({ !$0.isEmpty && $0.allSatisfy { $0 >= 0 && $0 <= Int32.max } }) ?? true
    else { throw MapRouletteError(.validation, reason: statusesReason) }
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
    if filter.choiceOnly { params.merge(choiceOnlyParams) { $1 } }
    let result = try await page(
      path: "tasks/box/\(b.west)/\(b.south)/\(b.east)/\(b.north)", params: params, size: pageSize,
      after: after, offset: false, envelope: true, parse: summary)
    guard
      filter.challengeIDs.isEmpty
        || result.items.allSatisfy({ filter.challengeIDs.contains($0.challengeID) })
    else {
      throw MapRouletteError(.protocolFailure)
    }
    return result
  }
  /// Bounded, unordered map markers. A full result may be truncated; no pagination or total.
  /// This read-only backend operation uses PUT and includes tasks locked by other users.
  /// All-challenge discovery filters to enabled challenges/projects; selected IDs bypass that filter.
  public func findTaskMarkers(filter: TaskFilter, limit: Int = 100) async throws -> [TaskSummary] {
    guard (1...1000).contains(limit) else {
      throw MapRouletteError(.validation, reason: "limit must be 1...1000")
    }
    guard
      filter.statuses.map({ !$0.isEmpty && $0.allSatisfy { $0 >= 0 && $0 <= Int32.max } }) ?? true
    else { throw MapRouletteError(.validation, reason: statusesReason) }
    var params = [
      "cLocal": "1", "ca": String(filter.includeArchived),
      "tStatus": filter.statuses?.map(String.init).joined(separator: ",") ?? "-1",
      "excludeLocked": "true", "limit": String(limit),
    ]
    if !filter.challengeIDs.isEmpty {
      params["cid"] = filter.challengeIDs.map { String($0.value) }.joined(separator: ",")
    }
    if filter.choiceOnly { params.merge(choiceOnlyParams) { $1 } }
    let b = filter.bounds
    if filter.challengeIDs.isEmpty {
      params["ce"] = "true"
      params["pe"] = "true"
    }
    let response = try await request(
      "markers/box/\(b.west)/\(b.south)/\(b.east)/\(b.north)",
      params: params, method: .put, body: Data("{}".utf8))
    let rows = try response.array()
    guard rows.count <= limit else { throw MapRouletteError(.protocolFailure) }
    let markers = try rows.map(summary)
    guard
      filter.challengeIDs.isEmpty
        || markers.allSatisfy({ filter.challengeIDs.contains($0.challengeID) })
    else { throw MapRouletteError(.protocolFailure) }
    return markers
  }

  // Low-level lifecycle writes. Mobile apps complete tasks with `submitChoice` and `skipTask`;
  // these exist for tooling and future flows, behind `@_spi(LowLevelTaskLifecycle) import`.

  /// Locks the task for the caller (`GET task/{id}/start`). A repeat by the owner refreshes it.
  /// Failures carry `.lockedByOtherUser` (403) or `.alreadyHoldingTask` (409).
  @_spi(LowLevelTaskLifecycle)
  public func startTask(_ id: TaskID) async throws -> TaskLock {
    try await lock(id, action: "start", .start)
  }
  /// Refreshes a held lock. Only for long edit flows; `commitResolution` does not need it.
  @_spi(LowLevelTaskLifecycle)
  public func refreshTaskLock(_ id: TaskID) async throws -> TaskLock {
    try await lock(id, action: "refreshLock", .refresh)
  }
  /// Releases the caller's lock. Succeeds even when the caller holds no lock; not proof of ownership.
  @_spi(LowLevelTaskLifecycle)
  public func releaseTask(_ id: TaskID) async throws {
    _ = try await write(.release, "task/\(id.value)/release", .get)
  }
  /// Supported mobile path, with `submitChoice`. Offer it only when `task.canSkip()`.
  /// Skips without changing status and releases the caller's lock if held. Not idempotent (the
  /// server counts every skip): after `.outcomeUnknown`, re-read instead of resending.
  public func skipTask(_ id: TaskID) async throws {
    _ = try await write(.skip, "task/\(id.value)/skip", .post)
  }
  /// Bare status write (`PUT task/{id}/{code}`, no query or body). The server releases the lock.
  /// Call it inside a fresh `startTask`, or use `commitResolution`. Never resend after
  /// `.outcomeUnknown` without `verifyResolution`.
  @_spi(LowLevelTaskLifecycle)
  public func resolveTask(_ id: TaskID, as resolution: TaskResolution) async throws {
    _ = try await write(.resolve, "task/\(id.value)/\(resolution.rawValue)", .put)
  }
  /// Late-locking commit: start, then the status write. If the status write fails, the lock is
  /// released once (best effort) before the error is rethrown. If start reports a stale lock of
  /// the caller's on another task (409), that task is re-read, released when still locked, and
  /// start is retried once. Cancellation propagates without cleanup; the server expires locks.
  @_spi(LowLevelTaskLifecycle)
  public func commitResolution(_ id: TaskID, as resolution: TaskResolution) async throws {
    try await startForCommit(id)
    do {
      try await resolveTask(id, as: resolution)
    } catch let error as MapRouletteError {
      await releaseQuietly(id)
      throw error
    }
  }

  private func startForCommit(_ id: TaskID) async throws {
    do {
      try await startReleasingUnknown(id)
      return
    } catch let error as MapRouletteError {
      guard case .alreadyHoldingTask(let held, _, _, _) = error.problem, held != id else {
        throw error
      }
      // Under late locking, a lock held elsewhere comes from an interrupted commit by this user.
      let stale: MapRouletteTask?
      do {
        stale = try await getTask(held)
      } catch let read as MapRouletteError {
        guard read.kind == .notFound else { throw error }
        stale = nil
      }
      if stale?.lockedBy != nil {
        do { try await releaseTask(held) } catch is MapRouletteError { throw error }
      }
    }
    try await startReleasingUnknown(id)
  }

  // A start with an unknown outcome may hold the lock: release it once before rethrowing.
  private func startReleasingUnknown(_ id: TaskID) async throws {
    do {
      _ = try await startTask(id)
    } catch let error as MapRouletteError {
      if error.problem == .outcomeUnknown { await releaseQuietly(id) }
      throw error
    }
  }

  private func releaseQuietly(_ id: TaskID) async {
    // Best effort: the server-side lock expiry is the backstop.
    try? await releaseTask(id)
  }

  /// Submits a choice task's answers or outcome with late locking: start, then
  /// `POST task/{id}/choice`. The server applies the OSM edit (answers, or an enabled delete), sets
  /// the status and releases the lock. The submission is checked against `task`'s payload before
  /// anything is sent (`.validation`); decode outcomes with this client's `allowElementDeletion`.
  ///
  /// Failures carry a `.choice` or other `WriteProblem`; the lock is then released (best effort),
  /// except after `.statusPending` or `.outcomeUnknown`. The identical submission is resent at most
  /// once, only where the server's idempotency makes it safe: after `.statusPending`, and after an
  /// unknown outcome of a non-editing outcome when a fresh read shows it did not land and the caller
  /// still holds the lock. An edit (answers or a delete) is never resent after an unknown outcome,
  /// because the first request may still be uploading: `.outcomeUnknown` is thrown with the lock
  /// kept; submit the same submission again later (the server resumes it without a second upload).
  /// When the read shows the submission landed, the result comes from the read. If the resend then fails
  /// without proving that nothing was applied (e.g. lock_required because the first attempt
  /// finished meanwhile), the first error (`.outcomeUnknown`, or `.statusPending` with its
  /// changeset) is thrown and the lock kept.
  public func submitChoice(_ task: MapRouletteTask, _ submission: ChoiceSubmission) async throws
    -> ChoiceResult
  {
    try requireWrites()
    let target = try task.validateChoice(submission, allowElementDeletion: allowElementDeletion)
    let id = task.id
    let body = Data(choiceBody(submission).utf8)
    let edits: Bool
    switch submission {
    case .answers: edits = true
    case .outcome(let outcome): edits = outcome.deletesElement
    }
    try await startForCommit(id)
    let first: MapRouletteError
    do {
      return try await postChoice(id, body)
    } catch let error as MapRouletteError {
      first = error
    }
    if case .choice(.statusPending) = first.problem {
    } else if first.problem == .outcomeUnknown {
      switch try await recover(task, target) {
      case .applied(let changeset):
        return ChoiceResult(status: TaskStatus(code: target.rawValue), changesetID: changeset)
      // An edit may still be uploading on the server; resending could race it.
      case .resend: if edits { throw first }
      case .unknown: throw first
      }
    } else {
      await releaseQuietly(id)
      throw first
    }
    // The first attempt may have landed (unknown) or did land in OSM (statusPending).
    do {
      return try await postChoice(id, body)
    } catch let second as MapRouletteError {
      if case .choice(.statusPending) = second.problem { throw second }
      if first.problem == .outcomeUnknown, provesNotApplied(second.problem) {
        await releaseQuietly(id)
        throw second
      }
      // E.g. lock_required because the first attempt finished meanwhile: keep the first error
      // (statusPending keeps its changeset ID) and keep the lock for a later identical resend.
      throw first
    }
  }

  /// Fresh server-side OSM eligibility check (`GET task/{id}/choice/check`, `tasks:read`). It
  /// changes nothing the caller owns; the server may record a system ineligible flag that hides
  /// the task from mobile discovery. A 502 osm_unavailable or 503 osm_edits_unavailable carries
  /// `.choice(.osmUnavailable)`.
  public func checkChoice(_ id: TaskID) async throws -> ChoiceEligibility {
    let response = try await send("task/\(id.value)/choice/check")
    guard response.status == 200 else {
      let unavailable = osmUnavailable(response.status, errorField(response, "error"))
      throw failure(response, problem: unavailable ? .choice(.osmUnavailable) : nil)
    }
    do {
      let o = try JSONDecoder().decode(JSONValue.self, from: response.body).object()
      let deleteAllowed = try o.optional("deleteAllowed")?.boolean() ?? false
      if try o.required("eligible").boolean() {
        return ChoiceEligibility(eligible: true, deleteAllowed: deleteAllowed, reason: nil)
      }
      return ChoiceEligibility(
        eligible: false, deleteAllowed: false,
        reason: ineligibleReason(try o.optional("reason")?.string()))
    } catch {
      throw MapRouletteError(.protocolFailure)
    }
  }

  private func postChoice(_ id: TaskID, _ body: Data) async throws -> ChoiceResult {
    let response = try await write(.choice, "task/\(id.value)/choice", .post, body: body)
    do {
      guard response.status == 200 else { throw MapRouletteError(.protocolFailure) }
      let o = try JSONDecoder().decode(JSONValue.self, from: response.body).object()
      let status = try o.required("status").integer()
      guard status >= 0, status <= Int32.max else { throw MapRouletteError(.protocolFailure) }
      return ChoiceResult(
        status: TaskStatus(code: Int(status)),
        changesetID: (try o.optional("changesetId")?.integer()).flatMap { $0 > 0 ? $0 : nil })
    } catch {
      // The submission may have been applied even though the response could not be read.
      throw MapRouletteError(.protocolFailure, status: response.status, problem: .outcomeUnknown)
    }
  }

  private enum Recovery { case applied(Int64?), resend, unknown }

  // Fresh identity and task reads after an interrupted submission. A lock still held by the caller
  // means it did not land (the server releases on success). Applied needs the target status, no
  // lock, not completed by someone else, and a status that differs from the one before submitting
  // (Too hard can be submitted on a Too hard task). Anything else, including a failed read, stays
  // unknown. Cancellation propagates.
  private func recover(_ task: MapRouletteTask, _ target: TaskResolution) async throws -> Recovery {
    do {
      let me = try await getCurrentUser().id
      let fresh = try await getTask(task.id)
      if fresh.lockedBy == me { return .resend }
      if fresh.status?.code == target.rawValue, fresh.lockedBy == nil,
        task.status?.code != target.rawValue, fresh.completedBy == nil || fresh.completedBy == me
      {
        return .applied(fresh.changesetID)
      }
      return .unknown
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      return .unknown
    }
  }

  private enum Write { case start, refresh, release, skip, resolve, choice }

  private func lock(_ id: TaskID, action: String, _ operation: Write) async throws -> TaskLock {
    let response = try await write(operation, "task/\(id.value)/\(action)", .get)
    do {
      let value = try JSONDecoder().decode(JSONValue.self, from: response.body)
      let o = try value.object()
      let result = try task(value)
      guard result.id == id, response.status == 200 else { throw MapRouletteError(.protocolFailure) }
      let primary = try TaskID(o.required("lockPrimaryTaskId").integer())
      let bundled = try o.optional("lockBundledTasks")?.array().map { try TaskID($0.integer()) } ?? []
      return TaskLock(task: result, primaryTaskID: primary, bundledTaskIDs: bundled)
    } catch {
      // The lock may be held even though the response could not be read.
      throw MapRouletteError(.protocolFailure, status: response.status, problem: .outcomeUnknown)
    }
  }

  private func write(
    _ operation: Write, _ path: String, _ method: HTTPMethod, body: Data? = nil
  ) async throws -> HTTPResponse {
    try requireWrites()
    let response: HTTPResponse
    do {
      response = try await send(path, method: method, body: body)
    } catch let error as MapRouletteError where error.kind == .network {
      throw MapRouletteError(.network, problem: .outcomeUnknown)
    }
    if (200...299).contains(response.status) { return response }
    throw failure(response, problem: writeProblem(operation, response))
  }

  private func requireWrites() throws {
    guard environment.allowsWrites else {
      throw MapRouletteError(
        .validation,
        reason: "task writes are disabled for \(environment); before 1.0 this SDK writes only to staging (mr-api.osm.lol) or loopback")
    }
  }

  // Bodies are inspected only for the lock/scope details below and never retained.
  private func writeProblem(_ operation: Write, _ response: HTTPResponse) -> WriteProblem? {
    let body = try? JSONDecoder().decode(JSONValue.self, from: response.body).object()
    func field(_ name: String) -> String? {
      if case .string(let value) = body?[name] { return value }
      return nil
    }
    if operation == .choice { return choiceProblem(response.status, body, field) }
    switch response.status {
    case 400:
      return operation == .resolve && field("error") != "invalid_request" ? .invalidTransition : nil
    case 403:
      if field("error") == "insufficient_scope" { return .insufficientScope }
      if operation == .refresh { return .lockLost }
      if operation == .start || operation == .resolve,
        let message = field("message"), message.lowercased().contains("locked")
      {
        return .lockedByOtherUser(message: message)
      }
      return nil
    case 409:
      guard operation == .start, let body,
        let held = try? TaskID(body.required("lockedTaskId").integer())
      else { return nil }
      do {
        return try .alreadyHoldingTask(
          lockedTaskID: held,
          challengeID: body.optional("parentId").map { try ChallengeID($0.integer()) },
          challengeName: body.optional("parentName")?.string(),
          startedAt: body.optional("startedAt")?.string())
      } catch { return nil }
    case 500...599:
      return .outcomeUnknown
    default:
      return nil
    }
  }

  private func errorField(_ response: HTTPResponse, _ name: String) -> String? {
    guard let body = try? JSONDecoder().decode(JSONValue.self, from: response.body).object(),
      case .string(let value) = body[name]
    else { return nil }
    return value
  }

  private func choiceProblem(
    _ status: Int, _ body: [String: JSONValue]?, _ field: (String) -> String?
  ) -> WriteProblem? {
    let error = field("error")
    switch status {
    case 401: return error == "osm_reauth_required" ? .choice(.osmReauthRequired) : nil
    case 403:
      guard error == "insufficient_scope" else { return nil }
      return field("scope") == "osm:tagfix" ? .choice(.osmScopeRequired) : .insufficientScope
    case 409:
      switch error {
      case "lock_required": return .lockLost
      case "invalid_transition": return .invalidTransition
      case "submission_pending": return .choice(.submissionPending)
      case "element_in_use": return .choice(.elementInUse)
      // The server's diagnostic `detail` is deliberately not exposed.
      case "task_ineligible": return .choice(.taskIneligible(reason: ineligibleReason(field("reason"))))
      default: return nil
      }
    case 422:
      switch error {
      case "invalid_submission": return .choice(.invalidSubmission(detail: field("detail")))
      case "unsupported_task": return .choice(.unsupportedTask)
      default: return nil
      }
    case 500:
      guard error == "status_pending" else { return .outcomeUnknown }
      return .choice(.statusPending(changesetID: try? body?.optional("changesetId")?.integer()))
    // 503 osm_edits_unavailable is refused before any lock check or upload.
    case 502, 503: return osmUnavailable(status, error) ? .choice(.osmUnavailable) : .outcomeUnknown
    case 500...599: return .outcomeUnknown
    default: return nil
    }
  }

  private func page<T: Sendable>(
    path: String, params: [String: String], size: Int, after: PageCursor?, offset: Bool,
    envelope: Bool = false, parse: (JSONValue) throws -> T
  ) async throws -> Page<T> {
    guard (1...100).contains(size) else {
      throw MapRouletteError(.validation, reason: "pageSize must be 1...100")
    }
    let key =
      path + "?"
      + params.sorted { $0.key < $1.key }.map {
        "\($0.key.count):\($0.key)\($0.value.count):\($0.value)"
      }.joined() + "&size=\(size)"
    guard after == nil || (after?.owner == owner && after?.key == key) else {
      throw MapRouletteError(
        .validation,
        reason: "page cursor belongs to another client, operation, filter or page size")
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
      ? PageCursor(owner: owner, key: key, position: nextPosition.partialValue) : nil
    return try Page(items: rows.map(parse), next: next, total: total)
  }
  private struct Credential {
    let key: String?
    let bearer: String?
  }

  private func credentials() async throws -> Credential {
    try Task.checkCancellation()
    let key = try await apiKey()
    let bearer = try await accessToken()
    guard key == nil || bearer == nil else {
      throw MapRouletteError(.validation, reason: "supply an API key or an access token, not both")
    }
    for value in [key, bearer].compactMap({ $0 }) {
      guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
        !value.contains("\r"), !value.contains("\n")
      else { throw MapRouletteError(.validation, reason: "credential is blank or contains a line break") }
    }
    if let bearer {
      guard bearer.range(of: "^[A-Za-z0-9._~+/-]+=*$", options: .regularExpression) != nil else {
        throw MapRouletteError(.validation, reason: "access token is not a valid bearer token")
      }
    }
    try Task.checkCancellation()
    return Credential(key: key, bearer: bearer)
  }

  private func request(
    _ path: String, params: [String: String] = [:], method: HTTPMethod = .get,
    body: Data? = nil, credential suppliedCredential: Credential? = nil,
    originRelative: Bool = false
  ) async throws -> JSONValue {
    let response = try await send(
      path, params: params, method: method, body: body, credential: suppliedCredential,
      originRelative: originRelative)
    guard response.status == 200 else { throw failure(response) }
    do { return try JSONDecoder().decode(JSONValue.self, from: response.body) } catch {
      throw MapRouletteError(.protocolFailure)
    }
  }

  private func failure(_ response: HTTPResponse, problem: WriteProblem? = nil) -> MapRouletteError {
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
    return MapRouletteError(
      kind, status: response.status,
      retryAfter: response.headers.first { $0.key.lowercased() == "retry-after" }?.value,
      problem: problem)
  }

  private func send(
    _ path: String, params: [String: String] = [:], method: HTTPMethod = .get,
    body: Data? = nil, credential suppliedCredential: Credential? = nil,
    originRelative: Bool = false
  ) async throws -> HTTPResponse {
    try Task.checkCancellation()
    let credential: Credential
    if let supplied = suppliedCredential {
      credential = supplied
    } else {
      credential = try await credentials()
    }
    guard
      var url = URLComponents(
        url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)
    else { throw MapRouletteError(.validation, reason: "request URL could not be built") }
    // Replace only the path: scheme, host and explicit port remain the configured service origin.
    if originRelative { url.path = "/" + path }
    let items = params.filter { !$0.value.isEmpty }.sorted { $0.key < $1.key }.map {
      URLQueryItem(name: $0.key, value: $0.value)
    }
    if !items.isEmpty { url.queryItems = items }
    guard let target = url.url else {
      throw MapRouletteError(.validation, reason: "request URL could not be built")
    }
    var headers = ["Accept": "application/json", "User-Agent": "MapRoulette-Mobile-SDK/\(MapRouletteSDK.version)"]
    if body != nil { headers["Content-Type"] = "application/json" }
    if let key = credential.key { headers["apiKey"] = key }
    if let bearer = credential.bearer { headers["Authorization"] = "Bearer " + bearer }
    let response: HTTPResponse
    do {
      response = try await transport.execute(
        HTTPRequest(url: target, headers: headers, method: method, body: body))
      try Task.checkCancellation()
    } catch is CancellationError { throw CancellationError() } catch let error as URLError
      where error.code == .cancelled
    { throw CancellationError() } catch let error as MapRouletteError { throw error } catch {
      throw MapRouletteError(.network)
    }
    return response
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
extension Optional {
  fileprivate func unwrap() throws -> Wrapped {
    guard let self else { throw MapRouletteError(.protocolFailure) }
    return self
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
    requiresLocal: o.optional("requiresLocal")?.boolean(),
    cooperativeType: o.optional("cooperativeType").map { try Int(exactly: $0.integer()).unwrap() })
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
    location: o.optionalObject("location"), cooperativeWork: o.optionalObject("cooperativeWork"),
    lockedBy: o.optional("lockedBy")?.integer(), completedBy: o.optional("completedBy")?.integer(),
    // Serialized as an ISO string; tolerate an epoch number without failing the whole read.
    mappedOn: o.optional("mappedOn").flatMap { value -> String? in
      switch value {
      case .string(let v): return v
      case .integer(let v): return String(v)
      default: return nil
      }
    },
    reviewStatus: o.optional("reviewStatus").map { try Int(exactly: $0.integer()).unwrap() },
    bundleID: o.optional("bundleId")?.integer(),
    // The backend may store -1 for "no changeset".
    changesetID: (try o.optional("changesetId")?.integer()).flatMap { $0 > 0 ? $0 : nil })
}
// Errors after which the server has re-evaluated the submission with the caller's lock and
// applied nothing.
private func provesNotApplied(_ problem: WriteProblem?) -> Bool {
  switch problem {
  case .choice(.taskIneligible)?, .choice(.invalidSubmission)?, .choice(.elementInUse)?,
    .choice(.osmReauthRequired)?, .choice(.osmScopeRequired)?, .choice(.osmUnavailable)?,
    .choice(.unsupportedTask)?, .insufficientScope?:
    true
  default: false
  }
}
private let statusesReason = "statuses must be nil or a non-empty list of codes 0...Int32.max"
private let choiceOnlyParams = ["cct": "3", "excludeStale": "true"]
private func ineligibleReason(_ wire: String?) -> IneligibleReason {
  switch wire {
  case "element_gone": .elementGone
  case "match_failed": .matchFailed
  case "key_changed": .keyChanged
  default: .unknown
  }
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

// 502 osm_unavailable: OSM unreachable. 503 osm_edits_unavailable: stored OSM tokens unusable.
private func osmUnavailable(_ status: Int, _ error: String?) -> Bool {
  (status == 502 && error == "osm_unavailable") || (status == 503 && error == "osm_edits_unavailable")
}
