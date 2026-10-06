import Foundation
import MapRoulette
import Testing

@testable import MapRouletteExample

@MainActor @Suite struct TaskTextTests {
  @Test func statusLabels() {
    #expect(TaskText.status(nil) == "Status unavailable")
    #expect(TaskText.status(2) == "Not an issue")
    #expect(TaskText.status(42) == "Unknown status (42)")
    #expect(TaskText.reviewStatus(-1) == nil)
    #expect(TaskText.reviewStatus(0) == "Review requested")
  }

  @Test func tagChangeAndExplanations() async throws {
    let task = try await makeTask()
    guard case .choice(let element, _, let questions, _) = task.work() else { Issue.record("not choice"); return }
    #expect(TaskText.tagChange(questions[0].options[0]) == "backrest=yes")
    let off = task.choiceOutcomes(allowElementDeletion: false)
    let on = task.choiceOutcomes(allowElementDeletion: true)
    #expect(TaskText.outcomeExplanation(on[1], element: element).hasPrefix("Deletes node/123"))
    #expect(TaskText.outcomeExplanation(off[1], element: element) == "Marks the task “Not an issue”. Does not edit OpenStreetMap.")
    #expect(TaskText.outcomeExplanation(off[1], element: element, notDeletable: true).contains("is not deleted"))
    #expect(TaskText.outcomeExplanation(off[2], element: element).contains("stays open"))
  }

  @Test func answersSummaryListsExactChangesAndCantTell() async throws {
    let task = try await makeTask()
    let work = try #require(ChoiceWork(task.work()))
    var form = TaskWorkController.form(task, work, allowElementDeletion: false, deleteAllowed: true)
    form.answers = ["backrest": "no"]
    let text = TaskText.answersSummary(form, taskID: 42, me: 7, osmServer: TaskText.devOSM)
    #expect(text.contains("• backrest=no"))
    #expect(text.contains("Left as “Can't tell” (not changed):\n• What is the seat mainly made of?"))
    #expect(text.contains("marks task 42 as Fixed for user 7"))
    #expect(text.contains("development OpenStreetMap server (master.apis.dev.openstreetmap.org)"))
  }

  @Test func outcomeSummaries() async throws {
    let task = try await makeTask()
    let gone = try #require(task.choiceOutcomes(allowElementDeletion: true).first { $0.id == "gone" })
    let element = try #require(ChoiceWork(task.work())).element
    #expect(
      TaskText.outcomeSummary(gone, element: element, taskID: 42, me: 7, osmServer: nil)
        .hasPrefix("Delete node/123 from OpenStreetMap"))
    let plain = try #require(TaskWorkController.withoutDeletion(task, gone))
    #expect(
      TaskText.outcomeSummary(plain, element: element, taskID: 42, me: 7, osmServer: nil, notDeletable: true)
        .contains("so it is not deleted"))
  }

  @Test func completedByAndChangesetLinks() {
    #expect(TaskText.completedBy(7, me: 7) == "Completed by you (MapRoulette user 7).")
    #expect(TaskText.completedBy(8, me: 7).contains("not you"))
    #expect(TaskText.completedBy(nil, me: 7).contains("did not report"))
    #expect(TaskText.changesetURL(TaskText.devOSM, 5)?.absoluteString == "https://master.apis.dev.openstreetmap.org/changeset/5")
    #expect(TaskText.changesetURL(nil, 5) == nil)
  }

  @Test func nextTasksWrapAroundAndSkipTheCurrentTask() {
    #expect(TaskText.nextTasks([1, 2, 3, 4], current: 3) == [4, 1, 2])
    #expect(TaskText.nextTasks([1, 2], current: 9) == [1, 2])
    #expect(TaskText.nextTasks([5], current: 5) == [])
  }

  @Test func unavailableReasons() async throws {
    #expect(TaskText.unavailableReason(try await makeTask(bundleID: 3)).contains("bundle"))
    #expect(TaskText.unavailableReason(try await makeTask(payload: nil)).contains("only handles multiple-choice"))
    #expect(TaskText.unavailableReason(try await makeTask(status: 1)) == "This task is fixed. There is nothing left to do.")
  }

  @Test func instructionFallsBackToTheChallenge() async throws {
    let challenge = try await makeChallenge()
    #expect(TaskText.instruction(try await makeTask(), challenge) == "Add backrest")
    #expect(TaskText.instruction(try await makeTask(instruction: "Task {{#mrTaskId}}"), challenge) == "Task 42")
  }
}

@Suite struct SessionRuleTests {
  @Test func writesOnlyForTheAllowlistedStagingOrigin() {
    #expect(AppSession.writesAllowed(origin: "https://mr-api.osm.lol", loopbackAllowed: false))
    #expect(!AppSession.writesAllowed(origin: "https://maproulette.org", loopbackAllowed: false))
    #expect(!AppSession.writesAllowed(origin: "https://mr-api.osm.lol.evil", loopbackAllowed: false))
    #expect(!AppSession.writesAllowed(origin: "http://127.0.0.1:9000", loopbackAllowed: false))
    #expect(AppSession.writesAllowed(origin: "http://127.0.0.1:9000", loopbackAllowed: true))
  }

