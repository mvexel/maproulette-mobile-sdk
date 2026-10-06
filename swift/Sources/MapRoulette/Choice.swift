import Foundation

/// One question about the task's element. `expect` maps each guarded key to its required current
/// value (nil: absent). "Can't tell" is not listed: leaving the question out of the answers means it.
public struct ChoiceQuestion: Sendable, Equatable {
  public let id: String, prompt: String, description: String?
  public let expect: [String: String?]
  public let options: [ChoiceOption]
}
/// One answer and its exact tag change; show the change below the label.
public struct ChoiceOption: Sendable, Equatable {
  public let id: String, label: String, description: String?
  public let setTags: [String: String], unsetTags: [String]
}
/// A task-level result that is not an answer. `resolution` is the status it will set: the declared
/// 2 or 6, Not an issue for "gone" without deletion, or Fixed for an enabled delete.
/// `deletesElement` is true only when the payload declares a delete AND deletion is enabled.
public struct ChoiceOutcome: Sendable, Equatable {
  public let id: String, label: String, description: String?
  public let resolution: TaskResolution
  public let deletesElement: Bool
  public init(
    id: String, label: String, description: String?, resolution: TaskResolution,
    deletesElement: Bool
  ) {
    self.id = id
    self.label = label
    self.description = description
    self.resolution = resolution
    self.deletesElement = deletesElement
  }
}
public enum ChoiceSubmission: Sendable, Equatable {
  /// Question id → option id; non-empty. Questions left out are "Can't tell".
  case answers([String: String])
  /// One of `choiceOutcomes`, decoded with the same deletion setting as the client.
  case outcome(ChoiceOutcome)
}
/// `changesetID` is nil when nothing was edited in OSM. After an interrupted response the result
/// comes from a fresh task read, which may not report the changeset: then it is nil even for an
/// edit.
public struct ChoiceResult: Sendable, Equatable {
  public let status: TaskStatus, changesetID: Int64?
}
/// Why a choice task went stale: the element is gone or invisible, its `match` tags changed, or a
/// guarded key no longer has its expected value. `.unknown` covers reasons added later.
public enum IneligibleReason: Sendable, Equatable {
  case elementGone, matchFailed, keyChanged, unknown
}
/// Fresh server-side OSM check. Eligibility is all-or-nothing: an ineligible task is not shown at
/// all. `deleteAllowed` (the node is in no way or relation) is only meaningful when `eligible`;
/// `reason` is set only when not. The server's diagnostic `detail` is not modelled.
public struct ChoiceEligibility: Sendable, Equatable {
  public let eligible: Bool, deleteAllowed: Bool, reason: IneligibleReason?
}
/// Failures specific to choice submission and checks (`POST task/{id}/choice`, `choice/check`).
public enum ChoiceProblem: Sendable, Equatable {
  /// 409 task_ineligible: the OSM element changed since the payload was written. Nothing was
  /// uploaded and no status was written; the server hides the task from mobile discovery. Say
  /// "This one no longer needs answering" and move on; never offer a conflict choice.
  case taskIneligible(reason: IneligibleReason)
  /// 409 element_in_use: a delete was refused because the node belongs to a way or relation.
  case elementInUse
  /// 401 osm_reauth_required: re-consent with `osm:tagfix`; the MapRoulette session stays valid.
  case osmReauthRequired
  /// 403 insufficient_scope for `osm:tagfix`: the grant cannot edit OSM.
  case osmScopeRequired
  /// 502 osm_unavailable (OSM unreachable) or 503 osm_edits_unavailable (the server cannot use
  /// stored OSM tokens): nothing was applied.
  case osmUnavailable
  /// 409 submission_pending: another submission for this task is unfinished.
  case submissionPending
  /// 500 status_pending: the OSM edit is uploaded but the status write failed and the SDK's one
  /// identical resend did not finish it. The lock is kept; resubmitting the same submission (after
  /// a new start if the lock expired) finishes it without a second upload.
  case statusPending(changesetID: Int64?)
  /// 422 invalid_submission: an id is not in the stored payload. `detail` is the server's text.
  case invalidSubmission(detail: String?)
  /// 422 unsupported_task: the stored payload fails server validation.
  case unsupportedTask
}

let tooHardID = "too-hard"

extension MapRouletteTask {
  /// Declared outcomes plus the built-in Too hard; empty when the task is not a valid choice task.
  /// Pass the client's `allowElementDeletion`.
  public func choiceOutcomes(allowElementDeletion: Bool = false) -> [ChoiceOutcome] {
    guard case .choice(_, _, _, let outcomes) = work(allowElementDeletion: allowElementDeletion)
    else { return [] }
    return outcomes + [
      ChoiceOutcome(
        id: tooHardID, label: "Too hard", description: nil, resolution: .tooHard,
        deletesElement: false)
    ]
  }

