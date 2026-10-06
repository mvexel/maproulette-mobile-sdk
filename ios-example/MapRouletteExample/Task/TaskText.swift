import Foundation
import MapRoulette

/// User-facing wording for choice tasks (docs/design/mobile-choice-challenges.md §7). Matches the Android demo.
enum TaskText {
  static let skip = "Skip"
  static let skipExplanation = "Leave it for someone else. The task stays open."
  static let cantTell = "Can't tell"
  static let noLongerNeeded = "This one no longer needs answering."
  static let devOSM = "https://master.apis.dev.openstreetmap.org"

  static func status(_ code: Int?) -> String {
    switch code {
    case nil: "Status unavailable"
    case 0: "Created"
    case 1: "Fixed"
    case 2: "Not an issue"
    case 3: "Skipped"
    case 4: "Deleted"
    case 5: "Already fixed"
    case 6: "Too hard"
    case 7: "Answered"
    case 8: "Validated"
    case 9: "Disabled"
    case let code?: "Unknown status (\(code))"
    }
  }

  static func reviewStatus(_ code: Int?) -> String? {
    switch code {
    case nil, -1: nil
    case 0: "Review requested"
    case 1: "Review approved"
    case 2: "Review rejected"
    case 3: "Review assisted"
    case 4: "Review disputed"
    case 5: "Review unnecessary"
    case 6: "Approved with revisions"
    case 7: "Approved with fixes after revisions"
    case let code?: "Review status \(code)"
    }
  }

  /// Instruction text with `{{property}}` placeholders filled in; markdown is shown as plain text.
  static func instruction(_ task: MapRouletteTask, _ challenge: Challenge) -> String {
    let instruction = task.resolvedInstruction(challenge: challenge)
    let text = instruction.render(task.templateProperties()).trimmingCharacters(in: .whitespacesAndNewlines)
    let fields = instruction.formFields.map { field in
      switch field {
      case .checkbox(_, let label): "• \(label)"
      case .select(_, let label, let values): "• \(label): \(values.joined(separator: " / "))"
      }
    }
    let questions =
      fields.isEmpty
      ? "" : "\n\nThe website asks these questions; the app does not submit answers:\n" + fields.joined(separator: "\n")
    return (text.isEmpty ? "No instructions." : text) + questions
  }

  /// Why a task is "Not available on mobile".
  static func unavailableReason(_ task: MapRouletteTask) -> String {
    if task.bundleID != nil { return "This task is part of a bundle. Complete it on the MapRoulette website." }
    guard case .choice = task.work() else {
      return "The app only handles multiple-choice tasks. Complete this one on the MapRoulette website."
    }
    return "This task is \(status(task.status?.code).lowercased()). There is nothing left to do."
  }

  static func element(_ ref: OSMElementRef) -> String { "\(ref.type.rawValue)/\(ref.id)" }

  /// The exact tag change of an option, as shown below its label: `backrest=yes`, `remove note`.
  static func tagChange(_ option: ChoiceOption) -> String {
    (option.setTags.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
      + option.unsetTags.map { "remove \($0)" }).joined(separator: ", ")
  }

  /// What an outcome records, shown on its button.
  static func outcomeExplanation(_ outcome: ChoiceOutcome, element ref: OSMElementRef, notDeletable: Bool = false)
    -> String
  {
    if notDeletable {
      return "\(element(ref)) is part of a way or relation, so it is not deleted. Marks the task “\(status(outcome.resolution.rawValue))”."
    }
    if outcome.deletesElement { return "Deletes \(element(ref)) from OpenStreetMap and marks the task Fixed." }
    if outcome.resolution == .tooHard { return "You can't answer it. It stays open for other mappers." }
    return "Marks the task “\(status(outcome.resolution.rawValue))”. Does not edit OpenStreetMap."
  }

  /// Which OSM server this build edits; nil when the app does not know it.
  static func osmNotice(_ osmServer: String?) -> String {
    switch osmServer {
    case nil: "The test backend decides which OpenStreetMap server is edited."
    case devOSM?: "Staging build: this edits the development OpenStreetMap server (\(host(devOSM))), not the real map."
    case let server?: "This edits OpenStreetMap at \(host(server))."
    }
  }

  /// Confirmation for answers: the exact tag changes that will be uploaded.
  static func answersSummary(_ form: ChoiceForm, taskID: Int64, me: Int64, osmServer: String?) -> String {
    var text = "Upload these tag changes to \(element(form.work.element)) in OpenStreetMap:"
    for question in form.work.questions {
      guard let option = question.options.first(where: { $0.id == form.answers[question.id] }) else { continue }
      text += "\n• \(tagChange(option))"
    }
    let skipped = form.work.questions.filter { form.answers[$0.id] == nil }
    if !skipped.isEmpty {
      text += "\n\nLeft as “\(cantTell)” (not changed):"
      for question in skipped { text += "\n• \(question.prompt)" }
    }
    text += "\n\nThe changes are uploaded in one changeset as your OpenStreetMap account. "
    text += "MapRoulette then marks task \(taskID) as Fixed for user \(me)."
    text += "\n\n\(osmNotice(osmServer))"
    return text
  }

  /// Confirmation for a task-level outcome.
  static func outcomeSummary(
    _ outcome: ChoiceOutcome, element ref: OSMElementRef, taskID: Int64, me: Int64, osmServer: String?,
    notDeletable: Bool = false
  ) -> String {
    let label = status(outcome.resolution.rawValue)
    if notDeletable {
      return "\(element(ref)) is part of a way or relation in OpenStreetMap, so it is not deleted. Instead, mark task \(taskID) "
        + "as “\(label)” in MapRoulette as user \(me). It does not edit OpenStreetMap."
    }
    if outcome.deletesElement {
      return "Delete \(element(ref)) from OpenStreetMap as your OpenStreetMap account, then mark task \(taskID) as Fixed "
        + "for MapRoulette user \(me).\n\n\(osmNotice(osmServer))"
    }
    return "Mark task \(taskID) as “\(label)” in MapRoulette as user \(me). It does not edit OpenStreetMap."
  }

  static func skipSummary(taskID: Int64, me: Int64) -> String {
    "\(skipExplanation)\n\nThis records a skip for task \(taskID) in MapRoulette as user \(me). It does not edit OpenStreetMap."
  }

  /// Web link to an OSM changeset, or nil when the server is unknown.
  static func changesetURL(_ osmServer: String?, _ changesetID: Int64) -> URL? {
    osmServer.flatMap { URL(string: "\($0)/changeset/\(changesetID)") }
  }

  /// "Completed by" line after a resolution, compared with the signed-in user.
  static func completedBy(_ completedBy: Int64?, me: Int64) -> String {
    switch completedBy {
    case nil: "MapRoulette did not report who completed it."
    case me?: "Completed by you (MapRoulette user \(me))."
    case let other?: "Completed by MapRoulette user \(other), not you (you are user \(me))."
    }
  }

  /// Tasks to offer as "Next task": those after `current` in the list order, then those before it.
  static func nextTasks(_ ids: [Int64], current: Int64) -> [Int64] {
    guard let at = ids.firstIndex(of: current) else { return ids.filter { $0 != current } }
    return Array(ids[(at + 1)...] + ids[..<at])
  }

  static func host(_ url: String) -> String { URLComponents(string: url)?.host ?? url }
}
