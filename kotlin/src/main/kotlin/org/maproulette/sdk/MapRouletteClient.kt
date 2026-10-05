package org.maproulette.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Read-only client. No locks, writes, storage, implicit pagination or automatic retries. */
class MapRouletteClient(
    serviceUrl: String = "https://maproulette.org/api/v2/",
    private val transport: Transport,
    private val apiKey: suspend () -> String? = { null },
) {
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

    suspend fun getCurrentUser(): UserIdentity {
        val body = request("user/whoami")
        return decode {
            val value = body.obj()
            UserIdentity(value.long("id"), requireNotNull(value.bool("guest")))
        }
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
                            method: HttpMethod = HttpMethod.GET, body: String? = null): JsonElement {
        currentCoroutineContext().ensureActive()
        val url = base.newBuilder()
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
        apiKey()?.let {
            require(it.isNotBlank() && '\r' !in it && '\n' !in it)
            headers["apiKey"] = it
        }
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

        if (response.status != 200) {
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
            throw MapRouletteException(kind, response.status, retryAfter)
        }
        return try {
            Json.parseToJsonElement(response.body)
        } catch (_: Exception) {
            throw MapRouletteException(ErrorKind.PROTOCOL)
        }
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