  /// Checks the submission against the payload before anything is sent; returns the status the
  /// server will set on success.
  func validateChoice(_ submission: ChoiceSubmission, allowElementDeletion: Bool) throws
    -> TaskResolution
  {
    guard bundleID == nil,
      case .choice(_, _, let questions, _) = work(allowElementDeletion: allowElementDeletion)
    else { throw MapRouletteError(.validation) }
    switch submission {
    case .answers(let answers):
      guard (1...8).contains(answers.count),
        answers.allSatisfy({ answer in
          questions.contains { $0.id == answer.key && $0.options.contains { $0.id == answer.value } }
        })
      else { throw MapRouletteError(.validation) }
      return .fixed
    case .outcome(let outcome):
      // Must equal what this client decodes, so a UI built with another deletion setting fails.
      guard choiceOutcomes(allowElementDeletion: allowElementDeletion).contains(outcome) else {
        throw MapRouletteError(.validation)
      }
      return outcome.resolution
    }
  }
}

/// Canonical request body: answers sorted by question id; ids are pattern-checked, so no escaping.
func choiceBody(_ submission: ChoiceSubmission) -> String {
  switch submission {
  case .answers(let answers):
    let pairs = answers.sorted { $0.key < $1.key }.map { "\"\($0.key)\":\"\($0.value)\"" }
    return "{\"answers\":{" + pairs.joined(separator: ",") + "}}"
  case .outcome(let outcome):
    return "{\"outcome\":\"\(outcome.id)\"" + (outcome.deletesElement ? ",\"delete\":true" : "") + "}"
  }
}

private struct Invalid: Error {}

/// Strict v1 decoding (docs/mobile-choice-challenges.md §2). Any broken rule throws; unknown
/// fields are ignored. The 16 KiB size rule is server-only.
func choiceWork(_ raw: [String: JSONValue], allowElementDeletion: Bool) throws -> TaskWork {
  let meta = try object(required(raw["meta"]))
  guard try whole(meta["version"]) == 2, try whole(meta["type"]) == 3,
    try whole(meta["choiceVersion"]) == 1
  else { throw Invalid() }
  let element = try osmElement(text(required(raw["element"])))
  var match: [String: String] = [:]
  if let value = present(raw["match"]) {
    let o = try object(value)
    guard (1...4).contains(o.count) else { throw Invalid() }
    for (key, tag) in o { match[try tagText(key)] = try tagText(text(tag)) }
  }
  let list = try array(required(raw["questions"]))
  guard (1...8).contains(list.count) else { throw Invalid() }
  let questions = try list.map { try question(object($0)) }
  guard Set(questions.map(\.id)).count == questions.count else { throw Invalid() }
  let guarded = questions.flatMap { $0.expect.keys.map(bytes) }
  let matchKeys = Set(match.keys.map(bytes))
  guard Set(guarded).count == guarded.count, !guarded.contains(where: matchKeys.contains) else {
    throw Invalid()
  }
  let declared = try present(raw["outcomes"]).map(array) ?? []
  guard declared.count <= 4 else { throw Invalid() }
  var deletes = 0
  let outcomes = try declared.map { item -> ChoiceOutcome in
    let o = try object(item)
    let id = try choiceID(text(required(o["id"])))
    guard id != tooHardID else { throw Invalid() }
    let status = try present(o["status"]).map { try whole($0) }
    var delete = false
    if let value = present(o["delete"]) {
      guard value == .bool(true) else { throw Invalid() }
      delete = true
    }
    guard (status != nil) != delete, status == nil || status == 2 || status == 6 else {
      throw Invalid()
    }
    if delete { deletes += 1 }
    let resolution: TaskResolution =
      delete ? (allowElementDeletion ? .fixed : .notAnIssue) : (status == 2 ? .notAnIssue : .tooHard)
    return try ChoiceOutcome(
      id: id, label: length(text(required(o["label"])), 1, 60),
      description: optionalText(o["description"], 300), resolution: resolution,
      deletesElement: delete && allowElementDeletion)
  }
  guard Set(outcomes.map(\.id)).count == outcomes.count,
    deletes == 0 || (deletes == 1 && element.type == .node && !match.isEmpty)
  else { throw Invalid() }
  return .choice(element: element, match: match, questions: questions, outcomes: outcomes)
}

