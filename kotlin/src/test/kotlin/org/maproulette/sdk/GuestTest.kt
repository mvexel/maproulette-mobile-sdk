package org.maproulette.sdk

import java.io.File
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class GuestTest {
    private fun load(name: String) = Json.parseToJsonElement(File(System.getProperty("fixtures"), name).readText()).jsonObject
    private val guest = load("guest.json")
    private val choice = load("choice.json")
    private fun ok(value: JsonElement, status: Int = 200) = HttpResponse(status, body = value.toString())

    private class Script(responses: List<HttpResponse>) : Transport {
        private val queue = ArrayDeque(responses)
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            if (queue.isEmpty()) fail("unexpected ${request.method} ${request.url}")
            return queue.removeFirst()
        }
        fun calls() = requests.map { "${it.method} ${URI(it.url).path}" }
    }

    private fun client(
        script: Script, environment: MapRouletteEnvironment = MapRouletteEnvironment.STAGING, deletion: Boolean = false,
    ) = MapRouletteClient(environment, script, deletion, accessToken = { "synthetic-guest-token" })

    private suspend fun slcTask(deletion: Boolean = false): Task {
        val task = JsonObject(choice.getValue("task").jsonObject + ("cooperativeWork" to choice.getValue("slc")))
        return client(Script(listOf(ok(task))), deletion = deletion).getTask(TaskId(101))
    }

    @Test fun guestStatusDecodes() = runBlocking<Unit> {
        val script = Script(listOf(ok(guest.getValue("status")), ok(guest.getValue("status_claimed"))))
        val c = client(script)
        val status = c.getGuestStatus()
        assertEquals(GuestState.ACTIVE, status.state); assertEquals(GuestEmailState.PENDING, status.email)
        assertEquals(14, status.pendingCount); assertEquals(0, status.publishedCount); assertNull(status.claimedAs)
        assertEquals(Instant.parse("2026-11-09T18:00:00Z"), status.expiresAt)
        val claimed = c.getGuestStatus()
        assertEquals(GuestState.CLAIMED, claimed.state)
        assertEquals(ClaimedAccount("rosa_slc", 123), claimed.claimedAs)
        assertEquals(Instant.parse("2026-11-09T18:00:00Z").plusMillis(250), claimed.expiresAt)
        assertEquals(listOf("GET /api/v2/mobile-guest/me", "GET /api/v2/mobile-guest/me"), script.calls())
        assertTrue(script.requests.all { it.headers["Authorization"] == "Bearer synthetic-guest-token" })
    }

    @Test fun pendingSubmitPostsCanonicalBodyWithoutLocking() = runBlocking<Unit> {
        val task = slcTask()
        val script = Script(listOf(ok(guest.getValue("pending_submit"))))
        val result = client(script).submitPendingChoice(task, ChoiceSubmission.Answers(mapOf("material" to "wood", "backrest" to "yes")))
        assertEquals(task.id, result.taskId); assertEquals(PendingState.PENDING, result.state)
        assertEquals(Instant.parse("2026-10-17T18:00:00Z"), result.holdUntil)
        // No start/lock: one POST to the pending route.
        assertEquals(listOf("POST /api/v2/task/101/choice/pending"), script.calls())
        assertEquals("""{"answers":{"backrest":"yes","material":"wood"}}""", script.requests[0].body)
    }

    @Test fun pendingGoneOutcomeIsStoredWithoutDeletion() = runBlocking<Unit> {
        val task = slcTask(deletion = true)
        val script = Script(listOf(ok(guest.getValue("pending_submit"))))
        val c = client(script, deletion = true)
        val gone = c.choiceOutcomes(task).first { it.deletesElement }
        c.submitPendingChoice(task, ChoiceSubmission.Outcome(gone))
        assertEquals("{\"outcome\":\"${gone.id}\"}", script.requests[0].body)
    }

    @Test fun pendingSubmitValidatesLocallyAndRequiresWrites() = runBlocking<Unit> {
        val task = slcTask()
        val script = Script(emptyList())
        assertFailsWith<IllegalArgumentException> {
            client(script).submitPendingChoice(task, ChoiceSubmission.Answers(mapOf("material" to "gold")))
        }
        val production = client(script, MapRouletteEnvironment.PRODUCTION)
        assertFailsWith<IllegalStateException> {
            production.submitPendingChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes")))
        }
        assertFailsWith<IllegalStateException> { production.deleteGuest() }
        assertFailsWith<IllegalStateException> { production.withdrawPendingChoice(TaskId(101)) }
        assertFailsWith<IllegalStateException> { production.setGuestEmail("rosa@example.org") }
        assertTrue(script.requests.isEmpty())
    }

    @Test fun pendingSubmitMapsChoiceProblems() = runBlocking<Unit> {
        val task = slcTask()
        val body = buildJsonObject { put("error", "task_ineligible"); put("reason", "key_changed"); put("detail", "x") }
        val script = Script(listOf(ok(body, 409)))
        val error = assertFailsWith<MapRouletteException> {
            client(script).submitPendingChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes")))
        }
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertEquals(ChoiceProblem.TaskIneligible(IneligibleReason.KEY_CHANGED), error.problem)
        assertEquals("task_ineligible", error.reason)
    }

    @Test fun withdrawSendsDelete() = runBlocking<Unit> {
        val script = Script(listOf(HttpResponse(204), ok(buildJsonObject { put("error", "not_found") }, 404)))
        val c = client(script)
        c.withdrawPendingChoice(TaskId(101))
        val error = assertFailsWith<MapRouletteException> { c.withdrawPendingChoice(TaskId(101)) }
        assertEquals(ErrorKind.NOT_FOUND, error.kind)
        assertEquals(List(2) { "DELETE /api/v2/task/101/choice/pending" }, script.calls())
        assertTrue(script.requests.all { it.body == null })
    }

    @Test fun pendingListPagesWithServerCursor() = runBlocking<Unit> {
        val last = buildJsonObject { putJsonArray("items") {}; put("next", JsonNull) }
        val script = Script(listOf(ok(guest.getValue("pending_list")), ok(last)))
        val c = client(script)
        val first = c.listPendingChoices(pageSize = 3)
        assertEquals(listOf(PendingState.PENDING, PendingState.PUBLISHED, PendingState.SKIPPED_STALE), first.items.map { it.state })
        assertEquals(1_234_567L, first.items[1].changesetId); assertEquals(listOf("bench"), first.items[1].droppedQuestionIds)
        assertNull(first.items[2].holdUntil); assertNull(first.items[2].changesetId)
        assertEquals(ChallengeId(7), first.items[0].challengeId)
        val next = assertNotNull(first.next)
        val second = c.listPendingChoices(pageSize = 3, after = next)
        assertTrue(second.items.isEmpty()); assertNull(second.next)
        val urls = script.requests.map { it.url.toHttpUrl() }
        assertNull(urls[0].queryParameter("after")); assertEquals("3", urls[0].queryParameter("limit"))
        assertEquals("c2", urls[1].queryParameter("after"))
        // A cursor is bound to its page size and client.
        assertFailsWith<IllegalArgumentException> { c.listPendingChoices(pageSize = 5, after = next) }
        assertFailsWith<IllegalArgumentException> { client(Script(emptyList())).listPendingChoices(pageSize = 3, after = next) }
        assertFailsWith<IllegalArgumentException> { c.listPendingChoices(pageSize = 0) }
    }

    @Test fun setEmailSendsJsonAndChecksShape() = runBlocking<Unit> {
        val script = Script(listOf(ok(guest.getValue("status"))))
        val c = client(script)
        val status = c.setGuestEmail(" rosa@example.org ")
        assertEquals(GuestEmailState.PENDING, status.email)
        assertEquals(listOf("PUT /api/v2/mobile-guest/email"), script.calls())
        assertEquals(buildJsonObject { put("email", "rosa@example.org") }, Json.parseToJsonElement(script.requests[0].body!!))
        assertEquals("application/json", script.requests[0].headers["Content-Type"])
        for (bad in listOf("rosa", "@example.org", "a@b@c", "a".repeat(250) + "@b.cd")) {
            assertFailsWith<IllegalArgumentException>(bad) { c.setGuestEmail(bad) }
        }
        assertEquals(1, script.requests.size)
    }

    @Test fun setEmailErrorsCarryStatusKindAndCode() = runBlocking<Unit> {
        for (row in guest.getValue("email_errors").jsonArray.map { it.jsonObject }) {
            val status = row.getValue("status").jsonPrimitive.int
            val script = Script(listOf(ok(row.getValue("body"), status)))
            val error = assertFailsWith<MapRouletteException>(row.toString()) { client(script).setGuestEmail("rosa@example.org") }
            val kind = row.getValue("kind").jsonPrimitive.content
            assertEquals(kind.replace(Regex("([A-Z])"), "_$1").uppercase(), error.kind.name, row.toString())
            assertEquals(status, error.status, row.toString())
            assertEquals(row["reason"]?.jsonPrimitive?.contentOrNull, error.reason, row.toString())
            assertFalse(error.message!!.contains("example.org"))
            assertEquals(listOf("PUT /api/v2/mobile-guest/email"), script.calls())
        }
        // Nothing saved yet: a conflict the app can tell apart from a claimed guest.
        val error = assertFailsWith<MapRouletteException> {
            client(Script(listOf(HttpResponse(409, body = """{"error":"nothing_saved"}""")))).setGuestEmail("rosa@example.org")
        }
        assertEquals(ErrorKind.CONFLICT, error.kind); assertEquals(409, error.status); assertEquals("nothing_saved", error.reason)
    }

    @Test fun deleteGuestSendsDelete() = runBlocking<Unit> {
        val script = Script(listOf(HttpResponse(204)))
        client(script).deleteGuest()
        assertEquals(listOf("DELETE /api/v2/mobile-guest"), script.calls())
    }

    @Test fun guestErrorsCarryOnlyTheCode() = runBlocking<Unit> {
        for (row in guest.getValue("errors").jsonArray.map { it.jsonObject }) {
            val script = Script(listOf(ok(row.getValue("body"), row.getValue("status").jsonPrimitive.int)))
            val error = assertFailsWith<MapRouletteException>(row.toString()) { client(script).getGuestStatus() }
            val kind = row.getValue("kind").jsonPrimitive.content
            assertEquals(kind.replace(Regex("([A-Z])"), "_$1").uppercase(), error.kind.name, row.toString())
            assertEquals(row["reason"]?.jsonPrimitive?.contentOrNull, error.reason, row.toString())
            assertFalse(error.message!!.contains("example.org"))
        }
    }

    @Test fun malformedGuestResponsesAreProtocolErrors() = runBlocking<Unit> {
        val badState = JsonObject(guest.getValue("status").jsonObject + ("state" to JsonPrimitive("frozen")))
        val badDate = JsonObject(guest.getValue("status").jsonObject + ("expiresAt" to JsonPrimitive("tomorrow")))
        for (body in listOf(badState, badDate)) {
            val error = assertFailsWith<MapRouletteException> { client(Script(listOf(ok(body)))).getGuestStatus() }
            assertEquals(ErrorKind.PROTOCOL, error.kind)
        }
        // The stored answer must be for the submitted task.
        val task = slcTask()
        val other = JsonObject(guest.getValue("pending_submit").jsonObject + ("taskId" to JsonPrimitive(102)))
        val error = assertFailsWith<MapRouletteException> {
            client(Script(listOf(ok(other)))).submitPendingChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes")))
        }
        assertEquals(ErrorKind.PROTOCOL, error.kind)
    }

    @Test fun choiceOnlyFilterExcludesPendingByDefault() = runBlocking<Unit> {
        val bounds = Bounds(-111.91, 40.75, -111.87, 40.78)
        for (exclude in listOf(true, false)) {
            val script = Script(listOf(HttpResponse(200, body = "[]"), HttpResponse(200, body = """{"total":0,"tasks":[]}""")))
            val c = client(script)
            c.findTaskMarkers(TaskFilter(bounds = bounds, choiceOnly = true, excludePending = exclude))
            c.findTasksInBounds(TaskFilter(bounds = bounds, choiceOnly = true, excludePending = exclude))
            for (request in script.requests) {
                assertEquals(if (exclude) "true" else null, request.url.toHttpUrl().queryParameter("excludePending"))
            }
        }
        val script = Script(listOf(HttpResponse(200, body = "[]")))
        client(script).findTaskMarkers(TaskFilter(bounds = bounds))
        assertNull(script.requests[0].url.toHttpUrl().queryParameter("excludePending"))
        assertTrue(TaskFilter(bounds = bounds).excludePending)
    }

    @Test fun okHttpSendsDeleteWithoutABody() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            OkHttpTransport().use { transport ->
                val environment = MapRouletteEnvironment(server.url("/api/v2/").toString())
                MapRouletteClient(environment, transport, accessToken = { "synthetic-guest-token" }).deleteGuest()
            }
            val received = server.takeRequest()
            assertEquals("DELETE", received.method); assertEquals("/api/v2/mobile-guest", received.path)
            assertEquals(0L, received.bodySize)
        }
    }
}
