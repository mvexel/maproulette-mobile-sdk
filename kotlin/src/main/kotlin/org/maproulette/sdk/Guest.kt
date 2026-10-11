package org.maproulette.sdk

import java.time.Instant

/** A guest's lifecycle on the backend (`GET mobile-guest/me`). */
enum class GuestState(internal val wire: String) { ACTIVE("active"), CLAIMED("claimed"), EXPIRED("expired") }

/** Whether the guest gave an email: none, a claim link was sent (pending), or a link was used. */
enum class GuestEmailState(internal val wire: String) { NONE("none"), PENDING("pending"), VERIFIED("verified") }

/** The OSM account a guest's answers were claimed by. */
data class ClaimedAccount(val displayName: String, val osmId: Long)

/** A guest's status. The email address itself is never returned. */
data class GuestStatus(
    val guestId: String,
    val state: GuestState,
    val email: GuestEmailState,
    /** Answers still waiting to be published. */
    val pendingCount: Int,
    val publishedCount: Int,
    /** When unclaimed answers are deleted; moves forward with each new answer. */
    val expiresAt: Instant,
    val claimedAs: ClaimedAccount? = null,
)

/** Where a guest's stored answer is. Only [PENDING] can still be withdrawn. */
enum class PendingState(internal val wire: String) {
    PENDING("pending"),
    PUBLISHED("published"),
    SKIPPED_STALE("skipped_stale"),
    SUPERSEDED("superseded"),
    EXPIRED("expired"),
    FAILED("failed"),
}

/** A guest's stored answer for one task. Never an OSM edit until the guest is claimed. */
data class PendingChoice(
    val taskId: TaskId,
    val challengeId: ChallengeId? = null,
    val state: PendingState,
    val answeredAt: Instant,
    /** While pending, other mappers don't get this task until then. */
    val holdUntil: Instant? = null,
    /** Set once published with an OSM edit. */
    val changesetId: Long? = null,
    /** Questions whose answers were not applied because the element changed meanwhile. */
    val droppedQuestionIds: List<String> = emptyList(),
)

/** Where a claim of a guest's answers is (`mobile-claim`). [WAITING]: accepted while the backend's
 * field write switch is off; published once it is turned on. */
enum class ClaimState(internal val wire: String) {
    WAITING("waiting"), QUEUED("queued"), RUNNING("running"), DONE("done"), FAILED("failed"),
}

/** A guest's answers claimed for the signed-in account ([MobileSignIn.claimGuest]). The backend
 * publishes them in the background; follow with [MapRouletteClient.getClaimStatus]. */
data class GuestClaim(
    val claimId: Long,
    val state: ClaimState,
    /** Saved answers the claim took over. */
    val pendingCount: Int,
    /** The account the answers now belong to. */
    val account: ClaimedAccount,
    /** False when this account had already claimed the guest (a repeated request). */
    val isNew: Boolean,
)

/** One claimed answer: its task, where it is, and the changeset once published. */
data class ClaimItem(
    val taskId: TaskId,
    val state: PendingState,
    val changesetId: Long? = null,
    /** Questions whose answers were not applied because the element changed meanwhile. */
    val droppedQuestionIds: List<String> = emptyList(),
)

/** A claim's progress (`GET mobile-claim/{id}`), for the account that claimed. */
data class ClaimStatus(
    val claimId: Long,
    val state: ClaimState,
    val total: Int,
    val published: Int,
    /** Not applied because the element no longer needed the answer. */
    val skipped: Int,
    /** Replaced by a later answer for the same task. */
    val superseded: Int,
    val failed: Int,
    val items: List<ClaimItem>,
)
