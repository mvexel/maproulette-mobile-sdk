import AuthenticationServices
import MapRoulette
import SwiftUI

/// Multiple-choice task screen with late locking (docs/mobile-choice-challenges.md §7). Viewing never locks.
struct TaskView: View {
  @Environment(AppSession.self) private var session
  @Environment(TaskChanges.self) private var changes
  @Environment(\.webAuthenticationSession) private var webAuth
  @Environment(\.dismiss) private var dismiss

  @State private var taskID: Int64
  /// Tasks from the list or map to offer as "Next task", in order; never includes `taskID`.
  @State private var upcoming: [Int64]
  @State private var controller: TaskWorkController?
  @State private var generation: Int?
  @State private var confirmation: Confirmation?
  /// Shown above the next screen after an account switch.
  @State private var switchNotice: String?
  @State private var reportedChange = false

  init(taskID: Int64, order: [Int64]) {
    _taskID = State(initialValue: taskID)
    _upcoming = State(initialValue: TaskText.nextTasks(order, current: taskID))
  }

  /// A confirmation bound to the controller that showed it: a dialog left open across an
  /// account switch must not write as the new account.
  private struct Confirmation: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let confirm: String
    let owner: TaskWorkController
    let run: (TaskWorkController) -> Void
  }

  var body: some View {
    Form {
      if let controller {
        content(controller)
      } else {
        ProgressView()
      }
    }
    .navigationTitle(controller?.state.loaded?.task.name ?? "Task \(taskID)")
    .navigationBarTitleDisplayMode(.inline)
    // Back is blocked while a write is in flight (normally a few seconds).
    .navigationBarBackButtonHidden(controller?.busy == true)
    .alert(
      confirmation?.title ?? "", isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }),
      presenting: confirmation
    ) { c in
      Button(c.confirm) { if controller === c.owner { c.run(c.owner) } }
      Button("Cancel", role: .cancel) {}
    } message: { c in
      Text(c.message)
    }
    .onAppear {
      if controller == nil { start() }
    }
    .onChange(of: session.view.generation) { accountChanged() }
    .onChange(of: controller?.changed) { if controller?.changed == true { reportChange() } }
  }

  // MARK: Lifecycle

  private func start() {
    generation = session.view.generation
    replaceController()
    controller?.load(try! TaskID(taskID))
  }

  /// A controller is bound to one account's clients; results of a replaced one are never shown.
  private func replaceController() {
    let view = session.view
    let writer =
      session.writesConfigured && view.signedIn && view.canWriteTasks
      ? view.userID.map { Writer(me: $0, canEditOsm: view.canEditOsm) } : nil
    controller = TaskWorkController(ops: ClientTaskOps(session.newClient()), writer: writer)
  }

  /// Account switch or sign-out: discard the old account's state and any late result.
  private func accountChanged() {
    guard let old = controller, generation != session.view.generation else { return }
    generation = session.view.generation
    var interrupted = old.busy
    if case .checkAgain = old.state { interrupted = true }
    if interrupted || old.changed { reportChange() }
    old.cancel()
    confirmation = nil
    replaceController()
    // §7: the old account's in-flight write is cancelled; its result cannot be checked with the
    // new account, so say so instead of implying nothing happened.
    switchNotice =
      (interrupted
        ? "Your sign-in changed while a result was being recorded, so its outcome is unknown. Check the task status below. "
        : "") + session.view.message
    controller?.load(try! TaskID(taskID))
  }

  private func reportChange() {
    // Once per screen: lists and maps reload when the user returns.
    if !reportedChange {
      reportedChange = true
      changes.taskChanged()
    }
  }

  /// Opens the next task from the list or map in this screen.
  private func openNext() {
    guard let controller, !controller.busy, !upcoming.isEmpty else { return }
    switchNotice = nil
    taskID = upcoming.removeFirst()
    if controller.changed { reportChange() }
    replaceController()
    self.controller?.load(try! TaskID(taskID))
  }

  private func signIn() {
    Task {
      await session.signIn { url in
        try await webAuth.authenticate(
          using: url, callbackURLScheme: AuthEndpoints.callbackScheme, preferredBrowserSession: .ephemeral)
      }
    }
  }

  // MARK: Content

  @ViewBuilder private func content(_ c: TaskWorkController) -> some View {
    let state = c.state
    if let loaded = state.loaded { header(loaded) }
    if let notice = notice(state) {
      Section {
        HStack(alignment: .firstTextBaseline) {
          if showsProgress(state) { ProgressView() }
          Text(notice).textSelection(.enabled)
        }
        .accessibilityAddTraits(.updatesFrequently)
      }
    } else if showsProgress(state) {
      Section { ProgressView() }
    }
    stateBody(c, state)
    if let loaded = state.loaded {
      Section("Instructions") {
        Text(TaskText.instruction(loaded.task, loaded.challenge)).textSelection(.enabled)
      }
    }
  }

  @ViewBuilder private func header(_ loaded: LoadedTask) -> some View {
    let task = loaded.task
    Section {
      VStack(alignment: .leading, spacing: 4) {
        Text(task.name).font(.title3.bold())
        let element = ChoiceWork(task.work()).map { " · \(TaskText.element($0.element))" } ?? ""
        Text(verbatim: "Task \(task.id.value) · \(TaskText.status(task.status?.code))\(element)")
          .font(.subheadline)
        Text(verbatim: "Challenge \(loaded.challenge.id.value): \(loaded.challenge.name)")
          .font(.subheadline).foregroundStyle(.secondary)
      }
      if let lockedBy = task.lockedBy {
        Label(
          lockedBy == session.view.userID
            ? "This task is still locked to you from an earlier attempt."
            : "Someone is working on this task right now. You can still continue, but they may finish first.",
          systemImage: "lock"
        )
        .listRowBackground(Color.yellow.opacity(0.2))
      }
    }
  }

  private func showsProgress(_ state: TaskScreen) -> Bool {
    switch state {
    case .loading, .submitting: true
    case .done(let d): d.checking
    case .checkAgain(let c): c.checking
    default: false
    }
  }

  private func notice(_ state: TaskScreen) -> String? {
    var text: String?
    switch state {
    case .loading: text = "Loading task…"
    case .loadFailed(let message): text = message
    case .notAvailable(_, let reason): text = "Not available on mobile. \(reason)"
    case .preview: text = nil
    case .checkFailed:
      text =
        "Couldn't check this right now. The app checks OpenStreetMap before a task can be answered; check your connection and retry."
    case .noLongerNeeded: text = TaskText.noLongerNeeded
    case .answering(_, _, let notice): text = notice
    case .submitting(_, _, let action):
      text = action.editsOsm ? "Uploading to OpenStreetMap and recording the result…" : "Recording “\(label(action))”…"
    case .done(let d): text = doneText(d)
    case .skipped(_, let uncertain):
      text =
        uncertain
        ? "The connection failed before MapRoulette confirmed the skip. It was not sent again; the task may or may not count as skipped."
        : "Skipped. The task stays open for others."
    case .takenByOther: text = "Someone else is working on this task. Pick another one."
    case .staleOwnLock(_, _, _, let held):
      text = "A previous attempt on task \(held.value) is still open, so this task could not be started. Nothing was recorded."
    case .checkAgain(let c):
      text = c.checking ? "Checking result…" : c.message + (c.changesetID.map { "\nOpenStreetMap changeset \($0)." } ?? "")
    case .signInRequired(_, let reason): text = reason
    case .elementInUse(_, let form, _):
      text = "\(TaskText.element(form.work.element)) is part of a way or relation in OpenStreetMap, so it was not deleted. Nothing was changed."
    }
    if let switchNotice, !isLoading(state) {
      return [switchNotice, text].compactMap { $0 }.joined(separator: "\n")
    }
    return text
  }

  private func isLoading(_ state: TaskScreen) -> Bool {
    if case .loading = state { return true }
    return false
  }

  @ViewBuilder private func stateBody(_ c: TaskWorkController, _ state: TaskScreen) -> some View {
    switch state {
    case .loading: EmptyView()
    case .loadFailed:
      Section {
        Button("Retry") { c.load(try! TaskID(taskID)) }
        Button("Back") { dismiss() }
      }
    case .notAvailable, .takenByOther, .skipped:
      Section { Button("Back to tasks") { dismiss() } }
    case .preview(_, let work):
      questionCards(c, work, form: nil, enabled: false)
      signInPrompt()
    case .checkFailed:
      Section {
        Button("Retry") { c.load(try! TaskID(taskID)) }
        if !upcoming.isEmpty { Button("Next task") { openNext() } }
        Button("Back to tasks") { dismiss() }
      }
    case .noLongerNeeded:
      Section {
        if !upcoming.isEmpty { Button("Next task") { openNext() } }
        Button("Back to tasks") { dismiss() }
      }
    case .answering(let loaded, let form, _):
      choiceActions(c, loaded, form, submitting: nil)
    case .submitting(let loaded, let form, let action):
      choiceActions(c, loaded, form, submitting: action)
    case .done(let d):
      Section {
        if !d.byOther, let id = d.changesetID { changesetLink(id) }
        if d.readFailed { Button("Check again") { c.recheck() }.disabled(d.checking) }
        Button("Back to tasks") { dismiss() }
      }
    case .staleOwnLock(let loaded, let form, let action, _):
      Section {
        Button(String("Retry “\(label(action))”")) { confirm(loaded, form, action) }
        Button("Back to tasks") { dismiss() }
      }
    case .checkAgain(let pending):
      if !pending.checking {
        Section {
          if let id = pending.changesetID { changesetLink(id) }
          Button("Check again") { c.recheck() }
          if pending.canResend { Button("Send the same submission again") { confirmResend(pending) } }
          Button("Back to tasks") { dismiss() }
        }
      }
    case .signInRequired:
      Section {
        Button("Sign in again to enable editing") { signIn() }
        Text(session.accountHint).font(.footnote).foregroundStyle(.secondary)
        Button("Back to tasks") { dismiss() }
      }
    case .elementInUse(let loaded, let form, let outcome):
      Section {
        if let plain = TaskWorkController.withoutDeletion(loaded.task, outcome), let me = session.view.userID {
          Button {
            confirmation = Confirmation(
              title: "Send “\(outcome.label)” without deleting?",
              message: TaskText.outcomeSummary(
                plain, element: form.work.element, taskID: loaded.task.id.value, me: me,
                osmServer: session.osmServer, notDeletable: true),
              confirm: "Confirm", owner: c, run: { $0.sendWithoutDeletion() })
          } label: {
            actionLabel(
              "Send “\(outcome.label)” without deleting",
              TaskText.outcomeExplanation(plain, element: form.work.element, notDeletable: true))
          }
        }
        Button("Back to tasks") { dismiss() }
      }
    }
  }

  @ViewBuilder private func signInPrompt() -> some View {
    if session.writesConfigured {
      Section {
        Text(session.view.signedIn ? "Your sign-in can only read tasks." : "Sign in to answer this task.")
        Button(session.view.signedIn ? "Sign in again to enable editing" : "Sign in") { signIn() }
        Text(session.accountHint).font(.footnote).foregroundStyle(.secondary)
      }
    }
  }

  /// Question cards, Submit, then the task-level outcomes and Skip. Writable sessions only.
  @ViewBuilder private func choiceActions(
    _ c: TaskWorkController, _ loaded: LoadedTask, _ form: ChoiceForm, submitting: ChoiceAction?
  ) -> some View {
    let idle = submitting == nil
    if !c.canEditOsm {
      Section {
        Text("This sign-in cannot edit OpenStreetMap, so answers cannot be uploaded. Outcomes that only record a result still work.")
        Button("Sign in again to enable editing") { signIn() }.disabled(!idle)
        Text(session.accountHint).font(.footnote).foregroundStyle(.secondary)
      }
    }
    questionCards(c, form.work, form: form, enabled: idle)
    Section {
      Button {
        guard case .answering(let l, let f, _) = c.state else { return }
        confirm(l, f, .answers(f.answers))
      } label: {
        if case .answers = submitting {
          HStack { ProgressView(); Text("Uploading…") }
        } else {
          Text("Submit answers").bold()
        }
      }
      .disabled(!(idle && c.canEditOsm && !form.answers.isEmpty))
    }
    Section("Or, for the whole task:") {
      let actions = form.outcomes.map(ChoiceAction.outcome) + [.skip]
      ForEach(Array(actions.filter { c.offers(loaded.task, form, $0) }.enumerated()), id: \.offset) { _, action in
        Button {
          guard case .answering(let l, let f, _) = c.state else { return }
          confirm(l, f, action)
        } label: {
          if action == submitting {
            HStack { ProgressView(); Text("Recording…") }
          } else {
            actionLabel(label(action), explanation(form, action))
          }
        }
        .disabled(!idle)
      }
    }
  }

  /// One card per question; `form` nil shows them read-only without a selection.
  @ViewBuilder private func questionCards(
    _ c: TaskWorkController, _ work: ChoiceWork, form: ChoiceForm?, enabled: Bool
  ) -> some View {
    ForEach(work.questions, id: \.id) { question in
      Section {
        ForEach(question.options, id: \.id) { option in
          optionRow(
            c, question: question.id, option: option.id, selected: form?.answers[question.id] == option.id,
            selectable: form != nil, enabled: enabled
          ) {
            Text(option.label)
            Text(TaskText.tagChange(option)).font(.footnote.monospaced()).foregroundStyle(.secondary)
            if let description = option.description, !description.isEmpty {
              Text(description).font(.footnote).foregroundStyle(.secondary)
            }
          }
        }
        if form != nil {
          optionRow(
            c, question: question.id, option: nil, selected: form?.answers[question.id] == nil, selectable: true,
            enabled: enabled
          ) { Text(TaskText.cantTell) }
        }
      } header: {
        VStack(alignment: .leading, spacing: 2) {
          Text(question.prompt).font(.headline).foregroundStyle(.primary).textCase(nil)
          if let description = question.description, !description.isEmpty {
            Text(description).font(.footnote).textCase(nil)
          }
        }
      }
    }
  }

  @ViewBuilder private func optionRow<Label: View>(
    _ c: TaskWorkController, question: String, option: String?, selected: Bool, selectable: Bool, enabled: Bool,
    @ViewBuilder label: () -> Label
  ) -> some View {
    let content = HStack {
      VStack(alignment: .leading, spacing: 2) { label() }
      Spacer()
      if selectable {
        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
          .foregroundStyle(selected ? Color.accentColor : .secondary)
      }
    }
    if selectable {
      Button { c.select(question: question, option: option) } label: { content.contentShape(Rectangle()) }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    } else {
      content
    }
  }

  private func actionLabel(_ title: String, _ detail: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(title)
      if !detail.isEmpty { Text(detail).font(.footnote).foregroundStyle(.secondary) }
    }
  }

  @ViewBuilder private func changesetLink(_ id: Int64) -> some View {
    if let url = TaskText.changesetURL(session.osmServer, id) {
      let where_ = session.osmServer == TaskText.devOSM ? " on development OSM" : ""
      Link(String("View changeset \(id)\(where_)"), destination: url)
    }
  }

  // MARK: Confirmations and wording

  private func confirm(_ loaded: LoadedTask, _ form: ChoiceForm, _ action: ChoiceAction) {
    guard let me = session.view.userID, let owner = controller else { return }
    switchNotice = nil
    let element = form.work.element
    let id = loaded.task.id.value
    let title: String, message: String
    switch action {
    case .answers:
      (title, message) = ("Upload these answers?", TaskText.answersSummary(form, taskID: id, me: me, osmServer: session.osmServer))
    case .outcome(let outcome):
      (title, message) = (
        "“\(outcome.label)”?",
        TaskText.outcomeSummary(
          outcome, element: element, taskID: id, me: me, osmServer: session.osmServer,
          notDeletable: form.notDeletable.contains(outcome.id))
      )
    case .skip:
      (title, message) = ("Skip this task?", TaskText.skipSummary(taskID: id, me: me))
    }
    confirmation = Confirmation(
      title: title, message: message, confirm: action.editsOsm ? "Upload" : "Confirm", owner: owner,
      run: { $0.perform(action) })
  }

  private func confirmResend(_ state: TaskScreen.CheckAgain) {
    guard let me = session.view.userID, let owner = controller else { return }
    let id = state.loaded.task.id.value
    let summary: String
    switch state.action {
    case .answers(let answers):
      var form = state.form
      form.answers = answers
      summary = TaskText.answersSummary(form, taskID: id, me: me, osmServer: session.osmServer)
    case .outcome(let outcome):
      summary = TaskText.outcomeSummary(
        outcome, element: state.form.work.element, taskID: id, me: me, osmServer: session.osmServer,
        notDeletable: state.form.notDeletable.contains(outcome.id))
    case .skip: return
    }
    confirmation = Confirmation(
      title: "Send the same submission again?",
      message: "MapRoulette resumes an unfinished submission and never uploads the same edit twice.\n\n\(summary)",
      confirm: "Send again", owner: owner, run: { $0.resendSame() })
  }

  private func doneText(_ d: TaskScreen.Done) -> String {
    var lines: [String] = []
    if d.byOther {
      lines.append("Someone else already completed this task. Your “\(label(d.action))” was not recorded.")
    } else {
      switch d.action {
      case .answers where d.changesetID == nil:
        lines.append("Done. MapRoulette reported no OpenStreetMap changeset for your answers.")
      case .answers: lines.append("Done. Your answers were uploaded to OpenStreetMap.")
      case .outcome(let outcome) where outcome.deletesElement:
        let element = ChoiceWork(d.loaded.task.work()).map { TaskText.element($0.element) } ?? "The element"
        lines.append("Done. \(element) was deleted from OpenStreetMap.")
      default: lines.append("Done. Recorded “\(label(d.action))”.")
      }
    }
    if d.readFailed {
      lines.append("The task could not be re-read to confirm its status.")
    } else {
      lines.append("Current status: \(TaskText.status(d.loaded.task.status?.code)).")
      lines.append(TaskText.completedBy(d.loaded.task.completedBy, me: d.me))
      if let review = TaskText.reviewStatus(d.loaded.task.reviewStatus) { lines.append("\(review).") }
    }
    if let id = d.changesetID { lines.append("OpenStreetMap changeset \(id).") }
    return lines.joined(separator: "\n")
  }

  private func label(_ action: ChoiceAction) -> String {
    switch action {
    case .answers: "Submit answers"
    case .outcome(let outcome): outcome.label
    case .skip: TaskText.skip
    }
  }

  private func explanation(_ form: ChoiceForm, _ action: ChoiceAction) -> String {
    switch action {
    case .answers: ""
    case .outcome(let outcome):
      TaskText.outcomeExplanation(outcome, element: form.work.element, notDeletable: form.notDeletable.contains(outcome.id))
    case .skip: TaskText.skipExplanation
    }
  }
}
