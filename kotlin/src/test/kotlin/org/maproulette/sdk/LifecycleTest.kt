package org.maproulette.sdk

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class LifecycleTest {
    private val fixtures = Json.parseToJsonElement(
        File(System.getProperty("fixtures"), "task-completion.json").readText(),
    ).jsonObject
    private fun body(name: String) = fixtures.getValue(name).toString()
    private fun rows(name: String) = fixtures.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    /** Replays responses in order; a null response simulates a connection failure after sending. */
    private class Script(vararg responses: HttpResponse?) : Transport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return queue.removeFirst() ?: throw IOException("connection reset")
        }
        fun calls() = requests.map { "${it.method} ${it.url.substringAfter("/api/v2/")}" }
    }
    private fun ok(name: String? = null, status: Int = 200) = HttpResponse(status, body = name?.let(::body) ?: "")
    private fun client(script: Script) = MapRouletteClient(transport = script, apiKey = { "synthetic-user-key" })
    private val id = TaskId(101)

    private suspend fun call(client: MapRouletteClient, row: JsonObject) {
        when (row.text("call")) {
            "start" -> client.startTask(id)
            "refresh" -> client.refreshTaskLock(id)
            "release" -> client.releaseTask(id)
            "skip" -> client.skipTask(id)
            "resolve" -> client.resolveTask(id, TaskResolution.entries.first {
                it.code == (row["resolution"]?.jsonPrimitive?.int ?: 1)
            })
            else -> fail("unknown call")
        }
    }

    private fun camel(name: String) = name.lowercase().split('_')
        .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")

    private fun problemName(problem: WriteProblem?) = when (problem) {
        null -> null
        is WriteProblem.LockedByOtherUser -> "lockedByOtherUser"
        is WriteProblem.AlreadyHoldingTask -> "alreadyHoldingTask"
        WriteProblem.LockLost -> "lockLost"
        WriteProblem.InvalidTransition -> "invalidTransition"
        WriteProblem.InsufficientScope -> "insufficientScope"
        WriteProblem.OutcomeUnknown -> "outcomeUnknown"
    }

    @Test fun writeRoutesAreBareWithOneCredential() = runBlocking<Unit> {
        for (row in rows("routes")) {
            for (bearer in listOf(false, true)) {
                val script = Script(HttpResponse(row["status"]!!.jsonPrimitive.int, body = row.text("body")?.let(::body) ?: ""))
                val client = if (bearer) MapRouletteClient(transport = script, accessToken = { "synthetic-access-token" })
                else client(script)
                call(client, row)
                val request = script.requests.single()
                assertEquals(row.text("method"), request.method.name)
                assertEquals("https://maproulette.org" + row.text("path"), request.url)
                assertNull(request.body); assertFalse("Content-Type" in request.headers)
                if (bearer) {
                    assertEquals("Bearer synthetic-access-token", request.headers["Authorization"]); assertFalse("apiKey" in request.headers)
                } else {
                    assertEquals("synthetic-user-key", request.headers["apiKey"]); assertFalse("Authorization" in request.headers)
                }
            }
        }
        assertEquals(listOf(1, 2, 5, 6), TaskResolution.entries.map { it.code })
    }

    @Test fun startReturnsLockAndBundleMembers() = runBlocking<Unit> {
        val lock = client(Script(ok("start_200"))).startTask(id)
        assertEquals(TaskLock(lock.task, id, emptyList()), lock); assertEquals(id, lock.task.id)
        val bundle = client(Script(ok("start_200_bundle"))).startTask(id)
        assertEquals(TaskId(100), bundle.primaryTaskId); assertEquals(listOf(TaskId(100), TaskId(102)), bundle.bundledTaskIds)
    }

    @Test fun errorMappingsCarryLifecycleProblemsWithoutBodies() = runBlocking<Unit> {
        for (row in rows("errors")) {
            val status = row["status"]!!.jsonPrimitive.int
            val script = Script(HttpResponse(status, mapOf("Retry-After" to "5"), row.text("body")?.let(::body) ?: ""))
            val error = assertFailsWith<MapRouletteException>(row.toString()) { call(client(script), row) }
            assertEquals(row.text("kind"), camel(error.kind.name), row.toString())
            assertEquals(row.text("problem"), problemName(error.problem), row.toString())
            assertEquals(1, script.requests.size)
            assertFalse(error.toString().contains("other-mapper")); assertFalse(error.toString().contains("synthetic"))
        }
        val held = assertFailsWith<MapRouletteException> { client(Script(ok("start_409_own", 409))).startTask(id) }
        assertEquals(WriteProblem.AlreadyHoldingTask(TaskId(555), ChallengeId(43), "Survey shops", "2026-10-05T11:58:00.000Z"), held.problem)
        val minimal = assertFailsWith<MapRouletteException> { client(Script(ok("start_409_own_minimal", 409))).startTask(id) }
        assertEquals(WriteProblem.AlreadyHoldingTask(TaskId(555), null, null, null), minimal.problem)
        val other = assertFailsWith<MapRouletteException> { client(Script(ok("start_403_other", 403))).startTask(id) }
        assertEquals(WriteProblem.LockedByOtherUser("Task is currently locked by user other-mapper"), other.problem)
    }

    @Test fun interruptedWritesAreUnknownAndNeverRetried() = runBlocking<Unit> {
        for (row in rows("routes")) {
            val script = Script(null)
            val error = assertFailsWith<MapRouletteException> { call(client(script), row) }
            assertEquals(ErrorKind.NETWORK, error.kind); assertEquals(WriteProblem.OutcomeUnknown, error.problem)
            assertEquals(1, script.requests.size)
        }
    }

    @Test fun commitHappyPathStartsThenWritesStatus() = runBlocking<Unit> {
        val script = Script(ok("start_200"), ok(status = 204))
        client(script).commitResolution(id, TaskResolution.ALREADY_FIXED)
        assertEquals(listOf("GET task/101/start", "PUT task/101/5"), script.calls())
    }

    @Test fun commitStopsWhenAnotherUserHoldsTheLock() = runBlocking<Unit> {
        val script = Script(ok("start_403_other", 403))
        val error = assertFailsWith<MapRouletteException> { client(script).commitResolution(id, TaskResolution.FIXED) }
        assertIs<WriteProblem.LockedByOtherUser>(error.problem)
        assertEquals(listOf("GET task/101/start"), script.calls())
    }

    @Test fun commitReleasesOnceWhenStatusWriteFails() = runBlocking<Unit> {
        val rejected = Script(ok("start_200"), ok("resolve_400_invalid", 400), ok("task"))
        val error = assertFailsWith<MapRouletteException> { client(rejected).commitResolution(id, TaskResolution.TOO_HARD) }
        assertEquals(WriteProblem.InvalidTransition, error.problem)
        assertEquals(listOf("GET task/101/start", "PUT task/101/6", "GET task/101/release"), rejected.calls())
        // A failing best-effort release never replaces the original error.
        val interrupted = Script(ok("start_200"), null, null)
        val unknown = assertFailsWith<MapRouletteException> { client(interrupted).commitResolution(id, TaskResolution.FIXED) }
        assertEquals(WriteProblem.OutcomeUnknown, unknown.problem); assertEquals(ErrorKind.NETWORK, unknown.kind)
        assertEquals(listOf("GET task/101/start", "PUT task/101/1", "GET task/101/release"), interrupted.calls())
    }

    @Test fun commitReleasesAfterInterruptedStartWithoutWriting() = runBlocking<Unit> {
        val script = Script(null, ok("task"))
        val error = assertFailsWith<MapRouletteException> { client(script).commitResolution(id, TaskResolution.FIXED) }
        assertEquals(WriteProblem.OutcomeUnknown, error.problem)
        assertEquals(listOf("GET task/101/start", "GET task/101/release"), script.calls())
    }

    @Test fun commitRecoversOwnStaleLockOnce() = runBlocking<Unit> {
        val locked = Script(ok("start_409_own", 409), ok("stale_locked"), ok("task"), ok("start_200"), ok(status = 204))
        client(locked).commitResolution(id, TaskResolution.NOT_AN_ISSUE)
        assertEquals(listOf("GET task/101/start", "GET task/555", "GET task/555/release", "GET task/101/start", "PUT task/101/2"), locked.calls())

        val resolved = Script(ok("start_409_own", 409), ok("stale_resolved"), ok("start_200"), ok(status = 204))
        client(resolved).commitResolution(id, TaskResolution.FIXED)
        assertEquals(listOf("GET task/101/start", "GET task/555", "GET task/101/start", "PUT task/101/1"), resolved.calls())

        val missing = Script(ok("start_409_own", 409), HttpResponse(404), ok("start_200"), ok(status = 204))
        client(missing).commitResolution(id, TaskResolution.FIXED)
        assertEquals(listOf("GET task/101/start", "GET task/555", "GET task/101/start", "PUT task/101/1"), missing.calls())

        val again = Script(ok("start_409_own", 409), ok("stale_locked"), ok("task"), ok("start_409_own", 409))
        val error = assertFailsWith<MapRouletteException> { client(again).commitResolution(id, TaskResolution.FIXED) }
        assertIs<WriteProblem.AlreadyHoldingTask>(error.problem)
        assertEquals(listOf("GET task/101/start", "GET task/555", "GET task/555/release", "GET task/101/start"), again.calls())

        val retryLost = Script(ok("start_409_own", 409), ok("stale_locked"), ok("task"), null, ok("task"))
        val lost = assertFailsWith<MapRouletteException> { client(retryLost).commitResolution(id, TaskResolution.FIXED) }
        assertEquals(WriteProblem.OutcomeUnknown, lost.problem)
        assertEquals(listOf("GET task/101/start", "GET task/555", "GET task/555/release", "GET task/101/start", "GET task/101/release"), retryLost.calls())

        val unreadable = Script(ok("start_409_own", 409), null)
        val original = assertFailsWith<MapRouletteException> { client(unreadable).commitResolution(id, TaskResolution.FIXED) }
        assertEquals(ErrorKind.CONFLICT, original.kind); assertEquals(2, unreadable.requests.size)
    }

    @Test fun commitRequiresWritePermissionAndValidSession() = runBlocking<Unit> {
        for ((name, status, problem) in listOf(Triple("gate_403_scope", 403, WriteProblem.InsufficientScope), Triple("gate_401_invalid", 401, null))) {
            val script = Script(ok(name, status))
            val error = assertFailsWith<MapRouletteException> { client(script).commitResolution(id, TaskResolution.FIXED) }
            assertEquals(problem, error.problem); assertEquals(1, script.requests.size)
        }
    }

    @Test fun readModelLockFieldsAndWriteScope() = runBlocking<Unit> {
        val task = client(Script(ok("task_lock_fields"))).getTask(id)
        assertEquals(901L, task.lockedBy); assertEquals(900L, task.completedBy)
        assertEquals("2026-10-05T12:00:00.000Z", task.mappedOn); assertEquals(0, task.reviewStatus); assertNull(task.bundleId)
        val plain = client(Script(ok("task"))).getTask(id)
        assertNull(plain.lockedBy); assertNull(plain.completedBy)
        val writer = MapRouletteClient(transport = Script(ok("identity_mobile_write")), accessToken = { "synthetic-access-token" }).getCurrentUser()
        assertEquals(setOf("tasks:read", "tasks:write"), writer.scopes); assertTrue(writer.canWriteTasks)
        assertFalse(UserIdentity(900, false, setOf("tasks:read")).canWriteTasks)
        assertTrue(UserIdentity(900, false).canWriteTasks); assertFalse(UserIdentity(900, true).canWriteTasks)
    }

    @Test fun bareWritesReachRealTransportWithoutBody() = runBlocking<Unit> {
        MockWebServer().use { server -> OkHttpTransport().use { transport ->
            server.start()
            val client = MapRouletteClient(serviceUrl = server.url("/api/v2/").toString(), transport = transport, accessToken = { "wire-bearer" })
            server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
            client.skipTask(id); client.resolveTask(id, TaskResolution.FIXED)
            for ((method, path) in listOf("POST" to "/api/v2/task/101/skip", "PUT" to "/api/v2/task/101/1")) {
                val received = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(method, received.method); assertEquals(path, received.path)
                assertEquals(0L, received.bodySize); assertEquals("0", received.getHeader("Content-Length"))
                assertNull(received.getHeader("Content-Type")); assertNull(received.getHeader("apiKey"))
                assertEquals("Bearer wire-bearer", received.getHeader("Authorization"))
            }
        } }
    }

    private suspend fun decode(task: JsonObject): Task = client(Script(HttpResponse(200, body = task.toString()))).getTask(id)
    private fun kind(name: String) = rows("kinds").first { it.text("name") == name }

    private fun elementName(element: OsmElementRef?) = element?.let { "${it.type.name.lowercase()}/${it.id}" }

    @Test fun taskKindsDecodeLeniently() = runBlocking<Unit> {
        for (row in rows("kinds")) {
            val task = decode(row.getValue("task").jsonObject)
            val work = task.work()
            val name = row.text("name")
            when (row.text("work")) {
                "standard" -> assertEquals(TaskWork.Standard, work, name)
                "tagFix" -> {
                    val fix = assertIs<TaskWork.TagFix>(work, name)
                    assertEquals(row["version"]!!.jsonPrimitive.int, fix.version)
                    val expected = row.getValue("edits").jsonArray.map { it.jsonObject }
                    assertEquals(expected.map { it.text("element") }, fix.edits.map { elementName(it.element) })
                    assertEquals(expected.map { it.text("kind") }, fix.edits.map { it.kind.name.lowercase() })
                    assertEquals(expected.map { e -> e.getValue("setTags").jsonObject.mapValues { it.value.jsonPrimitive.content } }, fix.edits.map { it.setTags })
                    assertEquals(expected.map { e -> e.getValue("unsetTags").jsonArray.map { it.jsonPrimitive.content } }, fix.edits.map { it.unsetTags })
                }
                "changeFile" -> assertEquals(TaskWork.ChangeFile(row.text("format"), row.text("encoding")), work, name)
                "unknown" -> assertEquals(TaskWork.Unknown(task.cooperativeWork!!), work, name)
                else -> fail("unknown work")
            }
            val support = when (task.mobileSupport()) {
                MobileSupport.FULL -> "full"; MobileSupport.RESOLVE_WITHOUT_FIX -> "resolveWithoutFix"; MobileSupport.UNSUPPORTED -> "unsupported"
            }
            assertEquals(row.text("support"), support, name)
        }
        for (row in rows("challenge_kinds")) {
            val challenge = Challenge(ChallengeId(42), ProjectId(7), "c", null, null, null, null, null, null,
                row["cooperativeType"]?.jsonPrimitive?.intOrNull)
            val expected = mapOf("none" to CooperativeType.NONE, "tags" to CooperativeType.TAGS,
                "changeFile" to CooperativeType.CHANGE_FILE, "unknown" to CooperativeType.UNKNOWN)
            assertEquals(expected[row.text("kind")], challenge.cooperativeKind)
        }
        val wire = Script(HttpResponse(200, body = """{"id":42,"parent":7,"name":"c","cooperativeType":2}"""))
        assertEquals(CooperativeType.CHANGE_FILE, client(wire).getChallenge(ChallengeId(42)).cooperativeKind)
    }

    @Test fun allowedResolutionsFollowKindAndStatus() = runBlocking<Unit> {
        for (row in rows("allowed")) {
            val base = kind(row.text("kind")!!).getValue("task").jsonObject
            val task = decode(JsonObject(base + ("status" to row.getValue("status"))))
            assertEquals(row.getValue("resolutions").jsonArray.map { it.jsonPrimitive.int }.toSet(),
                task.allowedResolutions().map { it.code }.toSet(), row.toString())
            assertEquals(row["skip"]!!.jsonPrimitive.boolean, task.canSkip(), row.toString())
        }
    }

    @Test fun verifyResolutionAppliesReReadRules() = runBlocking<Unit> {
        for (row in rows("verify")) {
            val task = decode(JsonObject(fixtures.getValue("task").jsonObject + row.filterKeys { it in setOf("status", "lockedBy", "completedBy") }))
            val target = TaskResolution.entries.first { it.code == row["target"]!!.jsonPrimitive.int }
            assertEquals(row.text("expect"), camel(task.verifyResolution(target, me = 900).name), row.toString())
        }
    }

    @Test fun instructionsResolveSubstituteAndListFormFields() = runBlocking<Unit> {
        val fixture = fixtures.getValue("instruction").jsonObject
        val task = decode(fixture.getValue("task").jsonObject)
        val challenge = Challenge(ChallengeId(42), ProjectId(7), "c", fixture.text("challenge"), null, null, null, null, null)
        val instruction = task.resolvedInstruction(challenge)
        assertEquals(fixture.text("challenge"), instruction.markdown)
        val properties = task.templateProperties()
        assertEquals(fixture.getValue("properties").jsonObject.mapValues { it.value.jsonPrimitive.content }, properties)
        assertEquals(fixture.text("rendered"), instruction.render(properties))
        val fields = fixture.getValue("formFields").jsonArray.map { it.jsonObject }.map {
            if (it.text("type") == "select") FormField.Select(it.text("name")!!, it.text("label")!!, it.getValue("values").jsonArray.map { v -> v.jsonPrimitive.content })
            else FormField.Checkbox(it.text("name")!!, it.text("label")!!)
        }
        assertEquals(fields, instruction.formFields)
        for (row in rows("instruction_choice")) {
            val choice = decode(JsonObject(fixtures.getValue("task").jsonObject + ("instruction" to row.getValue("task"))))
            val parent = Challenge(ChallengeId(42), ProjectId(7), "c", row.text("challenge"), null, null, null, null, null)
            assertEquals(row.text("expect"), choice.resolvedInstruction(parent).markdown)
        }
        for (row in rows("osm_identity")) {
            val geometry = buildJsonObject { put("type", "FeatureCollection"); put("features", JsonArray(listOf(row.getValue("feature")))) }
            val identified = decode(JsonObject(fixtures.getValue("task").jsonObject + ("geometries" to geometry))).templateProperties()
            assertEquals(row.text("osmId"), identified["#osmId"], row.toString())
            assertEquals(row.text("osmType"), identified["#osmType"], row.toString())
        }
    }
}
