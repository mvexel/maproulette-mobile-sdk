import Foundation
import MapRoulette
import os

/// The SDK operations the task screen uses; a fake replaces it in unit tests.
protocol TaskOps: Sendable {
  /// Outcomes decoded with the client's deletion setting (the SDK rejects others, except their
  /// `withoutDeletion()` form).
  func choiceOutcomes(_ task: MapRouletteTask) -> [ChoiceOutcome]
  func getTask(_ id: TaskID) async throws -> MapRouletteTask
  func getChallenge(_ task: MapRouletteTask) async throws -> Challenge
  func checkChoice(_ id: TaskID) async throws -> ChoiceEligibility
  func submitChoice(_ task: MapRouletteTask, _ submission: ChoiceSubmission) async throws -> ChoiceResult
  func skipTask(_ id: TaskID) async throws
}

/// Bound to one account's client: a controller never sends through a later account's client.
/// The client's deletion setting is the cap; "gone" without deletion (after `element_in_use`,
/// or when the check disallows deletion) is sent as `outcome.withoutDeletion()`.
struct ClientTaskOps: TaskOps {
  let client: MapRouletteClient

  init(_ client: MapRouletteClient) { self.client = client }

  func choiceOutcomes(_ task: MapRouletteTask) -> [ChoiceOutcome] { client.choiceOutcomes(task) }
  func getTask(_ id: TaskID) async throws -> MapRouletteTask { try await client.getTask(id) }
  func getChallenge(_ task: MapRouletteTask) async throws -> Challenge {
    try await client.getChallenge(task.challengeID)
  }
  func checkChoice(_ id: TaskID) async throws -> ChoiceEligibility { try await client.checkChoice(id) }
  func submitChoice(_ task: MapRouletteTask, _ submission: ChoiceSubmission) async throws -> ChoiceResult {
    try await client.submitChoice(task, submission)
  }
  func skipTask(_ id: TaskID) async throws { try await client.skipTask(id) }
}

enum ChoiceAction: Equatable, Sendable {
  /// Question id → option id; questions left out are "Can't tell".
  case answers([String: String])
  case outcome(ChoiceOutcome)
  case skip

  /// Whether the action edits OpenStreetMap (needs `osm:tagfix`).
  var editsOsm: Bool {
    switch self {
    case .answers: true
    case .outcome(let outcome): outcome.deletesElement
    case .skip: false
    }
  }
}

/// `me` is the signed-in MapRoulette user id; `canEditOsm`: the grant includes `osm:tagfix`.
struct Writer: Sendable {
  let me: Int64
  let canEditOsm: Bool
}

/// The questions of a valid choice task.
struct ChoiceWork: Equatable, Sendable {
  let element: OSMElementRef
  let questions: [ChoiceQuestion]

  init?(_ work: TaskWork) {
    guard case .choice(let element, _, let questions, _) = work else { return nil }
    self.element = element
    self.questions = questions
  }
}

/// The questions and offered outcomes of an eligible task, plus the user's current answers.
struct ChoiceForm: Equatable, Sendable {
  let work: ChoiceWork
  let outcomes: [ChoiceOutcome]
  var answers: [String: String] = [:]
  /// Ids of delete outcomes offered without deletion because the element cannot be deleted
  /// (it is in a way or relation); they record Not an issue.
  var notDeletable: Set<String> = []
}

/// A task and its challenge, as last read.
struct LoadedTask: Sendable {
  let task: MapRouletteTask
  let challenge: Challenge
}

/// Screen states (docs/design/task-completion.md §11, docs/design/mobile-choice-challenges.md §7 app rules).
enum TaskScreen: Sendable {
  enum Pending: Sendable { case unknown, submissionPending, statusPending }

