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

/** Filters task locations. null statuses means all statuses; empty is invalid. */
data class TaskFilter(
    val challengeIds: List<ChallengeId>,
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
)

data class TaskSummary(
    val id: TaskId,
    val challengeId: ChallengeId,
    val title: String,
    val status: TaskStatus?,
    val point: JsonObject?,
)

data class UserIdentity(val id: Long, val guest: Boolean)

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

/** Server bodies and credentials are deliberately excluded from error descriptions. */
class MapRouletteException(
    val kind: ErrorKind,
    val status: Int? = null,
    val retryAfter: String? = null,
) : Exception(
    "MapRoulette ${kind.name.lowercase()}" + (status?.let { " (HTTP $it)" } ?: ""),
)
