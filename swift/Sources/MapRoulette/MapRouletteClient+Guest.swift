import Foundation

// Guest calls (deferred sign-up). The client's access token is a guest token (scope `guest`).
// Errors carry the server's code in `reason`, e.g. "guest_claimed" (.conflict), "pending_limit"
// and "email_rate_limited" (.rateLimit), "mail_unavailable" (.server), "task_completed" (.conflict).
// Calls that change server state need an environment that allows writes, like `submitChoice`.
extension MapRouletteClient {
  /// Validates locally like `submitChoice`, then stores the answers for the guest
  /// (`POST task/{id}/choice/pending`). Never edits OSM. A "gone" outcome is stored without
  /// deletion. Submitting again for the same task replaces the guest's earlier answer, so a
  /// resend after a network failure is safe. Choice failures carry `.choice(...)` problems.
  public func submitPendingChoice(_ task: MapRouletteTask, _ submission: ChoiceSubmission) async throws
    -> PendingChoice
  {
    try requireWrites()
    _ = try task.validateChoice(submission, allowElementDeletion: allowElementDeletion)
    var stored = submission
    if case .outcome(let outcome) = submission, outcome.deletesElement {
      stored = .outcome(outcome.withoutDeletion())
    }
    let response = try await guestCall(
      "task/\(task.id.value)/choice/pending", .post, body: Data(choiceBody(stored).utf8), write: .pending)
    return try parse(response) { o in
      let choice = try pendingChoice(o)
      guard choice.taskID == task.id else { throw MapRouletteError(.protocolFailure) }
      return choice
    }
  }

  /// Withdraws the guest's pending answer and releases its hold (`DELETE task/{id}/choice/pending`).
  /// `.notFound` when there is none.
  public func withdrawPendingChoice(_ id: TaskID) async throws {
    try requireWrites()
    _ = try await guestCall("task/\(id.value)/choice/pending", .delete, write: .pending)
  }

  /// The guest's answers, newest first as the server orders them. `pageSize` must be 1...100.
  public func listPendingChoices(pageSize: Int = 50, after: PageCursor? = nil) async throws
    -> Page<PendingChoice>
  {
    guard (1...100).contains(pageSize) else {
      throw MapRouletteError(.validation, reason: "pageSize must be 1...100")
    }
    let key = "mobile-guest/pending?size=\(pageSize)"
    guard after == nil || (after?.owner == owner && after?.key == key && after?.token != nil) else {
      throw MapRouletteError(
        .validation, reason: "page cursor belongs to another client, operation or page size")
    }
    var params = ["limit": String(pageSize)]
    if let token = after?.token { params["after"] = token }
    let response = try await guestCall("mobile-guest/pending", .get, params: params)
    return try parse(response) { o in
      let rows = try o.required("items").array()
      guard rows.count <= pageSize else { throw MapRouletteError(.protocolFailure) }
      let next = try o.optional("next")?.string()
      return try Page(
        items: rows.map { try pendingChoice($0.object()) },
        next: next.map { PageCursor(owner: owner, key: key, position: 0, token: $0) }, total: nil)
    }
  }

  /// The guest's status (`GET mobile-guest/me`).
  public func getGuestStatus() async throws -> GuestStatus {
    try parse(try await guestCall("mobile-guest/me", .get), guestStatus)
  }

  /// Stores the email and sends the claim link (`PUT mobile-guest/email`); call again to correct
  /// it. The address is not checked beyond one "@" and at most 254 characters.
  public func setGuestEmail(_ email: String) async throws -> GuestStatus {
    try requireWrites()
    let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.count <= 254, trimmed.filter({ $0 == "@" }).count == 1, !trimmed.hasPrefix("@"),
      !trimmed.hasSuffix("@")
    else { throw MapRouletteError(.validation, reason: "email must contain one @ and be at most 254 characters") }
    let body = try JSONSerialization.data(withJSONObject: ["email": trimmed])
    return try parse(try await guestCall("mobile-guest/email", .put, body: body, write: .pending), guestStatus)
  }

  /// Deletes the guest's email, claim links and pending answers (`DELETE mobile-guest`). Published
  /// OSM edits stay. The guest credentials stop working; forget them afterwards.
  public func deleteGuest() async throws {
    try requireWrites()
    _ = try await guestCall("mobile-guest", .delete, write: .pending)
  }

  private func guestCall(
    _ path: String, _ method: HTTPMethod, params: [String: String] = [:], body: Data? = nil,
    write operation: Write? = nil
  ) async throws -> HTTPResponse {
    let response = try await send(path, params: params, method: method, body: body)
    if (200...299).contains(response.status) { return response }
    let base = failure(response, problem: operation.flatMap { writeProblem($0, response) })
    throw MapRouletteError(
      base.kind, status: base.status, retryAfter: base.retryAfter, problem: base.problem,
      reason: errorField(response, "error").flatMap(guestErrorCode))
  }

  private func parse<T>(_ response: HTTPResponse, _ read: ([String: JSONValue]) throws -> T) throws -> T {
    do {
      return try read(JSONDecoder().decode(JSONValue.self, from: response.body).object())
    } catch {
      throw MapRouletteError(.protocolFailure)
    }
  }
}

// Only plain error codes are passed on, never other server text.
private func guestErrorCode(_ code: String) -> String? {
  code.range(of: "^[a-z_]{1,40}$", options: .regularExpression) != nil ? code : nil
}

private func guestStatus(_ o: [String: JSONValue]) throws -> GuestStatus {
  let pending = try o.required("pending").integer()
  let published = try o.optional("published")?.integer() ?? 0
  guard pending >= 0, published >= 0, pending <= Int32.max, published <= Int32.max else {
    throw MapRouletteError(.protocolFailure)
  }
  let claimed = try o.optional("claimedAs").map { value -> ClaimedAccount in
    let c = try value.object()
    return try ClaimedAccount(displayName: c.required("displayName").string(), osmID: c.required("osmId").integer())
  }
  guard let state = GuestState(rawValue: try o.required("state").string()),
    let email = GuestEmailState(rawValue: try o.optional("email")?.string() ?? "none")
  else { throw MapRouletteError(.protocolFailure) }
  return try GuestStatus(
    guestID: o.required("guestId").string(), state: state, email: email, pendingCount: Int(pending),
    publishedCount: Int(published), expiresAt: date(o.required("expiresAt")), claimedAs: claimed)
}

private func pendingChoice(_ o: [String: JSONValue]) throws -> PendingChoice {
  guard let state = PendingState(rawValue: try o.required("state").string()) else {
    throw MapRouletteError(.protocolFailure)
  }
  let result = try o.optional("result")?.object()
  let changeset = try result?.optional("changesetId")?.integer()
  return try PendingChoice(
    taskID: TaskID(o.required("taskId").integer()),
    challengeID: o.optional("challengeId").map { try ChallengeID($0.integer()) },
    state: state, answeredAt: date(o.required("answeredAt")),
    holdUntil: o.optional("holdUntil").map(date),
    changesetID: changeset.flatMap { $0 > 0 ? $0 : nil },
    droppedQuestionIDs: result?.optional("dropped")?.array().map { try $0.string() } ?? [])
}

/// RFC 3339 UTC, with or without fractional seconds.
private func date(_ value: JSONValue) throws -> Date {
  let text = try value.string()
  let plain = ISO8601DateFormatter()
  let fractional = ISO8601DateFormatter()
  fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
  guard let date = plain.date(from: text) ?? fractional.date(from: text) else {
    throw MapRouletteError(.protocolFailure)
  }
  return date
}