  /// `task` is the fresh read after the write, or the pre-write task when `readFailed`.
  /// `byOther`: a check found someone else's result; this user's submission was not recorded.
  struct Done: Sendable {
    var loaded: LoadedTask
    var action: ChoiceAction
    var me: Int64
    var changesetID: Int64?
    var readFailed: Bool
    var byOther = false
    var checking = false
  }
  /// The submission may still land (unknown outcome, pending submission or status). Never resent
  /// automatically; the user re-reads with Check again. `canResend`: the user may send the
  /// identical submission again (confirmed), which the server resumes without a second upload.
  struct CheckAgain: Sendable {
    var loaded: LoadedTask
    var form: ChoiceForm
    var action: ChoiceAction
    var message: String
    var changesetID: Int64?
    var checking: Bool
    var pending: Pending = .unknown
    var canResend = false
  }

  case loading
  case loadFailed(String)
  /// Not a choice task, or nothing left to do: read-only, "Not available on mobile".
  case notAvailable(LoadedTask, reason: String)
  /// Signed out or read-only: the questions without actions. No eligibility check is made.
  case preview(LoadedTask, ChoiceWork)
  /// Ineligible (stale) at open or submit. No actions.
  case noLongerNeeded(LoadedTask)
  /// The eligibility check failed (network, OSM unavailable, …): not actionable until a retry succeeds.
  case checkFailed(LoadedTask)
  /// `notice` explains why the user is back here; the answers are kept.
  case answering(LoadedTask, ChoiceForm, notice: String?)
  case submitting(LoadedTask, ChoiceForm, ChoiceAction)
  case done(Done)
  /// A skip leaves the status unchanged. `uncertain`: the outcome is unknown and it was not resent.
  case skipped(LoadedTask, uncertain: Bool)
  case takenByOther(LoadedTask)
  case staleOwnLock(LoadedTask, ChoiceForm, ChoiceAction, lockedTaskID: TaskID)
  case checkAgain(CheckAgain)
  /// OSM re-consent or a missing scope. Nothing was changed.
  case signInRequired(LoadedTask, reason: String)
  /// A delete was refused (the node is in a way or relation); the same outcome may be sent without deletion.
  case elementInUse(LoadedTask, ChoiceForm, ChoiceOutcome)

  var loaded: LoadedTask? {
    switch self {
    case .loading, .loadFailed: nil
    case .notAvailable(let l, _), .preview(let l, _), .noLongerNeeded(let l), .checkFailed(let l),
      .answering(let l, _, _), .submitting(let l, _, _), .skipped(let l, _), .takenByOther(let l),
      .staleOwnLock(let l, _, _, _), .signInRequired(let l, _), .elementInUse(let l, _, _):
      l
    case .done(let d): d.loaded
    case .checkAgain(let c): c.loaded
    }
  }
}

/// Choice task screen logic with late locking. Opening checks eligibility (read-only for the
/// caller); a submission runs the SDK's start → POST choice → release-on-failure off the main
/// actor. Writes finish even if the controller is cancelled (so the SDK can release its lock), but
/// their results are then discarded. An edit is never resent: uncertain results wait for a manual
/// re-read.
@MainActor @Observable final class TaskWorkController {
  private(set) var state: TaskScreen = .loading
  /// True once a write or a stale finding may have changed the task, so maps and lists should refresh.
  private(set) var changed = false

  @ObservationIgnored private let ops: any TaskOps
  @ObservationIgnored private let writer: Writer?
  @ObservationIgnored private var job: Task<Void, Never>?
  @ObservationIgnored private var discarded = false
  @ObservationIgnored private let log = Logger(subsystem: "org.maproulette.example", category: "task")

  init(ops: any TaskOps, writer: Writer?) {
    self.ops = ops
    self.writer = writer
  }

  var busy: Bool {
    switch state {
    case .submitting: true
    case .checkAgain(let c): c.checking
    default: false
    }
  }

  var canEditOsm: Bool { writer?.canEditOsm == true }

