import Testing
import MapRoulette  // Deliberately not @testable: this file only compiles against the public API.

/// Apps build fakes from these value types (as Kotlin apps do with the data classes).
@Test func choiceValueTypesHavePublicInitializers() {
    let eligibility = ChoiceEligibility(eligible: false, deleteAllowed: false, reason: .keyChanged)
    #expect(eligibility.reason == .keyChanged && !eligibility.eligible)
    let result = ChoiceResult(status: TaskStatus(code: 1), changesetID: 99)
    #expect(result.status.code == 1 && result.changesetID == 99)
}
