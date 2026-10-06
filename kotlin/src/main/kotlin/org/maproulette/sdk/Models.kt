package org.maproulette.sdk

import kotlinx.serialization.json.JsonObject

@JvmInline
value class ChallengeId(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
value class TaskId(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
value class ProjectId(val value: Long) {
    init {
        require(value > 0)
    }
}

enum class LocalSurvey(val wire: Int) {
    EXCLUDE(0),
    INCLUDE(1),
    ONLY(2),
}

/** ANY matching tags. These are challenge labels, not OSM key/value tags. */
data class ChallengeFilter(
    val tags: List<String> = emptyList(),
    val text: String? = null,
    val localSurvey: LocalSurvey = LocalSurvey.INCLUDE,
    val includeArchived: Boolean = false,
    val onlyEnabled: Boolean = true,
)

data class Bounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(listOf(west, south, east, north).all { it.isFinite() })
        require(
            west >= -180 && east <= 180 && west < east &&
                south >= -90 && north <= 90 && south < north,
        )
    }

    internal fun path() = "$west/$south/$east/$north"
}

/** Filters task locations. Empty challengeIds means all challenges visible to the caller.
 * null statuses means all statuses; empty statuses is invalid. */
data class TaskFilter(
    val challengeIds: List<ChallengeId> = emptyList(),
    val bounds: Bounds,
    val statuses: List<Int>? = listOf(0, 3, 6),
    val includeArchived: Boolean = false,
)

data class Challenge(
    val id: ChallengeId,
    val projectId: ProjectId,
    val name: String,
    val instruction: String?,
    val description: String?,
    val tags: List<String>?,
    val enabled: Boolean?,
    val archived: Boolean?,
    val requiresLocal: Boolean?,
    /** Raw challenge-level hint: 0 none, 1 tag fix, 2 change file. The task's work decides behavior. */
    val cooperativeType: Int? = null,
)

data class ChallengeTag(val id: Long, val name: String)

data class TaskStatus(val code: Int) {
    val knownName: String?
        get() = names.getOrNull(code)

    companion object {
        private val names = listOf(
            "Created",
            "Fixed",
            "NotAnIssue",
            "Skipped",
            "Deleted",
            "AlreadyFixed",
            "TooHard",
            "Answered",
            "Validated",
            "Disabled",
        )
    }
}

data class Task(
    val id: TaskId,
    val challengeId: ChallengeId,
    val name: String,
    val instruction: String?,
    val status: TaskStatus?,
    val geometry: JsonObject,
    val location: JsonObject?,
    val cooperativeWork: JsonObject?,
    /** Current lock holder; only the single-task read reports it (fresh from the database). */
    val lockedBy: Long? = null,
    val completedBy: Long? = null,
    val mappedOn: String? = null,
    val reviewStatus: Int? = null,
    val bundleId: Long? = null,
)

data class TaskSummary(
    val id: TaskId,
    val challengeId: ChallengeId,
    val title: String,
    val status: TaskStatus?,
    val point: JsonObject?,
)

/** [scopes] is the bearer grant's scope set, or null for API-key and anonymous identities. */
data class UserIdentity(val id: Long, val guest: Boolean, val scopes: Set<String>? = null) {
    /** Bearer grants need `tasks:write`; an API key acts with the user's full authority. */
    val canWriteTasks: Boolean
        get() = scopes?.contains("tasks:write") ?: !guest
}

/** In-memory token bound to one client, operation, filter and page size. */
class Continuation internal constructor(
    internal val owner: Any,
    internal val key: String,
    internal val position: Int,
)

data class Page<T>(
    val items: List<T>,
    val next: Continuation?,
    val total: Long? = null,
)

enum class ErrorKind {
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMIT,
    SERVER,
    HTTP,
    NETWORK,
    PROTOCOL,
}

/** The only statuses the SDK writes. Deleted, Disabled and Skipped-by-status are deliberately absent. */
enum class TaskResolution(val code: Int) {
    FIXED(1),
    NOT_AN_ISSUE(2),
    ALREADY_FIXED(5),
    TOO_HARD(6),
}

/** A held edit lock. [bundledTaskIds] is non-empty when the lock covers a task bundle. */
data class TaskLock(val task: Task, val primaryTaskId: TaskId, val bundledTaskIds: List<TaskId>)

/** Lifecycle detail attached to write failures. Never contains credentials or raw bodies. */
sealed interface WriteProblem {
    /** 403: another user holds the lock. [message] is the server's text and may name that user. */
    data class LockedByOtherUser(val message: String?) : WriteProblem

    /** 409: the caller already holds a lock on a different task. */
    data class AlreadyHoldingTask(
        val lockedTaskId: TaskId,
        val challengeId: ChallengeId?,
        val challengeName: String?,
        val startedAt: String?,
    ) : WriteProblem

    /** 403 on refresh: the caller no longer owns the lock (expired, released or taken). */
    data object LockLost : WriteProblem

    /** 400 on a status write: already completed by someone else, invalid status or paused challenge. */
    data object InvalidTransition : WriteProblem

    /** 403 insufficient_scope: the bearer grant lacks `tasks:write`; sign in again to grant it. */
    data object InsufficientScope : WriteProblem

    /** The request may have been applied (network failure, 5xx or unreadable success). Re-read the
     * task before acting; never resend a skip or status write blindly. */
    data object OutcomeUnknown : WriteProblem
}

/** Interpretation of a fresh task read after an interrupted status write (see [verifyResolution]). */
enum class ResolutionCheck {
    /** The target status is recorded and the lock is gone. Do not resend. */
    APPLIED,

    /** Not applied and the caller still holds the lock: resending is safe. */
    NOT_APPLIED_LOCK_HELD,

    /** Not applied and unlocked: start again, then resend. */
    NOT_APPLIED_UNLOCKED,

    /** Another user holds the lock. Do not resend. */
    LOCKED_BY_OTHER,

    /** Another user completed the task, or it has another final status. Stop. */
    RESOLVED_BY_OTHER,
}

/** Server bodies and credentials are deliberately excluded from error descriptions. */
class MapRouletteException(
    val kind: ErrorKind,
    val status: Int? = null,
    val retryAfter: String? = null,
    val problem: WriteProblem? = null,
) : Exception(
    "MapRoulette ${kind.name.lowercase()}" + (status?.let { " (HTTP $it)" } ?: ""),
)