  /// Waits for the current operation, including a write that outlives `cancel()`. For tests.
  func idle() async { await job?.value }

  func load(_ id: TaskID) {
    if busy || discarded { return }
    job?.cancel()
    state = .loading
    job = Task {
      let next: TaskScreen
      do {
        let task = try await ops.getTask(id)
        let challenge = try await ops.getChallenge(task)
        next = await opened(LoadedTask(task: task, challenge: challenge))
      } catch {
        next = .loadFailed(Self.readError(error))
      }
      publish(next)
    }
  }

  private func publish(_ next: TaskScreen) {
    if Task.isCancelled || discarded { return }
    state = next
  }

  private func opened(_ loaded: LoadedTask) async -> TaskScreen {
    let task = loaded.task
    guard task.mobileSupport() == .inPlace,
      let work = ChoiceWork(task.work())
    else { return .notAvailable(loaded, reason: TaskText.unavailableReason(task)) }
    guard writer != nil else { return .preview(loaded, work) }
    let eligibility: ChoiceEligibility
    do {
      eligibility = try await ops.checkChoice(task.id)
    } catch {
      // Not checked: not offered (spec §5a). Type only: messages may carry server text.
      log.debug("Choice check for task \(task.id.value) failed: \(String(describing: type(of: error)), privacy: .public)")
      return .checkFailed(loaded)
    }
    if !eligibility.eligible {
      if !Task.isCancelled { changed = true }  // The server now hides it from discovery.
      return .noLongerNeeded(loaded)
    }
    return .answering(
      loaded,
      Self.form(work, outcomes: ops.choiceOutcomes(task), deleteAllowed: eligibility.deleteAllowed),
      notice: nil)
  }

  /// `optionID` nil means "Can't tell": the question is left out of the submission.
  func select(question questionID: String, option optionID: String?) {
    guard case .answering(let loaded, var form, let notice) = state,
      let question = form.work.questions.first(where: { $0.id == questionID })
    else { return }
    if let optionID {
      guard question.options.contains(where: { $0.id == optionID }) else { return }
      form.answers[questionID] = optionID
    } else {
      form.answers[questionID] = nil
    }
    state = .answering(loaded, form, notice: notice)
  }

  /// Whether `action` may be offered on `form`'s task for this writer.
  func offers(_ task: MapRouletteTask, _ form: ChoiceForm, _ action: ChoiceAction) -> Bool {
    guard let writer else { return false }
    if action.editsOsm && !writer.canEditOsm { return false }
    switch action {
    case .answers(let answers):
      return !answers.isEmpty
        && answers.allSatisfy { q, o in
          form.work.questions.contains { $0.id == q && $0.options.contains { $0.id == o } }
        }
    case .outcome(let outcome): return form.outcomes.contains(outcome)
    case .skip: return task.canSkip()
    }
  }

  /// User-confirmed submission, from a screen that offers a fresh attempt.
  func perform(_ action: ChoiceAction) {
    let loaded: LoadedTask, form: ChoiceForm
    switch state {
    case .answering(let l, let f, _): (loaded, form) = (l, f)
    case .staleOwnLock(let l, let f, _, _): (loaded, form) = (l, f)
    default: return
    }
    guard offers(loaded.task, form, action) else { return }
    submit(loaded, form, action)
  }

  /// After `element_in_use`: the same outcome without deletion (Not an issue), once the user confirms.
  func sendWithoutDeletion() {
    guard case .elementInUse(let loaded, let form, let outcome) = state, writer != nil,
      let plain = Self.withoutDeletion(outcome)
    else { return }
    submit(loaded, form, .outcome(plain))
  }

  /// User-confirmed resend of the identical submission from Check again. The server's
  /// idempotency resumes it (for example finishes a pending status) without a second OSM upload.
  func resendSame() {
    guard case .checkAgain(let current) = state, current.canResend, !current.checking, writer != nil else {
      return
    }
    submit(current.loaded, current.form, current.action)
  }

