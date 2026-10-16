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

/// One campaign's share of a guest's answers: still pending, and on the map (OSM carries the
/// answer's tag, whoever added it).
public struct ChallengeAnswerCounts: Hashable, Sendable {
  public let pending: Int
  public let onMap: Int
  public static let zero = ChallengeAnswerCounts(pending: 0, onMap: 0)
  public init(pending: Int, onMap: Int) {
    self.pending = pending
    self.onMap = onMap
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
  /// Per campaign; campaigns without answers are absent (use `answers(in:)`).
  public let byChallenge: [ChallengeID: ChallengeAnswerCounts]
  public init(
    guestID: String, state: GuestState, email: GuestEmailState, pendingCount: Int, publishedCount: Int,
    expiresAt: Date, claimedAs: ClaimedAccount? = nil, byChallenge: [ChallengeID: ChallengeAnswerCounts] = [:]
  ) {
    self.guestID = guestID
    self.state = state
    self.email = email
    self.pendingCount = pendingCount
    self.publishedCount = publishedCount
    self.expiresAt = expiresAt
    self.claimedAs = claimedAs
    self.byChallenge = byChallenge
  }
  /// The guest's answers in one campaign; zero when it has none.
  public func answers(in challenge: ChallengeID) -> ChallengeAnswerCounts { byChallenge[challenge] ?? .zero }
}

/// Where a guest's stored answer is. Only `pending` can still be withdrawn.
public enum PendingState: String, Hashable, Sendable {
  case pending, published, skippedStale = "skipped_stale", superseded, expired, failed
  /// A local mapper added it to OSM (the server saw the tag there).
  case onMap = "on_map"
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

/// Where a claim is. The claim only records; a background job publishes the answers to OSM.
/// `waiting` means publishing is switched off on the backend for now.
public enum ClaimState: String, Hashable, Sendable { case waiting, queued, running, done, failed }

/// What `MobileSignIn.claimGuest()` started: the guest's saved answers, now the account's.
public struct GuestClaim: Hashable, Sendable {
  public let claimID: Int64
  public let state: ClaimState
  /// Saved answers the claim took over.
  public let pendingCount: Int
  public let account: ClaimedAccount
  /// False when this account had already claimed the guest (a repeated request).
  public let isNew: Bool
  public init(claimID: Int64, state: ClaimState, pendingCount: Int, account: ClaimedAccount, isNew: Bool) {
    self.claimID = claimID
    self.state = state
    self.pendingCount = pendingCount
    self.account = account
    self.isNew = isNew
  }
}

/// One answer of a claim.
public struct ClaimItem: Hashable, Sendable {
  public let taskID: TaskID
  public let state: PendingState
  public let changesetID: Int64?
  /// Questions whose answers were not applied because the element changed meanwhile.
  public let droppedQuestionIDs: [String]
  public init(taskID: TaskID, state: PendingState, changesetID: Int64? = nil, droppedQuestionIDs: [String] = []) {
    self.taskID = taskID
    self.state = state
    self.changesetID = changesetID
    self.droppedQuestionIDs = droppedQuestionIDs
  }
}

/// A claim's progress (`GET mobile-claim/{id}`), for the account that made it.
public struct ClaimStatus: Hashable, Sendable {
  public let claimID: Int64
  public let state: ClaimState
  public let total: Int
  public let published: Int
  /// Not applied because the element changed meanwhile (`skipped_stale`).
  public let skipped: Int
  public let superseded: Int
  public let failed: Int
  public let items: [ClaimItem]
  public init(
    claimID: Int64, state: ClaimState, total: Int, published: Int, skipped: Int, superseded: Int, failed: Int,
    items: [ClaimItem]
  ) {
    self.claimID = claimID
    self.state = state
    self.total = total
    self.published = published
    self.skipped = skipped
    self.superseded = superseded
    self.failed = failed
    self.items = items
  }
}
