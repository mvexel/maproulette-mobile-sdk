import Foundation
import MapRoulette

@testable import MapRouletteExample

let me: Int64 = 7
let other: Int64 = 8
let taskID = try! TaskID(42)

/// The pilot bench payload (docs/mobile-choice-challenges.md §3), shortened to two questions.
nonisolated(unsafe) let bench: [String: Any] = try! JSONSerialization.jsonObject(
  with: Data(
    """
    {"meta":{"version":2,"type":3,"choiceVersion":1},"element":"node/123","match":{"amenity":"bench"},
     "questions":[
      {"id":"backrest","prompt":"Does the bench have a backrest?","expect":{"backrest":null},
       "options":[{"id":"yes","label":"Yes","setTags":{"backrest":"yes"}},{"id":"no","label":"No","setTags":{"backrest":"no"}}]},
      {"id":"material","prompt":"What is the seat mainly made of?","expect":{"material":null},
       "options":[{"id":"wood","label":"Wood","setTags":{"material":"wood"}},{"id":"metal","label":"Metal","setTags":{"material":"metal"}}]}],
     "outcomes":[{"id":"not-a-bench","label":"Not a bench","status":2},{"id":"gone","label":"Bench is gone","delete":true}]}
    """.utf8)) as! [String: Any]

/// Serves one fixed body to every request.
struct Fixed: Transport {
  let body: Data
  func execute(_ request: HTTPRequest) async throws -> HTTPResponse { HTTPResponse(status: 200, body: body) }
}

private func decodingClient(_ value: Any) throws -> MapRouletteClient {
  try MapRouletteClient(
    transport: Fixed(body: JSONSerialization.data(withJSONObject: value)), accessToken: { nil })
}

/// A task decoded by the SDK (the model has no public initializer).
func makeTask(
  status: Int = 0, lockedBy: Int64? = nil, completedBy: Int64? = nil, payload: [String: Any]? = bench,
  bundleID: Int64? = nil, instruction: String? = nil, changesetID: Int64? = nil
) async throws -> MapRouletteTask {
  var o: [String: Any] = [
    "id": 42, "parent": 1, "name": "Bench", "status": status,
    "geometries": ["type": "FeatureCollection", "features": [Any]()],
  ]
  o["cooperativeWork"] = payload
  o["lockedBy"] = lockedBy
  o["completedBy"] = completedBy
  o["bundleId"] = bundleID
  o["instruction"] = instruction
  o["changesetId"] = changesetID
  return try await decodingClient(o).getTask(taskID)
}

func makeChallenge() async throws -> Challenge {
  try await decodingClient(["id": 1, "parent": 2, "name": "Benches", "instruction": "Add backrest"])
    .getChallenge(ChallengeID(1))
}

func failure(_ kind: ErrorKind, _ problem: WriteProblem? = nil) -> MapRouletteError {
  MapRouletteError(kind, problem: problem)
}

enum Read {
  case task(MapRouletteTask)
  case error(any Error)
}

/// Records every call; each operation's behavior is replaceable per test. No network.
@MainActor final class FakeOps: TaskOps {
  nonisolated let allowElementDeletion: Bool
  let challenge: Challenge
  var calls: [String] = []
  var submissions: [ChoiceSubmission] = []
  var reads: [Read] = []
  var check: () throws -> ChoiceEligibility = { ChoiceEligibility(eligible: true, deleteAllowed: true, reason: nil) }
  var submit: (ChoiceSubmission) async throws -> ChoiceResult = { _ in ChoiceResult(status: TaskStatus(code: 1), changesetID: 99) }
  var skip: () throws -> Void = {}

  init(allowElementDeletion: Bool = false, challenge: Challenge) {
    self.allowElementDeletion = allowElementDeletion
    self.challenge = challenge
  }

  @discardableResult func thenRead(_ next: MapRouletteTask...) -> FakeOps {
    reads += next.map(Read.task)
    return self
  }

  func getTask(_ id: TaskID) async throws -> MapRouletteTask {
    calls.append("get")
    guard !reads.isEmpty else { throw failure(.protocolFailure) }
    switch reads.removeFirst() {
    case .task(let t): return t
    case .error(let e): throw e
    }
  }
  func getChallenge(_ task: MapRouletteTask) async throws -> Challenge {
    calls.append("challenge")
    return challenge
  }
  func checkChoice(_ id: TaskID) async throws -> ChoiceEligibility {
    calls.append("check")
    return try check()
  }
  func submitChoice(_ task: MapRouletteTask, _ submission: ChoiceSubmission) async throws -> ChoiceResult {
    calls.append("submit")
    submissions.append(submission)
    return try await submit(submission)
  }
  func skipTask(_ id: TaskID) async throws {
    calls.append("skip")
    try skip()
  }
}

extension TaskScreen {
  var answering: (LoadedTask, ChoiceForm, String?)? {
    if case .answering(let l, let f, let n) = self { return (l, f, n) }
    return nil
  }
  var done: Done? {
    if case .done(let d) = self { return d }
    return nil
  }
  var checkAgain: CheckAgain? {
    if case .checkAgain(let c) = self { return c }
    return nil
  }
  var name: String { String(describing: self).components(separatedBy: "(").first ?? "" }
}