private func question(_ o: [String: JSONValue]) throws -> ChoiceQuestion {
  let id = try choiceID(text(required(o["id"])))
  let rawExpect = try object(required(o["expect"]))
  guard (1...4).contains(rawExpect.count) else { throw Invalid() }
  var expect: [String: String?] = [:]
  for (key, value) in rawExpect {
    expect[try tagText(key)] = .some(value == .null ? nil : try tagText(text(value)))
  }
  let list = try array(required(o["options"]))
  guard (2...12).contains(list.count) else { throw Invalid() }
  let options = try list.map { item -> ChoiceOption in
    let option = try object(item)
    var set: [String: String] = [:]
    for (key, value) in try present(option["setTags"]).map(object) ?? [:] {
      set[try tagText(key)] = try tagText(text(value))
    }
    let unset = try (present(option["unsetTags"]).map(array) ?? []).map { try tagText(text($0)) }
    // Compare tags as UTF-8 bytes like Kotlin and the backend: Swift String equality would treat
    // canonically equivalent spellings (NFC/NFD) as equal.
    let expected = Dictionary(expect.map { (bytes($0.key), $0.value.map(bytes)) }) { a, _ in a }
    let setKeys = Set(set.keys.map(bytes))
    let unsetKeys = unset.map(bytes)
    guard !set.isEmpty || !unset.isEmpty, Set(unsetKeys).count == unsetKeys.count,
      !unsetKeys.contains(where: setKeys.contains),
      (Array(setKeys) + unsetKeys).allSatisfy({ expected[$0] != nil })
    else { throw Invalid() }
    // An option equal to the expected state would be a no-op edit.
    var after = expected
    for (key, value) in set { after[bytes(key)] = .some(bytes(value)) }
    for key in unsetKeys { after[key] = .some(nil) }
    guard after != expected else { throw Invalid() }
    return try ChoiceOption(
      id: choiceID(text(required(option["id"]))),
      label: length(text(required(option["label"])), 1, 60),
      description: optionalText(option["description"], 300), setTags: set, unsetTags: unset)
  }
  guard Set(options.map(\.id)).count == options.count else { throw Invalid() }
  return try ChoiceQuestion(
    id: id, prompt: length(text(required(o["prompt"])), 1, 200),
    description: optionalText(o["description"], 500), expect: expect, options: options)
}

/// `^(node|way|relation)/[1-9][0-9]{0,15}$`, checked without a regex.
func osmElement(_ value: String) throws -> OSMElementRef {
  let parts = value.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
  guard parts.count == 2, let type = OSMElementRef.ElementType(rawValue: String(parts[0])),
    let first = parts[1].unicodeScalars.first, ("1"..."9").contains(first),
    (1...16).contains(parts[1].unicodeScalars.count),
    parts[1].unicodeScalars.allSatisfy({ ("0"..."9").contains($0) }), let id = Int64(parts[1])
  else { throw Invalid() }
  return OSMElementRef(type: type, id: id)
}
/// `^[a-z0-9][a-z0-9-]{0,31}$`
func choiceID(_ value: String) throws -> String {
  func ok(_ c: Unicode.Scalar) -> Bool { ("a"..."z").contains(c) || ("0"..."9").contains(c) }
  let scalars = value.unicodeScalars
  guard (1...32).contains(scalars.count), ok(scalars.first!),
    scalars.allSatisfy({ ok($0) || $0 == "-" })
  else { throw Invalid() }
  return value
}
// Lengths count Unicode code points on both platforms.
private func length(_ value: String, _ min: Int, _ max: Int) throws -> String {
  guard (min...max).contains(value.unicodeScalars.count) else { throw Invalid() }
  return value
}
/// OSM tag key or value: 1–255 code points, no control characters (Cc).
private func tagText(_ value: String) throws -> String {
  _ = try length(value, 1, 255)
  guard !value.unicodeScalars.contains(where: { $0.value < 0x20 || (0x7F...0x9F).contains($0.value) })
  else { throw Invalid() }
  return value
}
private func bytes(_ value: String) -> [UInt8] { Array(value.utf8) }
private func present(_ value: JSONValue?) -> JSONValue? {
  guard let value, value != .null else { return nil }
  return value
}
private func required(_ value: JSONValue?) throws -> JSONValue {
  guard let value = present(value) else { throw Invalid() }
  return value
}
private func object(_ value: JSONValue) throws -> [String: JSONValue] {
  guard case .object(let v) = value else { throw Invalid() }
  return v
}
private func array(_ value: JSONValue) throws -> [JSONValue] {
  guard case .array(let v) = value else { throw Invalid() }
  return v
}
private func text(_ value: JSONValue) throws -> String {
  guard case .string(let v) = value else { throw Invalid() }
  return v
}
private func whole(_ value: JSONValue?) throws -> Int64 {
  guard case .integer(let v)? = value else { throw Invalid() }
  return v
}
private func optionalText(_ value: JSONValue?, _ max: Int) throws -> String? {
  try present(value).map { try length(text($0), 0, max) }
}
