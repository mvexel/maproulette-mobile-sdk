package org.maproulette.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** MapRoulette client: reads plus the bare task-lifecycle writes (start, refresh, release, skip and
 * status 1/2/5/6). No storage, implicit pagination or automatic retries; writes are never resent. */
class MapRouletteClient(
    serviceUrl: String = "https://maproulette.org/api/v2/",
    private val transport: Transport,
    private val apiKey: suspend () -> String? = { null },
    private val accessToken: suspend () -> String? = { null },
) {
    /** Retains the original positional/trailing-lambda API-key constructor. */
    constructor(
        serviceUrl: String = "https://maproulette.org/api/v2/",
        transport: Transport,
        apiKey: suspend () -> String?,
    ) : this(serviceUrl, transport, apiKey, { null })

    private val base = serviceUrl.toHttpUrl()
        .also {
            require(
                it.scheme == "https" ||
                    (it.scheme == "http" && it.host in listOf("localhost", "127.0.0.1", "::1")),
            )
            require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
        }
        .newBuilder()
        .apply {
            if (!serviceUrl.endsWith('/')) addPathSegment("")
        }
        .build()
    private val owner = Any()

    suspend fun searchChallenges(
        filter: ChallengeFilter = ChallengeFilter(),
        pageSize: Int = 50,
        after: Continuation? = null,
    ): Page<Challenge> {
        require(filter.tags.all { it.isNotBlank() && ',' !in it })
        val params = linkedMapOf(
            "ct" to filter.tags.joinToString(","),
            "cLocal" to filter.localSurvey.wire.toString(),
            "ca" to filter.includeArchived.toString(),
            "ce" to filter.onlyEnabled.toString(),
            "pe" to filter.onlyEnabled.toString(),
            "sort" to "id",
            "order" to "ASC",
        )
        filter.text?.let { params["cs"] = it }
        return page("challenges/extendedFind", params, pageSize, after, offset = true, parse = ::challenge)
    }

    suspend fun getChallenge(id: ChallengeId): Challenge {
        val body = request("challenge/${id.value}")
        return decode {
            challenge(body).also { require(it.id == id) }
        }
    }

    suspend fun getChallengeTags(id: ChallengeId): List<ChallengeTag> {
        val body = request("challenge/${id.value}/tags")
        return decode {
            body.jsonArray.map {
                ChallengeTag(it.obj().long("id"), it.obj().str("name"))
            }
        }
    }

    suspend fun listTasks(
        id: ChallengeId,
        pageSize: Int = 50,
        after: Continuation? = null,
    ): Page<Task> {
        val result = page(
            "challenge/${id.value}/tasks", linkedMapOf(), pageSize, after,
            offset = false, parse = ::task,
        )
        if (result.items.any { it.challengeId != id }) {
            throw MapRouletteException(ErrorKind.PROTOCOL)
        }
        return result
    }

    suspend fun getTask(id: TaskId): Task {
        val body = request("task/${id.value}")
        return decode {
            task(body).also { require(it.id == id) }
        }
    }

    suspend fun findTasksInBounds(
        filter: TaskFilter,
        pageSize: Int = 50,
        after: Continuation? = null,
    ): Page<TaskSummary> {
        require(filter.statuses == null || (filter.statuses.isNotEmpty() && filter.statuses.all { it >= 0 }))
        val params = linkedMapOf(
            "cLocal" to "1",
            "ca" to filter.includeArchived.toString(),
            "tStatus" to (filter.statuses?.joinToString(",") ?: "-1"),
            "includeTotal" to "true",
            "sort" to "id",
            "order" to "ASC",
        )
        if (filter.challengeIds.isNotEmpty()) {
            params["cid"] = filter.challengeIds.joinToString(",") { it.value.toString() }
        }
        val result = page(
            "tasks/box/${filter.bounds.path()}", params, pageSize, after,
            offset = false, parse = ::summary, envelope = true,
        )
        if (filter.challengeIds.isNotEmpty() && result.items.any { it.challengeId !in filter.challengeIds }) {
            throw MapRouletteException(ErrorKind.PROTOCOL)
        }
        return result
    }

    /** Bounded, unordered map markers. A full result may be truncated; there is no pagination or total.
     * This read-only backend operation uses PUT and includes tasks locked by other users.
     * All-challenge discovery includes only enabled challenges/projects; selected IDs bypass that filter. */
    suspend fun findTaskMarkers(filter: TaskFilter, limit: Int = 100): List<TaskSummary> {
        require(limit in 1..1000)
        require(filter.statuses == null || (filter.statuses.isNotEmpty() && filter.statuses.all { it >= 0 }))
        val params = linkedMapOf(
            "cLocal" to "1", "ca" to filter.includeArchived.toString(),
            "tStatus" to (filter.statuses?.joinToString(",") ?: "-1"),
            "excludeLocked" to "true", "limit" to limit.toString(),
        )
        if (filter.challengeIds.isNotEmpty()) {
            params["cid"] = filter.challengeIds.joinToString(",") { it.value.toString() }
        }
        if (filter.challengeIds.isEmpty()) {
            params["ce"] = "true"
            params["pe"] = "true"
        }
        val response = request("markers/box/${filter.bounds.path()}", params, HttpMethod.PUT, "{}")
        return decode {
            val rows = response.jsonArray
            require(rows.size <= limit)
            val markers = rows.map(::summary)
            require(filter.challengeIds.isEmpty() || markers.all { it.challengeId in filter.challengeIds })
            markers
        }
    }

    /** Locks the task for the caller (`GET task/{id}/start`). A repeat by the owner refreshes the lock.
     * Failures carry [WriteProblem.LockedByOtherUser] (403) or [WriteProblem.AlreadyHoldingTask] (409). */
    suspend fun startTask(id: TaskId): TaskLock = lock(id, "start", Write.START)

    /** Refreshes a held lock. Only for long edit flows; [commitResolution] does not need it. */
    suspend fun refreshTaskLock(id: TaskId): TaskLock = lock(id, "refreshLock", Write.REFRESH)

    /** Releases the caller's lock. The server returns success even when the caller holds no lock, so
     * success does not prove ownership. */
    suspend fun releaseTask(id: TaskId) {
        write(Write.RELEASE, "task/${id.value}/release", HttpMethod.GET)
    }

    /** Skips the task without changing its status and releases the caller's lock if held.
     * Not idempotent (the server counts every skip): after [WriteProblem.OutcomeUnknown], re-read
     * instead of resending. */
    suspend fun skipTask(id: TaskId) {
        write(Write.SKIP, "task/${id.value}/skip", HttpMethod.POST)
    }

    /** Bare status write (`PUT task/{id}/{code}`, no query or body). The server releases the lock.
     * Call it inside a fresh [startTask], or use [commitResolution]. Never resend after
     * [WriteProblem.OutcomeUnknown] without [verifyResolution]. */
    suspend fun resolveTask(id: TaskId, resolution: TaskResolution) {
        write(Write.RESOLVE, "task/${id.value}/${resolution.code}", HttpMethod.PUT)
    }

    /** Late-locking commit: start, then the status write. If the status write fails, the lock is
     * released once (best effort) before the error is rethrown. If start reports a stale lock of
     * the caller's on another task (409), that task is re-read, released when still locked, and
     * start is retried once. Cancellation propagates without cleanup; the server expires locks. */
    suspend fun commitResolution(id: TaskId, resolution: TaskResolution) {
        startForCommit(id)
        try {
            resolveTask(id, resolution)
        } catch (e: MapRouletteException) {
            releaseQuietly(id)
            throw e
        }
    }

    private suspend fun startForCommit(id: TaskId) {
        try {
            startReleasingUnknown(id)
            return
        } catch (e: MapRouletteException) {
            val held = e.problem as? WriteProblem.AlreadyHoldingTask
            if (held == null || held.lockedTaskId == id) throw e
            // Under late locking, a lock held elsewhere comes from an interrupted commit by this user.
            val stale = try {
                getTask(held.lockedTaskId)
            } catch (read: MapRouletteException) {
                if (read.kind != ErrorKind.NOT_FOUND) throw e
                null
            }
            if (stale?.lockedBy != null) {
                try {
                    releaseTask(held.lockedTaskId)
                } catch (_: MapRouletteException) {
                    throw e
                }
            }
        }
        startReleasingUnknown(id)
    }

    // A start with an unknown outcome may hold the lock: release it once before rethrowing.
    private suspend fun startReleasingUnknown(id: TaskId) {
        try {
            startTask(id)
        } catch (e: MapRouletteException) {
            if (e.problem == WriteProblem.OutcomeUnknown) releaseQuietly(id)
            throw e
        }
    }

    private suspend fun releaseQuietly(id: TaskId) {
        try {
            releaseTask(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the server-side lock expiry is the backstop.
        }
    }

    private suspend fun lock(id: TaskId, action: String, operation: Write): TaskLock {
        val response = write(operation, "task/${id.value}/$action", HttpMethod.GET)
        return try {
            val o = Json.parseToJsonElement(response.body).obj()
            val task = task(o)
            require(task.id == id && response.status == 200)
            TaskLock(
                task,
                TaskId(o.long("lockPrimaryTaskId")),
                o["lockBundledTasks"]?.takeUnless { it == JsonNull }?.jsonArray
                    ?.map { TaskId(it.jsonPrimitive.also { p -> require(!p.isString) }.long) }
                    ?: emptyList(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The lock may be held even though the response could not be read.
            throw MapRouletteException(ErrorKind.PROTOCOL, response.status, problem = WriteProblem.OutcomeUnknown)
        }
    }

    private enum class Write { START, REFRESH, RELEASE, SKIP, RESOLVE }

    private suspend fun write(operation: Write, path: String, method: HttpMethod): HttpResponse {
        val response = try {
            send(path, method = method)
        } catch (e: MapRouletteException) {
            if (e.kind != ErrorKind.NETWORK) throw e
            throw MapRouletteException(e.kind, e.status, e.retryAfter, WriteProblem.OutcomeUnknown)
        }
        if (response.status in 200..299) return response
        throw failure(response, writeProblem(operation, response))
    }

    // Bodies are inspected only for the lock/scope details below and never retained.
    private fun writeProblem(operation: Write, response: HttpResponse): WriteProblem? {
        val body = try {
            Json.parseToJsonElement(response.body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        fun field(name: String) = (body?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (response.status) {
            400 -> WriteProblem.InvalidTransition.takeIf {
                operation == Write.RESOLVE && field("error") != "invalid_request"
            }
            403 -> when {
                field("error") == "insufficient_scope" -> WriteProblem.InsufficientScope
                operation == Write.REFRESH -> WriteProblem.LockLost
                (operation == Write.START || operation == Write.RESOLVE) &&
                    field("message")?.contains("locked", ignoreCase = true) == true ->
                    WriteProblem.LockedByOtherUser(field("message"))
                else -> null
            }
            409 -> if (operation == Write.START && body != null) runCatching {
                WriteProblem.AlreadyHoldingTask(
                    TaskId(body.long("lockedTaskId")),
                    body.optionalLong("parentId")?.let(::ChallengeId),
                    body.text("parentName"),
                    body.text("startedAt"),
                )
            }.getOrNull() else null
            in 500..599 -> WriteProblem.OutcomeUnknown
            else -> null
        }
    }

    suspend fun getCurrentUser(): UserIdentity {
        currentCoroutineContext().ensureActive()
        val credentials = readCredentials()
        val bearer = credentials.accessToken != null
        val body = request(
            if (bearer) "oauth/mobile/me" else "user/whoami",
            credentials = credentials, atOriginRoot = bearer,
        )
        return decode {
            val value = body.obj()
            if (bearer) {
                val id = value.long("id")
                require(id > 0 && value.long("osmId") > 0)
                value.str("displayName")
                val scopes = value.str("scope").split(' ').toSet()
                require("tasks:read" in scopes && "" !in scopes)
                UserIdentity(id, false, scopes)
            } else UserIdentity(value.long("id"), requireNotNull(value.bool("guest")))
        }
    }

    // Credential values never enter a data-class diagnostic or persist in the client.
    private class Credentials(val apiKey: String?, val accessToken: String?)

    private suspend fun readCredentials(): Credentials {
        val key = apiKey()
        val token = accessToken()
        require(key == null || token == null) { "Configure one authentication mechanism per request" }
        key?.let { require(it.isNotBlank() && '\r' !in it && '\n' !in it) }
        token?.let { require(it.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) { "Invalid bearer credential" } }
        return Credentials(key, token)
    }

    private suspend fun <T> page(
        path: String,
        params: LinkedHashMap<String, String>,
        size: Int,
        after: Continuation?,
        offset: Boolean,
        parse: (JsonElement) -> T,
        envelope: Boolean = false,
    ): Page<T> {
        require(size in 1..100)

        // Include the filters and page size in the token identity so a continuation
        // cannot accidentally advance another query on the same client.
        val key = base.newBuilder()
            .addPathSegments(path)
            .apply {
                params.forEach { (key, value) -> addQueryParameter(key, value) }
                addQueryParameter("limit", size.toString())
            }
            .build()
            .toString()
        require(after == null || (after.owner === owner && after.key == key)) {
            "Continuation does not belong to this query"
        }
        val position = after?.position ?: 0
        params["limit"] = size.toString()
        params["page"] = position.toString()

        val body = request(path, params)
        return decode {
            val rows = if (envelope) body.obj()["tasks"]!!.jsonArray else body.jsonArray
            val total = if (envelope) body.obj().optionalLong("total") else null
            require(rows.size <= size && (total == null || total >= 0))

            // Challenge search uses a row offset; task endpoints use a page number.
            // A full page indicates another request may be needed, not a known total.
            val next = if (rows.size == size) {
                val increment = if (offset) rows.size else 1
                Continuation(owner, key, Math.addExact(position, increment))
            } else {
                null
            }
            Page(rows.map(parse), next, total)
        }
    }

    private suspend fun request(path: String, params: Map<String, String> = emptyMap(),
                            method: HttpMethod = HttpMethod.GET, body: String? = null,
                            credentials: Credentials? = null, atOriginRoot: Boolean = false): JsonElement {
        val response = send(path, params, method, body, credentials, atOriginRoot)
        if (response.status != 200) throw failure(response)
        return try {
            Json.parseToJsonElement(response.body)
        } catch (_: Exception) {
            throw MapRouletteException(ErrorKind.PROTOCOL)
        }
    }

    private fun failure(response: HttpResponse, problem: WriteProblem? = null): MapRouletteException {
        val kind = when (response.status) {
            401 -> ErrorKind.AUTHENTICATION
            403 -> ErrorKind.PERMISSION
            404 -> ErrorKind.NOT_FOUND
            409 -> ErrorKind.CONFLICT
            429 -> ErrorKind.RATE_LIMIT
            in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.HTTP
        }
        val retryAfter = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", true) }
            ?.value
        return MapRouletteException(kind, response.status, retryAfter, problem)
    }

    private suspend fun send(path: String, params: Map<String, String> = emptyMap(),
                             method: HttpMethod = HttpMethod.GET, body: String? = null,
                             credentials: Credentials? = null, atOriginRoot: Boolean = false): HttpResponse {
        currentCoroutineContext().ensureActive()
        val url = base.newBuilder()
            .apply { if (atOriginRoot) encodedPath("/") }
            .addPathSegments(path)
            .apply {
                params.filterValues { it.isNotEmpty() }.forEach { (key, value) ->
                    addQueryParameter(key, value)
                }
            }
            .build()
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "User-Agent" to "MapRoulette-Mobile-SDK/0.1",
        )
        if (body != null) headers["Content-Type"] = "application/json"
        val authentication = credentials ?: readCredentials()
        authentication.apiKey?.let { headers["apiKey"] = it }
        authentication.accessToken?.let { headers["Authorization"] = "Bearer $it" }
        val response = try {
            transport.execute(HttpRequest(url.toString(), headers, method, body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MapRouletteException) {
            throw e
        } catch (_: Exception) {
            throw MapRouletteException(ErrorKind.NETWORK)
        }
        currentCoroutineContext().ensureActive()
        return response
    }

    // Keep this boundary around response parsing only. Caller validation and
    // credential-provider failures must not be reported as malformed server data.
    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MapRouletteException) {
        throw e
    } catch (_: Exception) {
        throw MapRouletteException(ErrorKind.PROTOCOL)
    }
}

private fun JsonElement.obj() = jsonObject

// Reject coerced primitive types, while preserving missing/null optional fields.
private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString)
    it.content
}

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString)
    it.long
}

private fun JsonObject.optionalLong(key: String): Long? =
    get(key)?.takeUnless { it == JsonNull }?.let {
        require(!it.jsonPrimitive.isString)
        it.jsonPrimitive.long
    }

private fun JsonObject.text(key: String): String? =
    get(key)?.takeUnless { it == JsonNull }?.let { str(key) }

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.let {
        require(!it.isString)
        it.boolean
    }

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeUnless { it == JsonNull }?.jsonObject

private fun challenge(value: JsonElement): Challenge {
    val o = value.obj()
    val parent = o.getValue("parent")
    // Search embeds a project object; direct challenge retrieval supplies its ID.
    val project = if (parent is JsonObject) parent.long("id") else o.long("parent")
    val tags = o["tags"]?.takeUnless { it == JsonNull }?.jsonArray?.map {
        require(it.jsonPrimitive.isString)
        it.jsonPrimitive.content
    }
    return Challenge(
        id = ChallengeId(o.long("id")),
        projectId = ProjectId(project),
        name = o.str("name"),
        instruction = o.text("instruction"),
        description = o.text("description"),
        tags = tags,
        enabled = o.bool("enabled"),
        archived = o.bool("isArchived"),
        requiresLocal = o.bool("requiresLocal"),
        cooperativeType = o.optionalLong("cooperativeType")?.let {
            require(it in Int.MIN_VALUE..Int.MAX_VALUE)
            it.toInt()
        },
    )
}

private fun task(value: JsonElement): Task {
    val o = value.obj()
    val geometry = o.getValue("geometries").jsonObject
    require(geometry.str("type") == "FeatureCollection")
    geometry.getValue("features").jsonArray
    return Task(
        id = TaskId(o.long("id")),
        challengeId = ChallengeId(o.long("parent")),
        name = o.str("name"),
        instruction = o.text("instruction"),
        status = o.optionalLong("status")?.let {
            require(it in Int.MIN_VALUE..Int.MAX_VALUE)
            TaskStatus(it.toInt())
        },
        geometry = geometry,
        location = o.objectOrNull("location"),
        cooperativeWork = o.objectOrNull("cooperativeWork"),
        lockedBy = o.optionalLong("lockedBy"),
        completedBy = o.optionalLong("completedBy"),
        // Serialized as an ISO string; tolerate an epoch number without failing the whole read.
        mappedOn = (o["mappedOn"] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content,
        reviewStatus = o.optionalLong("reviewStatus")?.let {
            require(it in Int.MIN_VALUE..Int.MAX_VALUE)
            it.toInt()
        },
        bundleId = o.optionalLong("bundleId"),
    )
}

private fun summary(value: JsonElement): TaskSummary {
    val o = value.obj()
    return TaskSummary(
        id = TaskId(o.long("id")),
        challengeId = ChallengeId(o.long("parentId")),
        title = o.str("title"),
        status = o.optionalLong("status")?.let {
            require(it in Int.MIN_VALUE..Int.MAX_VALUE)
            TaskStatus(it.toInt())
        },
        point = o.objectOrNull("point"),
    )
}