  private func submit(_ loaded: LoadedTask, _ form: ChoiceForm, _ action: ChoiceAction) {
    guard let me = writer?.me, !discarded else { return }
    changed = true  // Before publishing: observers read it while rendering Submitting.
    state = .submitting(loaded, form, action)
    let ops = self.ops
    job = Task {
      // Only the write itself outlives cancellation (a detached task); its result is discarded.
      let result = await Task.detached { await Self.send(ops, loaded.task, action) }.value
      if Task.isCancelled || discarded { return }
      let next: TaskScreen
      switch result {
      case .success(let choice):
        next =
          action == .skip
          ? .skipped(loaded, uncertain: false)
          : await reread(loaded, action, me, choice?.changesetID)
      case .failure(let error): next = await failed(loaded, form, action, error)
      }
      publish(next)
    }
  }

  /// Manual re-check after an uncertain result or a failed post-write read. Never resends.
  func recheck() {
    guard let me = writer?.me else { return }
    switch state {
    case .checkAgain(let current) where !current.checking:
      job = Task { await verify(current, me) }
    case .done(var current) where current.readFailed && !current.checking:
      current.checking = true
      state = .done(current)
      job = Task { publish(await reread(current.loaded, current.action, me, current.changesetID)) }
    default: break
    }
  }

  /// Cancels reads and discards all later results. A write already sent still finishes.
  func cancel() {
    discarded = true
    job?.cancel()
  }

  private nonisolated static func send(_ ops: any TaskOps, _ task: MapRouletteTask, _ action: ChoiceAction) async
    -> Result<ChoiceResult?, any Error>
  {
    do {
      switch action {
      case .answers(let answers): return .success(try await ops.submitChoice(task, .answers(answers)))
      case .outcome(let outcome): return .success(try await ops.submitChoice(task, .outcome(outcome)))
      case .skip:
        try await ops.skipTask(task.id)
        return .success(nil)
      }
    } catch {
      return .failure(error)
    }
  }

  private func failed(_ loaded: LoadedTask, _ form: ChoiceForm, _ action: ChoiceAction, _ error: any Error) async
    -> TaskScreen
  {
    func answering(_ notice: String) -> TaskScreen { .answering(loaded, form, notice: notice) }
    func checkAgain(_ message: String, _ pending: TaskScreen.Pending, _ changesetID: Int64? = nil) -> TaskScreen {
      .checkAgain(
        .init(
          loaded: loaded, form: form, action: action, message: message, changesetID: changesetID,
          checking: false, pending: pending, canResend: pending == .statusPending))
    }
    guard let error = error as? MapRouletteError else {
      // Credential failures (for example a refresh that could not complete) happen before sending.
      return answering("Could not send the request. Nothing was recorded.")
    }
    if error.kind == .validation, error.problem == nil {
      // The SDK checks the submission against the payload before sending anything.
      return answering("The app could not complete this submission. Nothing was recorded.")
    }
    switch error.problem {
    case .choice(.taskIneligible)?: return .noLongerNeeded(loaded)
    case .choice(.elementInUse)?:
      if case .outcome(let outcome) = action, Self.withoutDeletion(outcome) != nil {
        return .elementInUse(loaded, form, outcome)
      }
      return answering("OpenStreetMap refused the change because the element is in use. Nothing was changed.")
    case .choice(.osmReauthRequired)?:
      return .signInRequired(
        loaded,
        reason: "OpenStreetMap no longer accepts MapRoulette's permission to edit for you. Nothing was changed. Sign in again to enable editing.")
    case .choice(.osmScopeRequired)?:
      return .signInRequired(
        loaded, reason: "This sign-in cannot edit OpenStreetMap. Nothing was changed. Sign in again to enable editing.")
    case .insufficientScope?:
      return .signInRequired(
        loaded, reason: "This sign-in can only read tasks. Nothing was recorded. Sign in again to enable task actions.")
    case .choice(.osmUnavailable)?:
      return answering(
        "OpenStreetMap edits are not available right now, so nothing was changed. Your answers are kept; try again later.")
    case .choice(.submissionPending)?:
      return checkAgain(
        "An earlier submission for this task is still being processed. Nothing was sent.", .submissionPending)
    case .choice(.statusPending(let changeset))?:
      return checkAgain(
        "The edit reached OpenStreetMap, but MapRoulette has not recorded the result yet. It was not sent again. "
          + "Sending the same submission again finishes it without a second upload.", .statusPending, changeset)
    case .outcomeUnknown?:
      if action == .skip { return .skipped(loaded, uncertain: true) }
      return checkAgain("The connection failed before MapRoulette confirmed the result. It was not sent again.", .unknown)
    case .choice(.invalidSubmission)?:
      return answering("MapRoulette did not accept this submission. Nothing was recorded.")
    case .choice(.unsupportedTask)?:
      return .notAvailable(
        loaded, reason: "MapRoulette cannot process this task's questions. Complete it on the MapRoulette website.")
    case .lockedByOtherUser?: return .takenByOther(loaded)
    case .alreadyHoldingTask(let held, _, _, _)?: return .staleOwnLock(loaded, form, action, lockedTaskID: held)
    case .lockLost?:
      return answering("Your lock on this task ended before the result was recorded. Nothing was recorded; you can try again.")
    case .invalidTransition?: return await notAccepted(loaded)
    case nil:
      switch error.kind {
      case .authentication:
        return answering(
          "MapRoulette rejected the sign-in, so nothing was recorded. Choose again; if it keeps failing, sign in again.")
      case .notFound: return .loadFailed("This task no longer exists.")
      default: return answering(Self.writeError(error))
      }
    }
  }

