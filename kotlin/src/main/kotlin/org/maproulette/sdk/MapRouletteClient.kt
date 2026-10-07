package org.maproulette.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** MapRoulette client: reads, [skipTask] and choice submission, plus the low-level task-lifecycle
 * writes (start, refresh, release and status 1/2/5/6) behind [LowLevelTaskLifecycle]. No storage,
 * implicit pagination or automatic retries; writes are never resent, except the identical choice
 * submission that [submitChoice] documents. Writes go only to environments whose
 * [MapRouletteEnvironment.allowsWrites] is true; others fail with IllegalStateException before sending.
 *
 * Credential providers run before every request, so a rotated or signed-out credential takes effect
 * immediately. Supply at most one: a request fails with IllegalArgumentException when both return a
 * value. Pass them by name; a trailing lambda is the API key.
 *
 * @param transport the HTTP transport, e.g. an [OkHttpTransport]; the caller closes it.
 * @param allowElementDeletion whether choice "gone" outcomes may delete the OSM element (default
 *   false). This is a cap: when false, no delete is ever sent and "gone" resolves to Not an issue. When
 *   true, use [choiceOutcomes] and, per task, [ChoiceOutcome.withoutDeletion] to submit "gone" without
 *   deleting. */
class MapRouletteClient(
    /** The deployment this client talks to. */
    val environment: MapRouletteEnvironment = MapRouletteEnvironment.PRODUCTION,
    private val transport: Transport,
    val allowElementDeletion: Boolean = false,
    private val accessToken: suspend () -> String? = { null },
    private val apiKey: suspend () -> String? = { null },
) {
    // MapRouletteEnvironment validated the URL and ensured the trailing slash.
    private val base = environment.serviceUrl.toHttpUrl()
    private val owner = Any()

    /** The task's kind, decoded with this client's [allowElementDeletion]. */
    fun work(task: Task): TaskWork = task.work(allowElementDeletion)

    /** The task's declared outcomes plus the built-in Too hard, decoded with this client's
     * [allowElementDeletion]; empty when the task is not a valid choice task. Every outcome, and its
     * [ChoiceOutcome.withoutDeletion] form, can be passed to [submitChoice]. */
    fun choiceOutcomes(task: Task): List<ChoiceOutcome> = task.choiceOutcomes(allowElementDeletion)

    /** Searches challenges, ordered by id. Anonymous reads work. [pageSize] must be 1..100; pass
     * `page.next` as [after] for the next page, with the same filter and page size. */
    suspend fun searchChallenges(
        filter: ChallengeFilter = ChallengeFilter(),
        pageSize: Int = 50,
        after: PageCursor? = null,
    ): Page<Challenge> {
        require(filter.tags.all { it.isNotBlank() && ',' !in it }) { "challenge tags must be non-blank and contain no comma" }
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

    /** Reads one challenge. NOT_FOUND when it does not exist or is not visible to the caller. */
    suspend fun getChallenge(id: ChallengeId): Challenge {
        val body = request("challenge/${id.value}")
        return decode {
            challenge(body).also { require(it.id == id) }
        }
    }

    /** Reads a challenge's labels (not OSM tags). */
    suspend fun getChallengeTags(id: ChallengeId): List<ChallengeTag> {
        val body = request("challenge/${id.value}/tags")
        return decode {
            body.jsonArray.map {
                ChallengeTag(it.obj().long("id"), it.obj().str("name"))
            }
        }
    }

    /** Lists a challenge's tasks in server order. [pageSize] must be 1..100; continue with `next`. */
    suspend fun listTasks(
        id: ChallengeId,
        pageSize: Int = 50,
        after: PageCursor? = null,
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

    /** Reads one task, including its lock holder and completion fields. */
    suspend fun getTask(id: TaskId): Task {
        val body = request("task/${id.value}")
        return decode {
            task(body).also { require(it.id == id) }
        }
    }

    /** Tasks whose location is inside the filter's bounds, ordered by id, with `total`. [pageSize] must
     * be 1..100; continue with `next`. */
    suspend fun findTasksInBounds(
        filter: TaskFilter,
        pageSize: Int = 50,
        after: PageCursor? = null,
    ): Page<TaskSummary> {
        require(filter.statuses == null || (filter.statuses.isNotEmpty() && filter.statuses.all { it >= 0 })) {
            STATUSES_REASON
        }
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
        if (filter.choiceOnly) params += choiceOnlyParams
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
        require(limit in 1..1000) { "limit must be 1..1000" }
        require(filter.statuses == null || (filter.statuses.isNotEmpty() && filter.statuses.all { it >= 0 })) {
            STATUSES_REASON
        }
        val params = linkedMapOf(
            "cLocal" to "1", "ca" to filter.includeArchived.toString(),
            "tStatus" to (filter.statuses?.joinToString(",") ?: "-1"),
            "excludeLocked" to "true", "limit" to limit.toString(),
        )
        if (filter.challengeIds.isNotEmpty()) {
            params["cid"] = filter.challengeIds.joinToString(",") { it.value.toString() }
        }
        if (filter.choiceOnly) params += choiceOnlyParams
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

    // Low-level lifecycle writes. Mobile apps complete tasks with submitChoice and skipTask; these
    // exist for tooling and future flows, behind @OptIn(LowLevelTaskLifecycle::class).

    /** Locks the task for the caller (`GET task/{id}/start`). A repeat by the owner refreshes the lock.
     * Failures carry [WriteProblem.LockedByOtherUser] (403) or [WriteProblem.AlreadyHoldingTask] (409). */
    @LowLevelTaskLifecycle
    suspend fun startTask(id: TaskId): TaskLock = lock(id, "start", Write.START)

    /** Refreshes a held lock. Only for long edit flows; [commitResolution] does not need it. */
    @LowLevelTaskLifecycle
    suspend fun refreshTaskLock(id: TaskId): TaskLock = lock(id, "refreshLock", Write.REFRESH)

    /** Releases the caller's lock. The server returns success even when the caller holds no lock, so
     * success does not prove ownership. */
    @LowLevelTaskLifecycle
    suspend fun releaseTask(id: TaskId) {
        write(Write.RELEASE, "task/${id.value}/release", HttpMethod.GET)
    }

    /** Supported mobile path, with [submitChoice]. Offer it only when `task.canSkip()`.
     * Skips the task without changing its status and releases the caller's lock if held.
     * Not idempotent (the server counts every skip): after [WriteProblem.OutcomeUnknown], re-read
     * instead of resending. */
    suspend fun skipTask(id: TaskId) {
        write(Write.SKIP, "task/${id.value}/skip", HttpMethod.POST)
    }

    /** Bare status write (`PUT task/{id}/{code}`, no query or body). The server releases the lock.
     * Call it inside a fresh [startTask], or use [commitResolution]. Never resend after
     * [WriteProblem.OutcomeUnknown] without [verifyResolution]. */
    @LowLevelTaskLifecycle
    suspend fun resolveTask(id: TaskId, resolution: TaskResolution) {
        write(Write.RESOLVE, "task/${id.value}/${resolution.code}", HttpMethod.PUT)
    }

    /** Late-locking commit: start, then the status write. If the status write fails, the lock is
     * released once (best effort) before the error is rethrown. If start reports a stale lock of
     * the caller's on another task (409), that task is re-read, released when still locked, and
     * start is retried once. Cancellation propagates without cleanup; the server expires locks. */
    @LowLevelTaskLifecycle
    suspend fun commitResolution(id: TaskId, resolution: TaskResolution) {
        startForCommit(id)
        try {
            resolveTask(id, resolution)
        } catch (e: MapRouletteException) {
            releaseQuietly(id)
            throw e
        }
    }

    @OptIn(LowLevelTaskLifecycle::class)
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
    @OptIn(LowLevelTaskLifecycle::class)
    private suspend fun startReleasingUnknown(id: TaskId) {
        try {
            startTask(id)
        } catch (e: MapRouletteException) {
            if (e.problem == WriteProblem.OutcomeUnknown) releaseQuietly(id)
            throw e
        }
    }

    @OptIn(LowLevelTaskLifecycle::class)
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
                    ?.map { TaskId(it.jsonPrimitive.wholeNumber()) }
                    ?: emptyList(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The lock may be held even though the response could not be read.
            throw MapRouletteException(ErrorKind.PROTOCOL, response.status, problem = WriteProblem.OutcomeUnknown)
        }
    }

    /** Submits a choice task's answers or outcome with late locking: start, then
     * `POST task/{id}/choice`. The server applies the OSM edit (answers, or an enabled delete), sets the
     * status and releases the lock. The submission is checked against [task]'s payload before anything
     * is sent (IllegalArgumentException); take outcomes from [choiceOutcomes] (or their
     * [ChoiceOutcome.withoutDeletion] form).
     *
     * Failures carry a [ChoiceProblem] or [WriteProblem]; the lock is then released (best effort),
     * except after StatusPending or OutcomeUnknown. The identical submission is resent at most once,
     * only where the server's idempotency makes it safe: after StatusPending, and after an unknown
     * outcome of a non-editing outcome when a fresh read shows it did not land and the caller still
     * holds the lock. An edit (answers or a delete) is never resent after an unknown outcome, because
     * the first request may still be uploading: OutcomeUnknown is thrown with the lock kept; submit
     * the same submission again later (the server resumes it without a second upload). When the read
     * shows the submission landed, the result comes from the read. If the resend then fails without proving
     * that nothing was applied (e.g. lock_required because the first attempt finished meanwhile), the
     * first error (OutcomeUnknown, or StatusPending with its changeset) is thrown and the lock kept. */
    suspend fun submitChoice(task: Task, submission: ChoiceSubmission): ChoiceResult {
        requireWrites()
        val target = task.validateChoice(submission, allowElementDeletion)
        val id = task.id
        val body = choiceBody(submission)
        val edits = submission is ChoiceSubmission.Answers ||
            (submission as ChoiceSubmission.Outcome).outcome.deletesElement
        startForCommit(id)
        val first = try {
            return postChoice(id, body)
        } catch (e: MapRouletteException) {
            e
        }
        when {
            first.problem is ChoiceProblem.StatusPending -> Unit
            first.problem == WriteProblem.OutcomeUnknown -> when (val recovery = recover(task, target)) {
                is Recovery.Applied -> return ChoiceResult(TaskStatus(target.code), recovery.changesetId)
                // An edit may still be uploading on the server; resending could race it.
                Recovery.Resend -> if (edits) throw first
                Recovery.Unknown -> throw first
            }
            else -> {
                releaseQuietly(id)
                throw first
            }
        }
        // The first attempt may have landed (unknown) or did land in OSM (StatusPending).
        try {
            return postChoice(id, body)
        } catch (second: MapRouletteException) {
            when {
                second.problem is ChoiceProblem.StatusPending -> throw second
                first.problem == WriteProblem.OutcomeUnknown && second.problem.provesNotApplied() -> {
                    releaseQuietly(id)
                    throw second
                }
                // E.g. lock_required because the first attempt finished meanwhile: keep the first error
                // (StatusPending keeps its changeset id) and keep the lock for a later identical resend.
                else -> throw first
            }
        }
    }

    /** Fresh server-side OSM eligibility check (`GET task/{id}/choice/check`, `tasks:read`). It changes
     * nothing the caller owns; the server may record a system ineligible flag that hides the task from
     * mobile discovery. A 502 osm_unavailable or 503 osm_edits_unavailable carries [ChoiceProblem.OsmUnavailable]. */
    suspend fun checkChoice(id: TaskId): ChoiceEligibility {
        val response = send("task/${id.value}/choice/check")
        if (response.status != 200) {
            throw failure(response, ChoiceProblem.OsmUnavailable.takeIf {
                osmUnavailable(response.status, errorBody(response)?.text("error"))
            })
        }
        return decode {
            val o = Json.parseToJsonElement(response.body).obj()
            val deleteAllowed = o.bool("deleteAllowed") ?: false
            if (requireNotNull(o.bool("eligible"))) ChoiceEligibility(true, deleteAllowed, null)
            else ChoiceEligibility(false, false, ineligibleReason(o.text("reason")))
        }
    }

    private suspend fun postChoice(id: TaskId, body: String): ChoiceResult {
        val response = write(Write.CHOICE, "task/${id.value}/choice", HttpMethod.POST, body)
        return try {
            require(response.status == 200)
            val o = Json.parseToJsonElement(response.body).obj()
            val status = o.long("status")
            require(status in 0..Int.MAX_VALUE)
            ChoiceResult(TaskStatus(status.toInt()), o.optionalLong("changesetId")?.takeIf { it > 0 })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The submission may have been applied even though the response could not be read.
            throw MapRouletteException(ErrorKind.PROTOCOL, response.status, problem = WriteProblem.OutcomeUnknown)
        }
    }

    private sealed interface Recovery {
        data class Applied(val changesetId: Long?) : Recovery
        data object Resend : Recovery
        data object Unknown : Recovery
    }

    // Fresh identity and task reads after an interrupted submission. A lock still held by the caller
    // means it did not land (the server releases on success). Applied needs the target status, no lock,
    // not completed by someone else, and a status that differs from the one before submitting (Too hard
    // can be submitted on a Too hard task). Anything else, including a failed read, stays unknown.
    private suspend fun recover(task: Task, target: TaskResolution): Recovery = try {
        val me = getCurrentUser().id
        val fresh = getTask(task.id)
        when {
            fresh.lockedBy == me -> Recovery.Resend
            fresh.status?.code == target.code && fresh.lockedBy == null && task.status?.code != target.code &&
                (fresh.completedBy == null || fresh.completedBy == me) -> Recovery.Applied(fresh.changesetId)
            else -> Recovery.Unknown
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Credential providers may throw anything; the submission outcome stays unknown either way.
        Recovery.Unknown
    }

    private enum class Write { START, REFRESH, RELEASE, SKIP, RESOLVE, CHOICE }

    private fun requireWrites() {
        check(environment.allowsWrites) {
            "task writes are disabled for $environment; in SDK 0.x writes go only to staging (mr-api.osm.lol), loopback, or an environment created with allowWrites"
        }
    }

    private suspend fun write(operation: Write, path: String, method: HttpMethod, body: String? = null): HttpResponse {
        requireWrites()
        val response = try {
            send(path, method = method, body = body)
        } catch (e: MapRouletteException) {
            if (e.kind != ErrorKind.NETWORK) throw e
            throw MapRouletteException(e.kind, e.status, e.retryAfter, WriteProblem.OutcomeUnknown)
        }
        if (response.status in 200..299) return response
        throw failure(response, writeProblem(operation, response))
    }

    private fun errorBody(response: HttpResponse): JsonObject? = try {
        Json.parseToJsonElement(response.body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    // Bodies are inspected only for the lock/scope details below and never retained.
    private fun writeProblem(operation: Write, response: HttpResponse): WriteProblem? {
        val body = errorBody(response)
        fun field(name: String) = (body?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (operation == Write.CHOICE) return choiceProblem(response.status, body, ::field)
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

    private fun choiceProblem(status: Int, body: JsonObject?, field: (String) -> String?): WriteProblem? {
        val error = field("error")
        return when (status) {
            401 -> ChoiceProblem.OsmReauthRequired.takeIf { error == "osm_reauth_required" }
            403 -> when {
                error != "insufficient_scope" -> null
                field("scope") == "osm:tagfix" -> ChoiceProblem.OsmScopeRequired
                else -> WriteProblem.InsufficientScope
            }
            409 -> when (error) {
                "lock_required" -> WriteProblem.LockLost
                "invalid_transition" -> WriteProblem.InvalidTransition
                "submission_pending" -> ChoiceProblem.SubmissionPending
                "element_in_use" -> ChoiceProblem.ElementInUse
                // The server's diagnostic `detail` is deliberately not exposed.
                "task_ineligible" -> ChoiceProblem.TaskIneligible(ineligibleReason(field("reason")))
                else -> null
            }
            422 -> when (error) {
                "invalid_submission" -> ChoiceProblem.InvalidSubmission(field("detail"))
                "unsupported_task" -> ChoiceProblem.UnsupportedTask
                else -> null
            }
            500 -> if (error == "status_pending") {
                ChoiceProblem.StatusPending(runCatching { body?.optionalLong("changesetId") }.getOrNull())
            } else WriteProblem.OutcomeUnknown
            // 503 osm_edits_unavailable is refused before any lock check or upload.
            502, 503 -> if (osmUnavailable(status, error)) ChoiceProblem.OsmUnavailable else WriteProblem.OutcomeUnknown
            in 500..599 -> WriteProblem.OutcomeUnknown
            else -> null
        }
    }

    /** The caller's identity. Needs a credential: an API key reads `user/whoami`, a bearer token reads
     * `oauth/mobile/me` (fork backend) and reports the grant's scopes. AUTHENTICATION when the
     * credential is missing or rejected. */
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
        require(key == null || token == null) { "supply an API key or an access token, not both" }
        key?.let { require(it.isNotBlank() && '\r' !in it && '\n' !in it) { "API key is blank or contains a line break" } }
        token?.let { require(it.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) { "access token is not a valid bearer token" } }
        return Credentials(key, token)
    }

    private suspend fun <T> page(
        path: String,
        params: LinkedHashMap<String, String>,
        size: Int,
        after: PageCursor?,
        offset: Boolean,
        parse: (JsonElement) -> T,
        envelope: Boolean = false,
    ): Page<T> {
        require(size in 1..100) { "pageSize must be 1..100" }

        // Include the filters and page size in the cursor identity so a cursor
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
            "page cursor belongs to another client, operation, filter or page size"
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
                PageCursor(owner, key, Math.addExact(position, increment))
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
            "User-Agent" to "MapRoulette-Mobile-SDK/${MapRouletteSdk.VERSION}",
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

// Errors after which the server has re-evaluated the submission with the caller's lock and applied nothing.
private fun WriteProblem?.provesNotApplied() = when (this) {
    is ChoiceProblem.TaskIneligible, is ChoiceProblem.InvalidSubmission, ChoiceProblem.ElementInUse,
    ChoiceProblem.OsmReauthRequired, ChoiceProblem.OsmScopeRequired, ChoiceProblem.OsmUnavailable,
    ChoiceProblem.UnsupportedTask, WriteProblem.InsufficientScope -> true
    else -> false
}

// 502 osm_unavailable: OSM unreachable. 503 osm_edits_unavailable: stored OSM tokens unusable.
private fun osmUnavailable(status: Int, error: String?) =
    (status == 502 && error == "osm_unavailable") || (status == 503 && error == "osm_edits_unavailable")

private const val STATUSES_REASON = "statuses must be null or a non-empty list of codes >= 0"

private val choiceOnlyParams = mapOf("cct" to "3", "excludeStale" to "true")

private fun ineligibleReason(wire: String?) = when (wire) {
    "element_gone" -> IneligibleReason.ELEMENT_GONE
    "match_failed" -> IneligibleReason.MATCH_FAILED
    "key_changed" -> IneligibleReason.KEY_CHANGED
    else -> IneligibleReason.UNKNOWN
}

// Reject coerced primitive types, while preserving missing/null optional fields.
private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString)
    it.content
}

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.wholeNumber()

/** A JSON number with an exact integer value, also when written as 1.0 or 1e5 (as Swift's
 * JSONDecoder accepts). Strings, booleans, fractions and out-of-range values are rejected. */
internal fun JsonPrimitive.wholeNumber(): Long {
    require(!isString && this != JsonNull && booleanOrNull == null)
    return longOrNull ?: java.math.BigDecimal(content).longValueExact()
}

internal fun JsonPrimitive.wholeNumberOrNull(): Long? =
    if (isString || this == JsonNull || booleanOrNull != null) null
    else longOrNull ?: runCatching { java.math.BigDecimal(content).longValueExact() }.getOrNull()

private fun JsonObject.optionalLong(key: String): Long? =
    get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.wholeNumber()

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
        mappedOn = (o["mappedOn"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else it.wholeNumberOrNull()?.toString()
        },
        reviewStatus = o.optionalLong("reviewStatus")?.let {
            require(it in Int.MIN_VALUE..Int.MAX_VALUE)
            it.toInt()
        },
        bundleId = o.optionalLong("bundleId"),
        // The backend may store -1 for "no changeset".
        changesetId = o.optionalLong("changesetId")?.takeIf { it > 0 },
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
