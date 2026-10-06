package org.maproulette.sdk

import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class ChoiceTest {
    private val fixtures = Json.parseToJsonElement(File(System.getProperty("fixtures"), "choice.json").readText()).jsonObject
    private fun rows(name: String) = fixtures.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
    private val id = TaskId(101)

    /** Replays responses in order; a null response simulates a connection failure after sending. */
    private class Script(responses: List<HttpResponse?>) : Transport {
        private val queue = ArrayDeque(responses)
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            if (queue.isEmpty()) fail("unexpected ${request.method} ${request.url}")
            return queue.removeFirst() ?: throw IOException("connection reset")
        }
        fun calls() = requests.map { "${it.method} ${URI(it.url).path}" }
    }

    private fun response(row: JsonElement?): HttpResponse? = when (row) {
        null, JsonNull -> null
        else -> row.jsonArray.let { HttpResponse(it[0].jsonPrimitive.int, body = it[1].takeUnless { b -> b == JsonNull }?.toString() ?: "") }
    }
    private fun client(script: Script, deletion: Boolean = false) =
        MapRouletteClient(transport = script, accessToken = { "synthetic-access-token" }, allowElementDeletion = deletion)

    private fun taskJson(payload: JsonElement?, extra: Map<String, JsonElement> = emptyMap()): JsonObject {
        val base = fixtures.getValue("task").jsonObject
        return JsonObject(base + extra + (if (payload == null) emptyMap() else mapOf("cooperativeWork" to payload)))
    }
    private suspend fun decode(task: JsonObject): Task =
        client(Script(listOf(HttpResponse(200, body = task.toString())))).getTask(id)
    private suspend fun slcTask(extra: Map<String, JsonElement> = emptyMap()) = decode(taskJson(fixtures.getValue("slc"), extra))

    private fun choiceJson(c: TaskWork.Choice) = buildJsonObject {
        put("element", "${c.element.type.name.lowercase()}/${c.element.id}")
        put("match", JsonObject(c.match.mapValues { JsonPrimitive(it.value) }))
        put("questions", JsonArray(c.questions.map { q ->
            buildJsonObject {
                put("id", q.id); put("prompt", q.prompt); put("description", q.description)
                put("expect", JsonObject(q.expect.mapValues { JsonPrimitive(it.value) }))
                put("options", JsonArray(q.options.map { o ->
                    buildJsonObject {
                        put("id", o.id); put("label", o.label); put("description", o.description)
                        put("setTags", JsonObject(o.setTags.mapValues { JsonPrimitive(it.value) }))
                        put("unsetTags", JsonArray(o.unsetTags.map(::JsonPrimitive)))
                    }
                }))
            }
        }))
    }
    private fun outcomeJson(o: ChoiceOutcome) = buildJsonObject {
        put("id", o.id); put("label", o.label); put("description", o.description)
        put("resolution", o.resolution.code); put("deletesElement", o.deletesElement)
    }

    private fun problemName(problem: WriteProblem?): String? = when (problem) {
        null -> null
        is WriteProblem.LockedByOtherUser -> "lockedByOtherUser"
        is WriteProblem.AlreadyHoldingTask -> "alreadyHoldingTask"
        WriteProblem.LockLost -> "lockLost"
        WriteProblem.InvalidTransition -> "invalidTransition"
        WriteProblem.InsufficientScope -> "insufficientScope"
        WriteProblem.OutcomeUnknown -> "outcomeUnknown"
        is ChoiceProblem.TaskIneligible -> "taskIneligible"
        ChoiceProblem.ElementInUse -> "elementInUse"
        ChoiceProblem.OsmReauthRequired -> "osmReauthRequired"
        ChoiceProblem.OsmScopeRequired -> "osmScopeRequired"
        ChoiceProblem.OsmUnavailable -> "osmUnavailable"
        ChoiceProblem.SubmissionPending -> "submissionPending"
        is ChoiceProblem.StatusPending -> "statusPending"
        is ChoiceProblem.InvalidSubmission -> "invalidSubmission"
        ChoiceProblem.UnsupportedTask -> "unsupportedTask"
    }
    private fun camel(name: String) = name.lowercase().split('_')
        .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")

    private fun submission(task: Task, row: JsonObject, deletion: Boolean): ChoiceSubmission {
        val s = row.getValue("submission").jsonObject
        s["answers"]?.let { answers -> return ChoiceSubmission.Answers(answers.jsonObject.mapValues { it.value.jsonPrimitive.content }) }
        val outcomeId = s.text("outcome")!!
        val decodedWith = (s["decodedWithDeletion"] as? JsonPrimitive)?.boolean ?: deletion
        return ChoiceSubmission.Outcome(task.choiceOutcomes(decodedWith).firstOrNull { it.id == outcomeId }
            ?: ChoiceOutcome(outcomeId, "Synthetic", null, TaskResolution.NOT_AN_ISSUE, false))
    }

    @Test fun slcExampleDecodesWithDeletionOffAndOn() = runBlocking<Unit> {
        val task = slcTask()
        for (deletion in listOf(false, true)) {
            val work = assertIs<TaskWork.Choice>(task.work(deletion))
            assertEquals(fixtures.getValue("slc_expect"), choiceJson(work))
            val key = if (deletion) "deletion_on" else "deletion_off"
            assertEquals(fixtures.getValue("outcomes").jsonObject.getValue(key), JsonArray(task.choiceOutcomes(deletion).map(::outcomeJson)))
            assertEquals(task.choiceOutcomes(deletion).dropLast(1), work.outcomes)
        }
        assertEquals(task.choiceOutcomes(false), task.choiceOutcomes())
        assertEquals(emptyList(), decode(taskJson(null)).choiceOutcomes())
    }

    @Test fun payloadValidationRules() = runBlocking<Unit> {
        for (row in rows("valid")) {
            assertIs<TaskWork.Choice>(decode(taskJson(row.getValue("payload"))).work(), row.text("name"))
        }
        val rules = mutableSetOf<Int>()
        for (row in rows("invalid")) {
            val task = decode(taskJson(row.getValue("payload")))
            for (deletion in listOf(false, true)) {
                assertEquals(TaskWork.Unknown(task.cooperativeWork!!), task.work(deletion), row.text("name"))
            }
            assertEquals(MobileSupport.UNSUPPORTED, task.mobileSupport(), row.text("name"))
            assertEquals(emptyList(), task.choiceOutcomes())
            rules += row["rule"]!!.jsonPrimitive.int
        }
        assertEquals((1..5).toSet(), rules)
    }

    @Test fun onlyActionableUnbundledChoiceTasksAreInPlace() = runBlocking<Unit> {
        val invalid = rows("invalid").first().getValue("payload")
        for (row in rows("support")) {
            val payload = when (row.text("payload")) { "slc" -> fixtures.getValue("slc"); "invalid" -> invalid; else -> null }
            val extra = buildMap { put("status", row.getValue("status")); row["bundleId"]?.let { put("bundleId", it) } }
            val task = decode(taskJson(payload, extra))
            val support = if (task.mobileSupport() == MobileSupport.IN_PLACE) "inPlace" else "unsupported"
            assertEquals(row.text("support"), support, row.text("name"))
            assertEquals(row.flag("skip"), task.canSkip(), row.text("name"))
            assertEquals(emptySet(), task.allowedResolutions(), row.text("name"))
        }
    }

    @Test fun submissionBodiesAreCanonical() = runBlocking<Unit> {
        val ok = fixtures.getValue("flows").jsonArray.first().jsonObject.getValue("responses").jsonArray
        for (row in rows("submissions")) {
            val deletion = row.flag("deletion")
            val task = slcTask()
            val script = Script(ok.map(::response))
            client(script, deletion).submitChoice(task, submission(task, row, deletion))
            assertEquals(listOf("GET /api/v2/task/101/start", "POST /api/v2/task/101/choice"), script.calls(), row.text("name"))
            val post = script.requests.last()
            assertEquals(row.text("body"), post.body, row.text("name"))
            assertEquals("application/json", post.headers["Content-Type"])
            assertEquals("Bearer synthetic-access-token", post.headers["Authorization"]); assertFalse("apiKey" in post.headers)
            assertNull(script.requests.first().body)
        }
    }

    @Test fun invalidSubmissionsSendNothing() = runBlocking<Unit> {
        for (row in rows("invalid_submissions")) {
            val payload = when {
                "payload" !in row -> fixtures.getValue("slc")
                row.text("payload") == "invalid" -> rows("invalid").first().getValue("payload")
                else -> null
            }
            val extra = row["bundleId"]?.let { mapOf("bundleId" to it) } ?: emptyMap()
            val task = decode(taskJson(payload, extra))
            val deletion = row.flag("clientDeletion")
            val script = Script(emptyList())
            assertFailsWith<IllegalArgumentException>(row.text("name")) {
                client(script, deletion).submitChoice(task, submission(task, row, deletion))
            }
            assertEquals(emptyList(), script.requests, row.text("name"))
        }
    }

    @Test fun errorMappingsReleaseTheLock() = runBlocking<Unit> {
        val start = HttpResponse(200, body = fixtures.getValue("start").toString())
        for (row in rows("errors")) {
            val name = row.text("name")
            val task = slcTask()
            val failure = HttpResponse(row["status"]!!.jsonPrimitive.int, body = row["body"]?.takeUnless { it == JsonNull }?.toString() ?: "")
            val script = Script(listOf(start, failure, HttpResponse(200, body = fixtures.getValue("task").toString())))
            val error = assertFailsWith<MapRouletteException>(name) {
                client(script).submitChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes")))
            }
            assertEquals(row.text("kind"), camel(error.kind.name), name)
            assertEquals(row.text("problem"), problemName(error.problem), name)
            when (val problem = error.problem) {
                is ChoiceProblem.TaskIneligible -> assertEquals(row.text("reason"), camel(problem.reason.name), name)
                is ChoiceProblem.InvalidSubmission -> assertEquals(row.text("detail"), problem.detail, name)
                else -> {}
            }
            val calls = listOf("GET /api/v2/task/101/start", "POST /api/v2/task/101/choice") +
                if (row.flag("release")) listOf("GET /api/v2/task/101/release") else emptyList()
            assertEquals(calls, script.calls(), name)
            assertFalse(error.toString().contains("synthetic")); assertFalse(error.toString().contains("bench"))
        }
    }

    @Test fun submitFlowsRetryOnlyWhereIdempotent() = runBlocking<Unit> {
        for (row in rows("flows")) {
            val name = row.text("name")
            val deletion = row.flag("deletion")
            val task = slcTask(row["taskStatus"]?.let { mapOf("status" to it) } ?: emptyMap())
            val script = Script(row.getValue("responses").jsonArray.map(::response))
            val call = suspend { client(script, deletion).submitChoice(task, submission(task, row, deletion)) }
            val result = row["result"]?.jsonObject
            if (result != null) {
                val got = call()
                assertEquals(result["status"]!!.jsonPrimitive.int, got.status.code, name)
                assertEquals(result["changesetId"]!!.jsonPrimitive.longOrNull, got.changesetId, name)
            } else {
                val expected = row.getValue("error").jsonObject
                val error = assertFailsWith<MapRouletteException>(name) { call() }
                assertEquals(expected.text("kind"), camel(error.kind.name), name)
                assertEquals(expected.text("problem"), problemName(error.problem), name)
                expected["changesetId"]?.let { assertEquals(ChoiceProblem.StatusPending(it.jsonPrimitive.long), error.problem, name) }
            }
            assertEquals(row.getValue("calls").jsonArray.map { it.jsonPrimitive.content }, script.calls(), name)
        }
    }

    @Test fun checkChoiceMapsEligibility() = runBlocking<Unit> {
        for (row in rows("check")) {
            val name = row.text("name")
            val script = Script(listOf(HttpResponse(row["status"]!!.jsonPrimitive.int,
                body = row["body"]?.takeUnless { it == JsonNull }?.toString() ?: "")))
            val expect = row["expect"]?.jsonObject
            if (expect != null) {
                val got = client(script).checkChoice(id)
                assertEquals(expect["eligible"]!!.jsonPrimitive.boolean, got.eligible, name)
                assertEquals(expect["deleteAllowed"]!!.jsonPrimitive.boolean, got.deleteAllowed, name)
                assertEquals(expect.text("reason"), got.reason?.let { camel(it.name) }, name)
            } else {
                val expected = row.getValue("error").jsonObject
                val error = assertFailsWith<MapRouletteException>(name) { client(script).checkChoice(id) }
                assertEquals(expected.text("kind"), camel(error.kind.name), name)
                assertEquals(expected.text("problem"), problemName(error.problem), name)
            }
            val request = script.requests.single()
            assertEquals("GET /api/v2/task/101/choice/check", script.calls().single())
            assertNull(request.body); assertNull(URI(request.url).query)
        }
    }

    @Test fun identityReportsOsmTagfixScope() = runBlocking<Unit> {
        for (row in rows("identity")) {
            val me = JsonObject(fixtures.getValue("me").jsonObject + ("scope" to JsonPrimitive(row.text("scope"))))
            val identity = client(Script(listOf(HttpResponse(200, body = me.toString())))).getCurrentUser()
            assertEquals(row.flag("canWriteTasks"), identity.canWriteTasks, row.toString())
            assertEquals(row.flag("canEditOsm"), identity.canEditOsm, row.toString())
        }
        assertFalse(UserIdentity(900, false).canEditOsm)
    }

    @Test fun choiceOnlyFilterAddsCooperativeTypeParameters() = runBlocking<Unit> {
        val expected = fixtures.getValue("markers_query").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val bounds = Bounds(-111.91, 40.75, -111.87, 40.78)
        for (choiceOnly in listOf(false, true)) {
            val script = Script(listOf(HttpResponse(200, body = "[]"), HttpResponse(200, body = """{"total":0,"tasks":[]}""")))
            val client = client(script)
            client.findTaskMarkers(TaskFilter(bounds = bounds, choiceOnly = choiceOnly))
            client.findTasksInBounds(TaskFilter(bounds = bounds, choiceOnly = choiceOnly))
            for (request in script.requests) {
                val query = URI(request.url).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                for ((key, value) in expected) assertEquals(if (choiceOnly) value else null, query[key], request.url)
            }
        }
    }

    @Test fun choicePostReachesRealTransportWithJsonBody() = runBlocking<Unit> {
        MockWebServer().use { server -> OkHttpTransport().use { transport ->
            server.start()
            val client = MapRouletteClient(serviceUrl = server.url("/api/v2/").toString(), transport = transport,
                accessToken = { "wire-bearer" })
            val task = slcTask()
            server.enqueue(MockResponse().setBody(fixtures.getValue("start").toString()))
            server.enqueue(MockResponse().setBody("""{"status":1,"changesetId":42}"""))
            assertEquals(ChoiceResult(TaskStatus(1), 42), client.submitChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes"))))
            assertEquals("/api/v2/task/101/start", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
            val post = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val body = """{"answers":{"backrest":"yes"}}"""
            assertEquals("POST", post.method); assertEquals("/api/v2/task/101/choice", post.path)
            assertEquals(body, post.body.readUtf8()); assertEquals(body.length.toString(), post.getHeader("Content-Length"))
            assertTrue(post.getHeader("Content-Type")!!.startsWith("application/json"))
            assertNull(post.getHeader("Transfer-Encoding")); assertEquals("Bearer wire-bearer", post.getHeader("Authorization"))
        } }
    }
}
