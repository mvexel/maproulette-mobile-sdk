package org.maproulette.sdk

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class PublicApiTest {
    private class Recorder(private val response: HttpResponse) : Transport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return response }
    }

    @Test fun versionMatchesTheRepositoryVersionFileAndUserAgent() = runBlocking<Unit> {
        assertEquals(File(System.getProperty("versionFile")).readText().trim(), MapRouletteSdk.VERSION)
        val wire = Recorder(HttpResponse(200, body = """{"id":1,"guest":true}"""))
        MapRouletteClient(transport = wire, apiKey = { "k" }).getCurrentUser()
        assertEquals("MapRoulette-Mobile-SDK/${MapRouletteSdk.VERSION}", wire.requests.single().headers["User-Agent"])
    }

    @Test fun environmentsAndTheWriteAllowlist() = runBlocking<Unit> {
        assertEquals("https://maproulette.org/api/v2/", MapRouletteEnvironment.PRODUCTION.serviceUrl)
        assertEquals("https://mr-api.osm.lol/api/v2/", MapRouletteEnvironment.STAGING.serviceUrl)
        assertFalse(MapRouletteEnvironment.PRODUCTION.allowsWrites)
        assertTrue(MapRouletteEnvironment.STAGING.allowsWrites)
        val table = Json.parseToJsonElement(File(System.getProperty("fixtures"), "environments.json").readText()).jsonObject
        for (row in table.getValue("cases").jsonArray.map { it.jsonObject }) {
            val url = row.getValue("url").jsonPrimitive.content
            if (row.getValue("valid").jsonPrimitive.boolean) {
                val environment = MapRouletteEnvironment(url)
                assertEquals(row.getValue("writes").jsonPrimitive.boolean, environment.allowsWrites, url)
                assertTrue(environment.serviceUrl.endsWith("/"), url)
            } else {
                val error = assertFailsWith<IllegalArgumentException>(url) { MapRouletteEnvironment(url) }
                assertTrue(error.message!!.contains("https"), url)
            }
        }
        // Writes to production fail before any request is sent; reads still work.
        val wire = Recorder(HttpResponse(200, body = ""))
        val client = MapRouletteClient(transport = wire, accessToken = { "synthetic-access-token" })
        val error = assertFailsWith<IllegalStateException> { client.skipTask(TaskId(1)) }
        assertTrue(error.message!!.contains("staging"))
        assertFalse(error.message!!.contains("synthetic"))
        val task = Task(TaskId(1), ChallengeId(1), "node/1", null, TaskStatus(0), JsonObject(emptyMap()), null, null)
        assertFailsWith<IllegalStateException> { client.submitChoice(task, ChoiceSubmission.Answers(mapOf("q" to "a"))) }
        lowLevelWritesAreRefused(client)
        assertTrue(wire.requests.isEmpty())
    }

    @OptIn(LowLevelTaskLifecycle::class)
    private suspend fun lowLevelWritesAreRefused(client: MapRouletteClient) {
        val id = TaskId(1)
        assertFailsWith<IllegalStateException> { client.startTask(id) }
        assertFailsWith<IllegalStateException> { client.refreshTaskLock(id) }
        assertFailsWith<IllegalStateException> { client.releaseTask(id) }
        assertFailsWith<IllegalStateException> { client.resolveTask(id, TaskResolution.NOT_AN_ISSUE) }
        assertFailsWith<IllegalStateException> { client.commitResolution(id, TaskResolution.NOT_AN_ISSUE) }
    }

    @Test fun validationErrorsCarryMessages() = runBlocking<Unit> {
        val client = MapRouletteClient(transport = Recorder(HttpResponse(200, body = "[]")))
        val error = assertFailsWith<IllegalArgumentException> { client.searchChallenges(pageSize = 200) }
        assertEquals("pageSize must be 1..100", error.message)
        assertEquals("TaskId must be positive", assertFailsWith<IllegalArgumentException> { TaskId(0) }.message)
    }
}
