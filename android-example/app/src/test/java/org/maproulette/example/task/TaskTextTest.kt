package org.maproulette.example.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.example.auth.AppSession
import org.maproulette.sdk.ChoiceOption
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.choiceOutcomes
import org.maproulette.sdk.work

class TaskTextTest {
    private val work = task().work() as TaskWork.Choice
    private fun form(answers: Map<String, String>, deletion: Boolean = false) =
        ChoiceForm(work, task().choiceOutcomes(deletion), answers)

    @Test
    fun statusWording() {
        assertEquals("Not an issue", TaskText.status(2))
        assertEquals("Unknown status (42)", TaskText.status(42))
    }

    @Test
    fun tagChangeShowsTheExactEdit() {
        assertEquals("backrest=yes", TaskText.tagChange(work.questions[0].options[0]))
        assertEquals("material=wood, remove note",
            TaskText.tagChange(ChoiceOption("x", "X", null, mapOf("material" to "wood"), listOf("note"))))
    }

    @Test
    fun answersSummaryListsExactTagChangesAndTheDevServer() {
        val text = TaskText.answersSummary(form(mapOf("backrest" to "no")), 42, 7, TaskText.DEV_OSM)
        assertTrue(text, text.contains("node/123"))
        assertTrue(text, text.contains("• backrest=no"))
        assertFalse(text, text.contains("material="))
        assertTrue("unanswered questions are listed as can't tell", text.contains("Can't tell") && text.contains("What is the seat mainly made of?"))
        assertTrue(text, text.contains("development OpenStreetMap server (master.apis.dev.openstreetmap.org), not the real map"))
        assertTrue(text, text.contains("task 42 as Fixed for user 7"))

        val both = TaskText.answersSummary(form(mapOf("backrest" to "yes", "material" to "metal")), 42, 7, null)
        assertTrue(both, both.contains("• backrest=yes\n• material=metal"))
        assertFalse(both, both.contains("Can't tell"))
        assertTrue(both, both.contains("test backend decides"))
    }

    @Test
    fun outcomeSummariesSayWhetherOsmIsEdited() {
        val goneOff = task().choiceOutcomes(false).single { it.id == "gone" }
        val goneOn = task().choiceOutcomes(true).single { it.id == "gone" }
        val off = TaskText.outcomeSummary(goneOff, work.element, 42, 7, TaskText.DEV_OSM)
        assertTrue(off, off.contains("“Not an issue”") && off.contains("does not edit OpenStreetMap"))
        val on = TaskText.outcomeSummary(goneOn, work.element, 42, 7, TaskText.DEV_OSM)
        assertTrue(on, on.startsWith("Delete node/123 from OpenStreetMap") && on.contains("development OpenStreetMap"))
        assertTrue(TaskText.outcomeExplanation(goneOn, work.element).contains("Deletes node/123"))
    }

    @Test
    fun changesetLinksOnlyForAKnownServer() {
        assertEquals("https://master.apis.dev.openstreetmap.org/changeset/5", TaskText.changesetUrl(TaskText.DEV_OSM, 5))
        assertEquals(null, TaskText.changesetUrl(null, 5))
        assertEquals(TaskText.DEV_OSM, AppSession.OSM_SERVERS["https://mr-api.osm.lol"])
        assertEquals(null, AppSession.OSM_SERVERS["https://maproulette.org"])
    }

    @Test
    fun unavailableReasons() {
        assertTrue(TaskText.unavailableReason(task(cooperativeWork = null)).contains("multiple-choice"))
        assertTrue(TaskText.unavailableReason(task(bundleId = 3)).contains("bundle"))
        assertTrue(TaskText.unavailableReason(task(status = 1)).contains("nothing left"))
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
    fun signInRequestsOsmEditingOnlyForWritableBuilds() {
        assertEquals("tasks:read tasks:write osm:tagfix", AppSession.requestedScope(writesConfigured = true))
        assertEquals("tasks:read", AppSession.requestedScope(writesConfigured = false))
        val full = AppSession.requestedScope(true)
        assertTrue(AppSession.acceptableGrant(setOf("tasks:read", "tasks:write", "osm:tagfix"), full))
        assertTrue(AppSession.acceptableGrant(setOf("tasks:read", "tasks:write"), full))
        assertTrue(AppSession.acceptableGrant(setOf("tasks:read"), full))
        assertFalse("tagfix needs write", AppSession.acceptableGrant(setOf("tasks:read", "osm:tagfix"), full))
        assertFalse("never wider than requested", AppSession.acceptableGrant(setOf("tasks:read", "tasks:write"), "tasks:read"))
        assertFalse(AppSession.acceptableGrant(setOf("tasks:write", "osm:tagfix"), full))
        assertFalse(AppSession.acceptableGrant(setOf("tasks:read", "tasks:write", "osm:tagfix", "admin"), full))
    }

    @Test
    fun grantsWithoutOsmEditingNeedReconsentOnlyOnWritableBuilds() {
        assertTrue(AppSession.needsReconsent(true, setOf("tasks:read", "tasks:write")))
        assertTrue(AppSession.needsReconsent(true, setOf("tasks:read")))
        assertFalse(AppSession.needsReconsent(true, setOf("tasks:read", "tasks:write", "osm:tagfix")))
        assertFalse("production build never asks for writes", AppSession.needsReconsent(false, setOf("tasks:read")))
    }

    @Test
    fun osmReconsentDoesNotRefreshTheMapRouletteToken() {
        assertTrue(AppSession.isOsmReauth("""{"error":"osm_reauth_required"}"""))
        listOf("""{"error":"invalid_token"}""", """{"status":"NotAuthorized"}""", "", "not json", """["osm_reauth_required"]""")
            .forEach { assertFalse(it, AppSession.isOsmReauth(it)) }
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
        listOf("start", "refreshLock", "release", "skip", "choice", "1", "2", "4", "5", "6", "9").forEach {
            assertTrue(it, AppSession.isTaskWrite("$api/task/42/$it"))
        }
        assertTrue("unparseable URLs are refused", AppSession.isTaskWrite("::"))
        listOf("$api/task/42", "$api/task/42/choice/check", "$api/challenge/1/tasks?limit=20", "$api/markers/box/1/2/3/4?limit=100",
            "https://maproulette.org/oauth/mobile/me").forEach { assertFalse(it, AppSession.isTaskWrite(it)) }
    }
}