  @Test func scopes() {
    #expect(AppSession.requestedScope(writesConfigured: true) == "tasks:read tasks:write osm:tagfix")
    #expect(AppSession.requestedScope(writesConfigured: false) == "tasks:read")
    let all = "tasks:read tasks:write osm:tagfix"
    #expect(AppSession.acceptableGrant(["tasks:read"], requested: all))
    #expect(AppSession.acceptableGrant(["tasks:read", "tasks:write", "osm:tagfix"], requested: all))
    #expect(!AppSession.acceptableGrant(["tasks:read", "osm:tagfix"], requested: all))
    #expect(!AppSession.acceptableGrant(["tasks:read", "tasks:write"], requested: "tasks:read"))
    #expect(!AppSession.acceptableGrant(["tasks:write"], requested: all))
    #expect(AppSession.needsReconsent(writesConfigured: true, granted: ["tasks:read", "tasks:write"]))
    #expect(!AppSession.needsReconsent(writesConfigured: true, granted: ["tasks:read", "tasks:write", "osm:tagfix"]))
    #expect(!AppSession.needsReconsent(writesConfigured: false, granted: ["tasks:read"]))
  }

  @Test func taskWritesAreRecognizedWhateverTheMethod() {
    let base = "https://mr-api.osm.lol/api/v2/task/12/"
    for write in ["start", "refreshLock", "release", "skip", "choice", "1", "6"] {
      #expect(AppSession.isTaskWrite(URL(string: base + write)!), "\(write)")
    }
    #expect(!AppSession.isTaskWrite(URL(string: base + "choice/check")!))
    #expect(!AppSession.isTaskWrite(URL(string: "https://mr-api.osm.lol/api/v2/task/12")!))
    #expect(!AppSession.isTaskWrite(URL(string: "https://mr-api.osm.lol/api/v2/challenge/3/tasks?limit=50")!))
  }

  @Test func osmReauthIsDetectedFromTheBody() {
    #expect(AppSession.isOsmReauth(Data(#"{"error":"osm_reauth_required"}"#.utf8)))
    #expect(!AppSession.isOsmReauth(Data(#"{"error":"invalid_token"}"#.utf8)))
    #expect(!AppSession.isOsmReauth(Data("not json".utf8)))
  }

  @Test func pkceChallengeIsS256Base64URL() {
    // printf %s VERIFIER | openssl dgst -sha256 -binary | base64, then base64url without padding.
    #expect(
      AppSession.challenge("dBjftJeZ4CVP-mJ92K9a7xGSaWPMQbCqvrRB3t3G1nE")
        == "C7fJsMKDyV4zukZMpHln8NfheCz-LQxDT-4T-nsjxpw")
  }
}

@Suite struct EndpointTests {
  @Test func originIsValidatedAndNormalized() throws {
    let e = try AuthEndpoints(baseURL: "https://MR-API.osm.lol/", clientID: "maproulette-ios-example", allowLoopback: false)
    #expect(e.origin == "https://mr-api.osm.lol")
    #expect(e.api.absoluteString == "https://mr-api.osm.lol/api/v2/")
    #expect(e.token.absoluteString == "https://mr-api.osm.lol/oauth/mobile/token")
    #expect(throws: AuthEndpoints.InvalidConfiguration.self) {
      try AuthEndpoints(baseURL: "http://mr-api.osm.lol", clientID: "", allowLoopback: false)
    }
    #expect(throws: AuthEndpoints.InvalidConfiguration.self) {
      try AuthEndpoints(baseURL: "https://mr-api.osm.lol/api", clientID: "", allowLoopback: false)
    }
    #expect(throws: AuthEndpoints.InvalidConfiguration.self) {
      try AuthEndpoints(baseURL: "https://user@mr-api.osm.lol", clientID: "", allowLoopback: false)
    }
    #expect(throws: AuthEndpoints.InvalidConfiguration.self) {
      try AuthEndpoints(baseURL: "https://mr-api.osm.lol", clientID: "bad id", allowLoopback: false)
    }
    #expect(throws: AuthEndpoints.InvalidConfiguration.self) {
      try AuthEndpoints(baseURL: "http://127.0.0.1:9000", clientID: "", allowLoopback: false)
    }
    #expect(try AuthEndpoints(baseURL: "http://127.0.0.1:9000", clientID: "", allowLoopback: true).origin == "http://127.0.0.1:9000")
  }

  @Test func connectionsAndCallbacksAreExact() throws {
    let e = try AuthEndpoints(baseURL: "https://mr-api.osm.lol", clientID: "c", allowLoopback: false)
    #expect(e.permitsConnection(URL(string: "https://mr-api.osm.lol/oauth/mobile/token")!))
    #expect(!e.permitsConnection(URL(string: "https://mr-api.osm.lol:444/oauth/mobile/token")!))
    #expect(!e.permitsConnection(URL(string: "https://evil.example/oauth/mobile/token")!))
    #expect(e.acceptsCallback(URL(string: "org.maproulette.example:/oauth2redirect?code=a&state=b")!))
    #expect(!e.acceptsCallback(URL(string: "org.maproulette.example:/other?code=a")!))
    #expect(!e.acceptsCallback(URL(string: "org.maproulette.example:/oauth2redirect#x")!))
    #expect(!e.acceptsCallback(URL(string: "org.evil:/oauth2redirect?code=a")!))
  }

  @Test func markerPoints() {
    #expect(taskPoint(.object(["lat": .number(40.76), "lng": .number(-111.89)]))?.latitude == 40.76)
    #expect(taskPoint(.object(["lat": .integer(1), "lng": .integer(2)]))?.longitude == 2)
    #expect(taskPoint(.object(["lat": .string("40"), "lng": .number(1)])) == nil)
    #expect(taskPoint(.object(["lat": .number(91), "lng": .number(1)])) == nil)
    #expect(taskPoint(nil) == nil)
  }
}
