import Foundation

/// A guest's lifecycle on the backend (`GET mobile-guest/me`).
public enum GuestState: String, Hashable, Sendable { case active, claimed, expired }
/// Whether the guest gave an email: none, a claim link was sent (pending), or a link was used.
public enum GuestEmailState: String, Hashable, Sendable { case none, pending, verified }

/// The OSM account a guest's answers were claimed by.
public struct ClaimedAccount: Hashable, Sendable {
  public let displayName: String
  public let osmID: Int64
  public init(displayName: String, osmID: Int64) {
    self.displayName = displayName
    self.osmID = osmID
  }
}

/// A guest's status. The email address itself is never returned.
public struct GuestStatus: Hashable, Sendable {
  public let guestID: String
  public let state: GuestState
  public let email: GuestEmailState
  /// Answers still waiting to be published.
  public let pendingCount: Int
  public let publishedCount: Int
  /// When unclaimed answers are deleted; moves forward with each new answer.
  public let expiresAt: Date
  public let claimedAs: ClaimedAccount?
  public init(
    guestID: String, state: GuestState, email: GuestEmailState, pendingCount: Int, publishedCount: Int,
    expiresAt: Date, claimedAs: ClaimedAccount? = nil
  ) {
    self.guestID = guestID
    self.state = state
    self.email = email
    self.pendingCount = pendingCount
    self.publishedCount = publishedCount
    self.expiresAt = expiresAt
    self.claimedAs = claimedAs
  }
}

/// Where a guest's stored answer is. Only `pending` can still be withdrawn.
public enum PendingState: String, Hashable, Sendable {
  case pending, published, skippedStale = "skipped_stale", superseded, expired, failed
}

/// A guest's stored answer for one task. Never an OSM edit until the guest is claimed.
public struct PendingChoice: Hashable, Sendable {
  public let taskID: TaskID
  public let challengeID: ChallengeID?
  public let state: PendingState
  public let answeredAt: Date
  /// While pending, other mappers don't get this task until then.
  public let holdUntil: Date?
  /// Set once published with an OSM edit.
  public let changesetID: Int64?
  /// Questions whose answers were not applied because the element changed meanwhile.
  public let droppedQuestionIDs: [String]
  public init(
    taskID: TaskID, challengeID: ChallengeID? = nil, state: PendingState, answeredAt: Date,
    holdUntil: Date? = nil, changesetID: Int64? = nil, droppedQuestionIDs: [String] = []
  ) {
    self.taskID = taskID
    self.challengeID = challengeID
    self.state = state
    self.answeredAt = answeredAt
    self.holdUntil = holdUntil
    self.changesetID = changesetID
    self.droppedQuestionIDs = droppedQuestionIDs
  }
}
