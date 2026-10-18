package org.maproulette.sdk

import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

/** Replays every fixtures/recorded/ file (real dev responses, see scripts/record-fixtures.py) through
 * the public client: each request must match the recorded method, path and query, successful
 * exchanges must decode, and recorded errors must map to an error kind other than PROTOCOL. */
class RecordedTest {
    private val dir = File(System.getProperty("fixtures"), "recorded")

    private class Replay(exchanges: List<JsonObject>) : Transport {
        val queue = ArrayDeque(exchanges)
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val e = queue.removeFirstOrNull() ?: fail("unexpected ${request.method} ${request.url}")
            val url = request.url.toHttpUrl()
            assertEquals(e.getValue("method").jsonPrimitive.content, request.method.name, request.url)
            assertEquals(e.getValue("path").jsonPrimitive.content, URI(request.url).path)
            val sent = url.queryParameterNames.associateWith { url.queryParameter(it) }
            val recorded = e["query"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            assertEquals(recorded, sent, request.url)
            val body = when (val b = e["body"]) {
                null, JsonNull -> ""
                is JsonPrimitive -> if (b.isString) b.content else b.toString()
                else -> b.toString()
            }
            return HttpResponse(e.getValue("status").jsonPrimitive.int, body = body)
        }
    }

    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

    private fun filter(args: JsonObject): TaskFilter {
        val b = args.getValue("bounds").jsonArray.map { it.jsonPrimitive.double }
        return TaskFilter(
            challengeIds = args.getValue("challengeIds").jsonArray.map { ChallengeId(it.jsonPrimitive.long) },
            bounds = Bounds(b[0], b[1], b[2], b[3]),
            statuses = (args["statuses"] as? JsonArray)?.map { it.jsonPrimitive.int },
            choiceOnly = args["choiceOnly"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    /** Runs the recorded call; returns true when the SDK was expected to stop before the last exchange. */
    private suspend fun call(name: String, args: JsonObject, wire: Replay): Boolean {
        val client = MapRouletteClient(MapRouletteEnvironment.STAGING, wire, accessToken = { "recorded-token" })
        when (name) {
            "searchChallenges" -> client.searchChallenges(pageSize = args.int("pageSize"))
            "getChallenge" -> assertEquals(ChallengeId(args.long("id")), client.getChallenge(ChallengeId(args.long("id"))).id)
            "getChallengeTags" -> client.getChallengeTags(ChallengeId(args.long("id")))
            "getTask" -> assertEquals(TaskId(args.long("id")), client.getTask(TaskId(args.long("id"))).id)
            "listTasks" -> {
                var after: PageCursor? = null
                repeat(args.int("pages")) {
                    after = client.listTasks(ChallengeId(args.long("id")), args.int("pageSize"), after).next ?: return true
                }
            }
            "listAllTasks" -> {
                val pages = client.listAllTasks(ChallengeId(args.long("id")), concurrency = args.int("concurrency")).toList()
                val tasks = pages.flatMap { it.items }
                assertEquals(tasks.size, tasks.map { it.id }.toSet().size)
            }
            "findTasksInBounds" -> {
                var after: PageCursor? = null
                repeat(args.int("pages")) {
                    val page = client.findTasksInBounds(filter(args), args.int("pageSize"), after)
                    assertNotNull(page.total)
                    after = page.next ?: return true
                }
            }
            "findTaskMarkers" -> client.findTaskMarkers(filter(args), args.int("limit"))
            "startGuest" -> {
                val config = MobileSignInConfiguration(
                    MapRouletteEnvironment.STAGING, args.getValue("clientId").jsonPrimitive.content, REDIRECT,
                )
                val signIn = MobileSignIn(config, InMemoryCredentialStore(), wire)
                val guest = signIn.startGuest()
                assertNotNull(signIn.accessToken(guest.generation))
            }
            "getGuestStatus" -> client.getGuestStatus()
            "submitPendingChoice" -> {
                val task = client.getTask(TaskId(args.long("task")))
                val answers = args.getValue("answers").jsonObject.mapValues { it.value.jsonPrimitive.content }
                assertEquals(task.id, client.submitPendingChoice(task, ChoiceSubmission.Answers(answers)).taskId)
            }
            "listPendingChoices" -> client.listPendingChoices(args.int("pageSize"))
            "withdrawPendingChoice" -> client.withdrawPendingChoice(TaskId(args.long("task")))
            "deleteGuest" -> client.deleteGuest()
            "getCurrentUser" -> client.getCurrentUser()
            "checkChoice" -> client.checkChoice(TaskId(args.long("task")))
            else -> fail("no replay for recorded call $name")
        }
        return false
    }

    @Test fun everyRecordedResponseReplays() = runBlocking<Unit> {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no recorded fixtures in $dir")
        for (file in files) {
            val doc = Json.parseToJsonElement(file.readText()).jsonObject
            val exchanges = doc.getValue("exchanges").jsonArray.map { it.jsonObject }
            // Shape-only recordings (routes the SDK has no client for) are compared by the drift job alone.
            if (doc["decode"]?.jsonPrimitive?.boolean == false) continue
            val wire = Replay(exchanges)
            val succeeds = exchanges.all { it.int("status") in 200..299 }
            val name = doc.getValue("call").jsonPrimitive.content
            try {
                val stoppedEarly = call(name, doc.getValue("args").jsonObject, wire)
                assertTrue(succeeds, "${file.name}: expected an error for status ${exchanges.last().int("status")}")
                if (!stoppedEarly) assertTrue(wire.queue.isEmpty(), "${file.name}: ${wire.queue.size} exchanges not requested")
            } catch (e: MapRouletteException) {
                assertFalse(succeeds, "${file.name}: $e")
                assertNotEquals(ErrorKind.PROTOCOL, e.kind, "${file.name}: an error response didn't decode")
                assertTrue(wire.queue.isEmpty(), "${file.name}: failed before its last exchange")
            }
        }
    }
}
