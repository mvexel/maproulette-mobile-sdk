package org.maproulette.example.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.maproulette.example.auth.AppSession
import org.maproulette.sdk.TaskResolution

private const val CHOICE_PHASE =
    "SDK choice phase: standard tasks are UNSUPPORTED and allowedResolutions() is empty; the demo needs the choice UI"

class TaskTextTest {
    @Test
    fun wordingMatchesTheResolutionMeanings() {
        assertEquals("I fixed this in OSM", TaskText.button(TaskResolution.FIXED))
        assertTrue(TaskText.explanation(TaskResolution.ALREADY_FIXED).contains("Someone else"))
        assertEquals("Not an issue", TaskText.status(2))
        assertEquals("Unknown status (42)", TaskText.status(42))
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun limitationsExplainUnsupportedAndPartialKinds() {
        assertNull(TaskText.limitation(task()))
        assertTrue(TaskText.limitation(task(bundleId = 3))!!.contains("bundle"))
        val tagFix = Json.parseToJsonElement("""{"meta":{"version":2,"type":1},"operations":[]}""").jsonObject
        assertTrue(TaskText.limitation(task(cooperativeWork = tagFix))!!.contains("not offered"))
        val unknown = Json.parseToJsonElement("""{"meta":{"version":9}}""").jsonObject
        assertTrue(TaskText.limitation(task(cooperativeWork = unknown))!!.contains("does not support"))
        assertTrue(TaskText.limitation(task(status = 1))!!.contains("nothing left"))
    }

    @Test
    fun instructionFallsBackToChallengeAndListsFormFields() {
        val text = TaskText.instruction(task(instruction = """Check {{#mrTaskId}} {{{select "Kind" name="k" values="a,b"}}}"""), CHALLENGE)
        assertTrue(text, text.startsWith("Check 42"))
        assertTrue(text, text.contains("Kind: a / b"))
        assertEquals("Add backrest", TaskText.instruction(task(), CHALLENGE))
    }

    @Test
    fun completedByComparesWithSignedInUser() {
        assertTrue(TaskText.completedBy(7, 7).startsWith("Completed by you"))
        assertTrue(TaskText.completedBy(8, 7).contains("not you"))
        assertTrue(TaskText.completedBy(null, 7).contains("did not report"))
    }

    @Test
    fun writesOnlyToAllowlistedStagingOrigin() {
        listOf("https://maproulette.org", "https://staging.MapRoulette.org", "https://maproulette.org.",
            "https://API.maproulette.org.", "https://mr-api.osm.lol.", "https://mr-api.osm.lol/", "http://mr-api.osm.lol",
            "https://mr-api.osm.lol:8443", "https://user@mr-api.osm.lol", "not a url", "")
            .forEach { assertFalse(it, AppSession.writesAllowedFor(it, loopbackAllowed = true)) }
        assertTrue(AppSession.writesAllowedFor("https://mr-api.osm.lol", loopbackAllowed = false))
        assertTrue(AppSession.writesAllowedFor("http://127.0.0.1:9000", loopbackAllowed = true))
        assertFalse(AppSession.writesAllowedFor("http://127.0.0.1:9000", loopbackAllowed = false))
    }

    @Test
    fun transportGuardRecognizesEveryLifecycleWrite() {
        val api = "https://maproulette.org/api/v2"
        listOf("start", "refreshLock", "release", "skip", "1", "2", "4", "5", "6", "9").forEach {
            assertTrue(it, AppSession.isTaskWrite("$api/task/42/$it"))
        }
        assertTrue("unparseable URLs are refused", AppSession.isTaskWrite("::"))
        listOf("$api/task/42", "$api/challenge/1/tasks?limit=20", "$api/markers/box/1/2/3/4?limit=100",
            "https://maproulette.org/oauth/mobile/me").forEach { assertFalse(it, AppSession.isTaskWrite(it)) }
    }
}