  private func notAccepted(_ loaded: LoadedTask) async -> TaskScreen {
    let fresh = (try? await ops.getTask(loaded.task.id)) ?? loaded.task
    return .notAvailable(
      LoadedTask(task: fresh, challenge: loaded.challenge),
      reason: "MapRoulette did not accept this result. The task may already be completed or its challenge paused.")
  }

  private func reread(_ loaded: LoadedTask, _ action: ChoiceAction, _ me: Int64, _ changesetID: Int64?) async
    -> TaskScreen
  {
    do {
      let fresh = try await ops.getTask(loaded.task.id)
      return .done(
        .init(
          loaded: LoadedTask(task: fresh, challenge: loaded.challenge), action: action, me: me,
          changesetID: changesetID ?? fresh.changesetID, readFailed: false))
    } catch {
      return .done(.init(loaded: loaded, action: action, me: me, changesetID: changesetID, readFailed: true))
    }
  }

  /// Applies docs/design/task-completion.md §5 to a fresh read. Never resends and never releases: an
  /// edit may still be uploading.
  private func verify(_ pending: TaskScreen.CheckAgain, _ me: Int64) async {
    var checking = pending
    checking.checking = true
    state = .checkAgain(checking)
    let fresh: MapRouletteTask
    do {
      fresh = try await ops.getTask(pending.loaded.task.id)
    } catch {
      var failed = pending
      failed.message = "The task could not be re-read. Check your connection and try again."
      publish(.checkAgain(failed))
      return
    }
    let loaded = LoadedTask(task: fresh, challenge: pending.loaded.challenge)
    let target: TaskResolution
    switch pending.action {
    case .answers: target = .fixed
    case .outcome(let outcome): target = outcome.resolution
    case .skip:  // Not reached: an uncertain skip goes straight to Skipped.
      publish(.skipped(loaded, uncertain: true))
      return
    }
    let check = fresh.verifyResolution(target: target, me: me)
    let inOsm = pending.changesetID  // Known only after status_pending: the edit is in OSM.
    var next = pending
    next.loaded = loaded
    next.checking = false
    let result: TaskScreen
    if check == .applied {
      result = .done(
        .init(loaded: loaded, action: pending.action, me: me, changesetID: fresh.changesetID ?? inOsm, readFailed: false))
    } else if let inOsm {
      // Our edit is in OSM but MapRoulette has no status for it: never call it "not recorded".
      next.canResend = check == .notAppliedLockHeld || check == .notAppliedUnlocked
      next.message =
        "Your edit is in OpenStreetMap changeset \(inOsm), but MapRoulette has not recorded the result "
        + "(task status: \(TaskText.status(fresh.status?.code).lowercased()))."
      result = .checkAgain(next)
    } else if check == .resolvedByOther {
      result = .done(
        .init(
          loaded: loaded, action: pending.action, me: me, changesetID: fresh.changesetID, readFailed: false,
          byOther: fresh.completedBy != nil && fresh.completedBy != me))
    } else if check == .lockedByOther {
      result = .takenByOther(loaded)
    } else if pending.pending == .submissionPending {
      // Another submission is unfinished; resubmitting would only be refused again.
      next.message =
        "Another submission for this task is still unfinished, so it cannot be answered now. Pick another task."
      result = .checkAgain(next)
    } else if check == .notAppliedLockHeld {
      next.canResend = true
      next.message =
        "Not recorded yet, and the task is still locked to you. Check again in a minute, or send the same submission again."
      result = .checkAgain(next)
    } else if pending.action.editsOsm {
      // An edit may have reached OSM: only the identical submission can be resumed.
      next.canResend = true
      next.message = "Not recorded. You can send the same submission again; MapRoulette does not upload an edit twice."
      result = .checkAgain(next)
    } else {
      result = .answering(loaded, pending.form, notice: "Your earlier submission was not recorded. You can submit again.")
    }
    publish(result)
  }

