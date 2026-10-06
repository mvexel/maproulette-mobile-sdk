import XCTest

/// Drives the main flows against the in-process `-demo` backend (no network writes) and saves
/// screenshots to $SHOTS_DIR (pass `TEST_RUNNER_SHOTS_DIR=…` to xcodebuild). Run with the
/// Screenshots scheme. `testLiveReadOnly` additionally needs a staging build and LIVE=1.
final class ScreenshotTests: XCTestCase {
  private var app: XCUIApplication!

  override func setUp() {
    continueAfterFailure = false
  }

  private func launch(_ arguments: [String]) {
    app = XCUIApplication()
    app.launchArguments = arguments
    app.launch()
  }

  private func shot(_ name: String) {
    let png = XCUIScreen.main.screenshot().pngRepresentation
    let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
    if let dir = ProcessInfo.processInfo.environment["SHOTS_DIR"] {
      try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }
  }

  private func text(_ fragment: String) -> XCUIElement {
    app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
  }
  private func button(_ fragment: String) -> XCUIElement {
    app.buttons.containing(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
  }
  private func wait(_ element: XCUIElement, _ timeout: TimeInterval = 15) {
    XCTAssertTrue(element.waitForExistence(timeout: timeout), "missing \(element)")
  }
  private func scrollTo(_ element: XCUIElement) {
    for _ in 0..<8 where !element.isHittable { app.swipeUp() }
  }

  private func enableDeletion() {
    let toggle = app.switches.firstMatch
    wait(toggle)
    for _ in 0..<3 where (toggle.value as? String) != "1" {
      toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }
    XCTAssertEqual(toggle.value as? String, "1", "deletion setting did not turn on")
  }

  func testHomeAndAnswerFlow() {
    launch(["-demo"])
    wait(text("Signed in as MapRoulette user 7"))
    shot("01-home-signed-in")
    button("Load challenge").tap()
    wait(text("Loaded 5 tasks"))
    scrollTo(button("Bench node/1188"))
    shot("02-challenge-task-list")
    button("Bench node/1184").tap()
    wait(text("Does the bench have a backrest?"))
    shot("03-task-answering")
    button("backrest=yes").tap()
    button("material=wood").tap()
    scrollTo(button("Submit answers"))
    shot("04-answers-selected")
    button("Submit answers").tap()
    wait(app.alerts.firstMatch)
    shot("05-confirm-upload")
    app.alerts.buttons["Upload"].tap()
    wait(text("Done. Your answers were uploaded"))
    shot("06-done-with-changeset")
  }

  func testNoLongerNeededAndCheckFailed() {
    launch(["-demo", "-demoTask", "186"])
    wait(text("no longer needs answering"))
    shot("07-no-longer-needed")
    button("Next task").tap()
    wait(text("Couldn't check this right now"))
    shot("08-check-failed-retry-next")
  }

  func testOutcomesAndDeletion() {
    launch(["-demo"])
    wait(text("Signed in as MapRoulette user 7"))
    enableDeletion()
    shot("09-deletion-setting-on")
    button("Load challenge").tap()
    wait(text("Loaded 5 tasks"))
    scrollTo(button("Bench node/1185"))
    button("Bench node/1185").tap()
    wait(text("Does the bench have a backrest?"))
    // 185: the node is in a way, so "gone" is offered without deletion.
    scrollTo(button("Bench is gone"))
    shot("10-outcomes-gone-not-deletable")
    button("Bench is gone").tap()
    wait(app.alerts.firstMatch)
    shot("10b-confirm-gone-not-deletable")
    app.alerts.buttons["Cancel"].tap()
  }

  func testElementInUse() {
    launch(["-demo"])
    wait(text("Signed in as MapRoulette user 7"))
    enableDeletion()
    button("Load challenge").tap()
    wait(text("Loaded 5 tasks"))
    scrollTo(button("Bench node/1188"))
    button("Bench node/1188").tap()
    wait(text("Someone is working on this task"))
    shot("11-locked-by-other-banner")
    scrollTo(button("Bench is gone"))
    button("Bench is gone").tap()
    wait(app.alerts.firstMatch)
    shot("12-confirm-delete")
    app.alerts.buttons["Upload"].tap()
    wait(button("without deleting"))
    shot("13-element-in-use")
    button("without deleting").tap()
    wait(app.alerts.firstMatch)
    app.alerts.buttons["Confirm"].tap()
    wait(text("Done. Recorded"))
    shot("14-done-without-deleting")
  }

  func testMap() {
    launch(["-demo", "-demoMap"])
    wait(app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] %@", "tap Search this area")).firstMatch)
    button("Search this area").tap()
    wait(text("tasks shown"))
    sleep(3)  // Map tiles.
    shot("15-map-markers")
  }

  /// Anonymous reads from the staging backend (build with MAPROULETTE_BASE_URL=https://mr-api.osm.lol).
  func testLiveReadOnly() throws {
    try XCTSkipUnless(ProcessInfo.processInfo.environment["LIVE"] == "1", "LIVE=1 and a staging build only")
    launch([])
    wait(button("Load challenge"))
    let field = app.textFields.firstMatch
    field.tap()
    field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 12) + "3")
    button("Load challenge").tap()
    wait(text("Tap a task"), 30)
    shot("20-staging-challenge-3")
    let first = app.buttons.containing(NSPredicate(format: "label CONTAINS 'Task '")).element(boundBy: 0)
    scrollTo(first)
    first.tap()
    wait(text("Challenge 3:"), 30)
    shot("21-staging-task-preview")
  }
}