  private static func readError(_ error: any Error) -> String {
    guard let error = error as? MapRouletteError else { return "Could not load the task. Please retry." }
    switch error.kind {
    case .notFound: return "Task not found."
    case .network: return "Could not connect to MapRoulette. Check your connection and retry."
    case .authentication: return "Your sign-in is no longer valid. Sign in again."
    default: return "MapRoulette could not load the task. Please retry."
    }
  }

  private static func writeError(_ error: MapRouletteError) -> String {
    switch error.kind {
    case .network: "Could not connect to MapRoulette. Nothing was recorded. Check your connection and try again."
    case .rateLimit: "MapRoulette is limiting requests. Nothing was recorded. Wait a moment and try again."
    case .http:
      "MapRoulette refused the request (HTTP \(error.status.map(String.init) ?? "?")). Nothing was recorded. The challenge may be paused."
    case .permission: "MapRoulette refused the request. Nothing was recorded."
    default: "MapRoulette returned an unexpected response. Nothing was recorded."
    }
  }

  /// The form for an eligible task: `outcomes` are the client's (declared plus Too hard, decoded
  /// with the deletion setting). When the check does not allow deletion (the node is in a way or
  /// relation), a delete outcome is offered without deletion instead (Not an issue) and listed in
  /// `notDeletable`.
  nonisolated static func form(_ work: ChoiceWork, outcomes: [ChoiceOutcome], deleteAllowed: Bool) -> ChoiceForm {
    let blocked = deleteAllowed ? [] : Set(outcomes.filter(\.deletesElement).map(\.id))
    return ChoiceForm(
      work: work, outcomes: outcomes.map { deleteAllowed ? $0 : $0.withoutDeletion() },
      notDeletable: blocked)
  }

  /// The deleting `outcome` without deletion (Not an issue), or nil if it is not a delete.
  nonisolated static func withoutDeletion(_ outcome: ChoiceOutcome) -> ChoiceOutcome? {
    outcome.deletesElement ? outcome.withoutDeletion() : nil
  }
}
