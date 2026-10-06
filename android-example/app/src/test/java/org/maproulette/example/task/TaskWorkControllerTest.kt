package org.maproulette.example.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ChoiceEligibility
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceProblem
import org.maproulette.sdk.ChoiceResult
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.IneligibleReason
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.ProjectId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskStatus
import org.maproulette.sdk.WriteProblem
import org.maproulette.sdk.HttpMethod
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteEnvironment
import org.maproulette.sdk.Transport

private const val ME = 7L
private const val OTHER = 8L
private val ID = TaskId(42)

/** The pilot bench payload (docs/design/mobile-choice-challenges.md §3), shortened to two questions. */
internal val BENCH: JsonObject = Json.parseToJsonElement("""
    {"meta":{"version":2,"type":3,"choiceVersion":1},"element":"node/123","match":{"amenity":"bench"},
     "questions":[
      {"id":"backrest","prompt":"Does the bench have a backrest?","expect":{"backrest":null},
       "options":[{"id":"yes","label":"Yes","setTags":{"backrest":"yes"}},{"id":"no","label":"No","setTags":{"backrest":"no"}}]},
      {"id":"material","prompt":"What is the seat mainly made of?","expect":{"material":null},
       "options":[{"id":"wood","label":"Wood","setTags":{"material":"wood"}},{"id":"metal","label":"Metal","setTags":{"material":"metal"}}]}],
     "outcomes":[{"id":"not-a-bench","label":"Not a bench","status":2},{"id":"gone","label":"Bench is gone","delete":true}]}
""").jsonObject

/** The outcomes a client with this deletion setting offers. */
internal fun outcomes(task: Task, deletion: Boolean = false): List<ChoiceOutcome> =
    MapRouletteClient(transport = Transport { error("no network in unit tests") }, allowElementDeletion = deletion)
        .choiceOutcomes(task)

internal fun task(status: Int = 0, lockedBy: Long? = null, completedBy: Long? = null, cooperativeWork: JsonObject? = BENCH,
                  bundleId: Long? = null, instruction: String? = null, changesetId: Long? = null) = Task(
    id = ID, challengeId = ChallengeId(1), name = "Bench", instruction = instruction, status = TaskStatus(status),
    geometry = Json.parseToJsonElement("""{"type":"FeatureCollection","features":[]}""").jsonObject,
    location = null, cooperativeWork = cooperativeWork, lockedBy = lockedBy, completedBy = completedBy, bundleId = bundleId,
    changesetId = changesetId,
)

internal val CHALLENGE = Challenge(ChallengeId(1), ProjectId(2), "Benches", "Add backrest", null, null, true, false, false)

private fun failure(kind: ErrorKind, problem: WriteProblem? = null) = MapRouletteException(kind, problem = problem)

/** Records every call; each operation's behavior is replaceable per test. No network. */
private class FakeOps(val allowElementDeletion: Boolean = false) : TaskOps {
    override fun choiceOutcomes(task: Task) = outcomes(task, allowElementDeletion)
    val calls = mutableListOf<String>()
    val submissions = mutableListOf<ChoiceSubmission>()
    var reads = ArrayDeque<() -> Task>()
    var check: suspend () -> ChoiceEligibility = { ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null) }
    var submit: suspend (ChoiceSubmission) -> ChoiceResult = { ChoiceResult(TaskStatus(1), 99) }
    var skip: suspend () -> Unit = {}

    fun thenRead(vararg next: () -> Task) = apply { reads.addAll(next) }

    override suspend fun getTask(id: TaskId): Task {
        calls += "get"
        return (reads.removeFirstOrNull() ?: error("unexpected read"))()
    }

    override suspend fun getChallenge(task: Task) = CHALLENGE.also { calls += "challenge" }
    override suspend fun checkChoice(id: TaskId): ChoiceEligibility {
        calls += "check"
        return check()
    }

    override suspend fun submitChoice(task: Task, submission: ChoiceSubmission): ChoiceResult {
        calls += "submit"
        submissions += submission
        return submit(submission)
    }

    override suspend fun skipTask(id: TaskId) {
        calls += "skip"
        skip()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskWorkControllerTest {
    private fun TestScope.controller(ops: FakeOps, writer: Writer? = Writer(ME, canEditOsm = true)): TaskWorkController =
        TaskWorkController(ops, writer, this, StandardTestDispatcher(testScheduler)).also {
            it.load(ID)
            advanceUntilIdle()
        }

    private val TaskWorkController.answering get() = state.value as TaskScreen.Answering

    private fun TaskWorkController.outcome(id: String) = answering.form.outcomes.single { it.id == id }

    private fun TestScope.answered(ops: FakeOps): TaskWorkController = controller(ops).also {
        it.select("backrest", "yes")
        it.perform(ChoiceAction.Answers(mapOf("backrest" to "yes")))
        advanceUntilIdle()
    }

    @Test
    fun nonChoiceTasksAreNotAvailableAndNeverChecked() = runTest {
        val tagFix = Json.parseToJsonElement("""{"meta":{"version":2,"type":1},"operations":[]}""").jsonObject
        listOf(task(cooperativeWork = null), task(cooperativeWork = tagFix), task(bundleId = 3), task(status = 1)).forEach { t ->
            val ops = FakeOps().thenRead({ t })
            val state = controller(ops).state.value as TaskScreen.NotAvailable
            assertTrue(state.reason, state.reason.isNotBlank())
            assertEquals(listOf("get", "challenge"), ops.calls)
        }
    }

    @Test
    fun withoutWriterPreviewHasNoCheckAndNoWrites() = runTest {
        val ops = FakeOps().thenRead({ task() })
        val c = controller(ops, writer = null)
        assertTrue(c.state.value is TaskScreen.Preview)
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertEquals(listOf("get", "challenge"), ops.calls)
    }

    @Test
    fun ineligibleAtOpenNoLongerNeedsAnswering() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.check = { ChoiceEligibility(false, false, IneligibleReason.KEY_CHANGED) }
        val c = controller(ops)
        assertTrue(c.state.value is TaskScreen.NoLongerNeeded)
        assertTrue("the server now hides it: refresh the map", c.changed)
        assertEquals(listOf("get", "challenge", "check"), ops.calls)
    }

    @Test
    fun failedCheckIsNotActionableUntilARetrySucceeds() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() })
        var checks = 0
        ops.check = {
            if (checks++ == 0) throw failure(ErrorKind.SERVER, ChoiceProblem.OsmUnavailable)
            ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null)
        }
        val c = controller(ops)
        assertTrue(c.state.value is TaskScreen.CheckFailed)
        assertFalse("not stale: the map keeps it", c.changed)
        c.perform(ChoiceAction.Outcome(outcomes(task()).first()))
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertTrue(ops.submissions.isEmpty())
        c.load(ID) // Retry
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Answering)
    }

    @Test
    fun deletionOffShowsGoneAsNotAnIssue() = runTest {
        val c = controller(FakeOps(allowElementDeletion = false).thenRead({ task() }))
        assertEquals(listOf("not-a-bench", "gone", "too-hard"), c.answering.form.outcomes.map { it.id })
        val gone = c.outcome("gone")
        assertFalse(gone.deletesElement)
        assertEquals(TaskResolution.NOT_AN_ISSUE, gone.resolution)
    }

    @Test
    fun deletionOnShowsDeletingGoneOnlyWhenTheCheckAllowsIt() = runTest {
        val allowed = controller(FakeOps(allowElementDeletion = true).thenRead({ task() }))
        assertTrue(allowed.outcome("gone").deletesElement)
        assertEquals(TaskResolution.FIXED, allowed.outcome("gone").resolution)

        assertEquals(emptySet<String>(), allowed.answering.form.notDeletable)
    }

    @Test
    fun undeletableGoneIsOfferedAsNotAnIssueWithoutDeletion() = runTest {
        val inWay = FakeOps(allowElementDeletion = true).thenRead({ task() }, { task(status = 2, completedBy = ME) })
        inWay.check = { ChoiceEligibility(eligible = true, deleteAllowed = false, reason = null) }
        inWay.submit = { ChoiceResult(TaskStatus(2), null) }
        val c = controller(inWay)
        assertEquals(listOf("not-a-bench", "gone", "too-hard"), c.answering.form.outcomes.map { it.id })
        val gone = c.outcome("gone")
        assertFalse(gone.deletesElement)
        assertEquals(TaskResolution.NOT_AN_ISSUE, gone.resolution)
        assertEquals(setOf("gone"), c.answering.form.notDeletable)
        c.perform(ChoiceAction.Outcome(gone))
        advanceUntilIdle()
        assertFalse((inWay.submissions.single() as ChoiceSubmission.Outcome).outcome.deletesElement)
        assertTrue(c.state.value is TaskScreen.Done)

        val off = FakeOps(allowElementDeletion = false).thenRead({ task() })
        off.check = { ChoiceEligibility(eligible = true, deleteAllowed = false, reason = null) }
        assertEquals("deletion off: plain Not an issue, nothing blocked", emptySet<String>(), controller(off).answering.form.notDeletable)
    }

    @Test
    fun answersAndCantTell() = runTest {
        val c = controller(FakeOps().thenRead({ task() }))
        assertFalse("nothing answered", c.offers(c.answering.task, c.answering.form, ChoiceAction.Answers(emptyMap())))
        c.select("backrest", "yes")
        c.select("material", "wood")
        c.select("material", null) // Can't tell
        c.select("material", "granite") // Not an option: ignored
        c.select("color", "red") // Not a question: ignored
        assertEquals(mapOf("backrest" to "yes"), c.answering.form.answers)
        assertTrue(c.offers(c.answering.task, c.answering.form, ChoiceAction.Answers(c.answering.form.answers)))
        assertFalse(c.offers(c.answering.task, c.answering.form, ChoiceAction.Answers(mapOf("backrest" to "maybe"))))
    }

    @Test
    fun withoutOsmScopeEditsAreNotSentButPlainOutcomesAre() = runTest {
        val ops = FakeOps(allowElementDeletion = true).thenRead({ task() }, { task(status = 2, completedBy = ME) })
        val c = controller(ops, Writer(ME, canEditOsm = false))
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "yes")))
        c.perform(ChoiceAction.Outcome(c.outcome("gone")))
        advanceUntilIdle()
        assertTrue(ops.submissions.isEmpty())
        c.perform(ChoiceAction.Outcome(c.outcome("not-a-bench")))
        advanceUntilIdle()
        assertEquals(1, ops.submissions.size)
        assertTrue(c.state.value is TaskScreen.Done)
    }

    @Test
    fun submittedAnswersRereadAndReportTheChangeset() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = ME) })
        val c = controller(ops)
        c.select("backrest", "yes")
        c.perform(ChoiceAction.Answers(c.answering.form.answers))
        assertTrue(c.state.value is TaskScreen.Submitting)
        assertTrue(c.busy)
        advanceUntilIdle()
        val done = c.state.value as TaskScreen.Done
        assertEquals(1, done.task.status?.code)
        assertEquals(ME, done.task.completedBy)
        assertEquals(99L, done.changesetId)
        assertEquals(listOf(ChoiceSubmission.Answers(mapOf("backrest" to "yes"))), ops.submissions)
        assertEquals(listOf("get", "challenge", "check", "submit", "get"), ops.calls)
        assertTrue(c.changed)
    }

    @Test
    fun failedRereadAfterSuccessCanBeRecheckedWithoutResending() = runTest {
        val ops = FakeOps().thenRead({ task() }, { throw failure(ErrorKind.NETWORK) }, { task(status = 1, completedBy = ME) })
        val c = answered(ops)
        assertTrue((c.state.value as TaskScreen.Done).readFailed)
        c.recheck()
        assertTrue("shows progress", (c.state.value as TaskScreen.Done).checking)
        c.recheck() // Ignored while checking.
        advanceUntilIdle()
        val done = c.state.value as TaskScreen.Done
        assertFalse(done.readFailed)
        assertEquals(99L, done.changesetId)
        assertEquals(1, ops.calls.count { it == "submit" })
    }

    @Test
    fun ineligibleAtSubmitNoLongerNeedsAnswering() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.CONFLICT, ChoiceProblem.TaskIneligible(IneligibleReason.KEY_CHANGED)) }
        assertTrue(answered(ops).state.value is TaskScreen.NoLongerNeeded)
    }

    @Test
    fun uncertainResultsWaitForManualCheckAndAreNeverResent() = runTest {
        listOf(
            failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown),
            failure(ErrorKind.CONFLICT, ChoiceProblem.SubmissionPending),
            failure(ErrorKind.SERVER, ChoiceProblem.StatusPending(55)),
        ).forEach { error ->
            val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = ME, changesetId = 55) })
            ops.submit = { throw error }
            val c = answered(ops)
            val pending = c.state.value as TaskScreen.CheckAgain
            assertFalse(pending.checking)
            assertFalse(c.busy)
            assertEquals(listOf("get", "challenge", "check", "submit"), ops.calls)
            c.perform(ChoiceAction.Answers(mapOf("backrest" to "yes"))) // Not allowed from this state.
            c.recheck()
            advanceUntilIdle()
            assertEquals(55L, (c.state.value as TaskScreen.Done).changesetId)
            assertEquals(1, ops.calls.count { it == "submit" })
        }
    }

    @Test
    fun uncertainEditIsOnlyResentManuallyAndIdentically() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(lockedBy = ME) }, { task() }, { task(status = 1, completedBy = ME) })
        var attempts = 0
        ops.submit = { if (attempts++ == 0) throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) else ChoiceResult(TaskStatus(1), 77) }
        val c = answered(ops)
        assertFalse("no resend before a check", (c.state.value as TaskScreen.CheckAgain).canResend)
        c.recheck()
        advanceUntilIdle()
        val held = c.state.value as TaskScreen.CheckAgain
        assertTrue(held.message, held.message.contains("still locked to you") && held.canResend)
        c.recheck()
        advanceUntilIdle()
        val unlocked = c.state.value as TaskScreen.CheckAgain
        assertTrue("an edit never falls back to editable answers", unlocked.canResend)
        assertEquals(1, ops.calls.count { it == "submit" })
        c.resendSame()
        advanceUntilIdle()
        assertEquals(77L, (c.state.value as TaskScreen.Done).changesetId)
        assertEquals(List(2) { ChoiceSubmission.Answers(mapOf("backrest" to "yes")) }, ops.submissions)
    }

    @Test
    fun uncertainPlainOutcomeNotAppliedReturnsToAnswering() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() })
        ops.submit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(ChoiceAction.Outcome(c.outcome("not-a-bench")))
        advanceUntilIdle()
        c.recheck()
        advanceUntilIdle()
        assertTrue(c.answering.notice!!.contains("not recorded"))
    }

    @Test
    fun submissionPendingNeverInvitesAnotherSubmission() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() })
        ops.submit = { throw failure(ErrorKind.CONFLICT, ChoiceProblem.SubmissionPending) }
        val c = answered(ops)
        c.recheck()
        advanceUntilIdle()
        val state = c.state.value as TaskScreen.CheckAgain
        assertFalse(state.canResend)
        assertTrue(state.message, state.message.contains("Pick another task"))
        c.resendSame()
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it == "submit" })
    }

    @Test
    fun statusPendingKeepsTheChangesetAndOffersTheSameSubmission() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() }, { task(status = 1, completedBy = ME) })
        var attempts = 0
        ops.submit = { if (attempts++ == 0) throw failure(ErrorKind.SERVER, ChoiceProblem.StatusPending(55)) else ChoiceResult(TaskStatus(1), 55) }
        val c = answered(ops)
        assertTrue((c.state.value as TaskScreen.CheckAgain).canResend)
        c.recheck()
        advanceUntilIdle()
        val pending = c.state.value as TaskScreen.CheckAgain
        assertTrue(pending.message, pending.message.contains("changeset 55") && !pending.message.contains("Not recorded"))
        assertEquals(55L, pending.changesetId)
        c.resendSame()
        advanceUntilIdle()
        assertEquals(55L, (c.state.value as TaskScreen.Done).changesetId)
    }

    @Test
    fun differentStatusWithoutAnotherCompleterIsNotByOther() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        ops.submit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = answered(ops)
        c.recheck()
        advanceUntilIdle()
        assertFalse((c.state.value as TaskScreen.Done).byOther)
    }

    @Test
    fun oneDeletionClientSendsGoneWithAndWithoutDeletion() = runTest {
        val bodies = mutableListOf<String?>()
        val start = """{"id":42,"parent":1,"name":"node/123","instruction":"","status":0,
            "geometries":{"type":"FeatureCollection","features":[]},"lockPrimaryTaskId":42,"lockBundledTasks":[]}"""
        val transport = Transport { request ->
            if (request.method == HttpMethod.POST) {
                bodies += request.body
                HttpResponse(200, body = """{"status":2,"changesetId":null}""")
            } else HttpResponse(200, body = start)
        }
        val ops = ClientTaskOps(MapRouletteClient(MapRouletteEnvironment.STAGING, transport = transport,
            accessToken = { "token" }, allowElementDeletion = true))
        val t = task()
        val gone = ops.choiceOutcomes(t).single { it.id == "gone" }
        ops.submitChoice(t, ChoiceSubmission.Outcome(ops.choiceOutcomes(t).single { it.id == "not-a-bench" }))
        ops.submitChoice(t, ChoiceSubmission.Outcome(TaskWorkController.withoutDeletion(gone)!!))
        ops.submitChoice(t, ChoiceSubmission.Outcome(gone))
        ops.submitChoice(t, ChoiceSubmission.Answers(mapOf("backrest" to "no")))
        assertEquals(listOf("""{"outcome":"not-a-bench"}""", """{"outcome":"gone"}""", """{"outcome":"gone","delete":true}""",
            """{"answers":{"backrest":"no"}}"""), bodies)
    }

    @Test
    fun failedCheckAgainReadStaysPending() = runTest {
        val ops = FakeOps().thenRead({ task() }, { throw failure(ErrorKind.NETWORK) })
        ops.submit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = answered(ops)
        c.recheck()
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.CheckAgain).message.contains("could not be re-read"))
    }

    @Test
    fun osmPermissionProblemsAskToSignInAgain() = runTest {
        listOf(ChoiceProblem.OsmReauthRequired to ErrorKind.AUTHENTICATION, ChoiceProblem.OsmScopeRequired to ErrorKind.PERMISSION,
            WriteProblem.InsufficientScope to ErrorKind.PERMISSION).forEach { (problem, kind) ->
            val ops = FakeOps().thenRead({ task() })
            ops.submit = { throw failure(kind, problem) }
            val state = answered(ops).state.value as TaskScreen.SignInRequired
            assertTrue(state.reason, state.reason.contains("Sign in again"))
        }
    }

    @Test
    fun osmUnavailableExplainsAndKeepsTheAnswers() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.SERVER, ChoiceProblem.OsmUnavailable) }
        val c = answered(ops)
        assertEquals(mapOf("backrest" to "yes"), c.answering.form.answers)
        assertTrue(c.answering.notice!!.contains("nothing was changed"))
    }

    @Test
    fun elementInUseOffersTheSameOutcomeWithoutDeletion() = runTest {
        val ops = FakeOps(allowElementDeletion = true).thenRead({ task() }, { task(status = 2, completedBy = ME) })
        ops.submit = { if ((it as ChoiceSubmission.Outcome).outcome.deletesElement) throw failure(ErrorKind.CONFLICT, ChoiceProblem.ElementInUse)
            else ChoiceResult(TaskStatus(2), null) }
        val c = controller(ops)
        c.perform(ChoiceAction.Outcome(c.outcome("gone")))
        advanceUntilIdle()
        val inUse = c.state.value as TaskScreen.ElementInUse
        assertEquals("gone", inUse.outcome.id)
        c.sendWithoutDeletion()
        advanceUntilIdle()
        val plain = (ops.submissions.last() as ChoiceSubmission.Outcome).outcome
        assertEquals("gone", plain.id)
        assertFalse(plain.deletesElement)
        assertEquals(TaskResolution.NOT_AN_ISSUE, plain.resolution)
        val done = c.state.value as TaskScreen.Done
        assertNull(done.changesetId)
        assertEquals(2, ops.submissions.size)
    }

    @Test
    fun lockedByOtherAtStartIsTakenByOther() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.PERMISSION, WriteProblem.LockedByOtherUser("locked")) }
        assertTrue(answered(ops).state.value is TaskScreen.TakenByOther)
    }

    @Test
    fun staleOwnLockOffersUserRetry() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = ME) })
        var attempts = 0
        ops.submit = {
            if (attempts++ == 0) throw failure(ErrorKind.CONFLICT, WriteProblem.AlreadyHoldingTask(TaskId(9), null, null, null))
            ChoiceResult(TaskStatus(1), 99)
        }
        val c = answered(ops)
        val stale = c.state.value as TaskScreen.StaleOwnLock
        assertEquals(TaskId(9), stale.lockedTaskId)
        c.perform(stale.action)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Done)
    }

    @Test
    fun definiteFailuresReturnToAnsweringWithANotice() = runTest {
        listOf(failure(ErrorKind.CONFLICT, WriteProblem.LockLost), failure(ErrorKind.AUTHENTICATION),
            failure(ErrorKind.RATE_LIMIT), failure(ErrorKind.HTTP, ChoiceProblem.InvalidSubmission(null))).forEach { error ->
            val ops = FakeOps().thenRead({ task() })
            ops.submit = { throw error }
            val c = answered(ops)
            assertTrue(c.answering.notice!!.lowercase().contains("nothing was recorded"))
            assertEquals(mapOf("backrest" to "yes"), c.answering.form.answers)
        }
    }

    @Test
    fun invalidTransitionRereadsAndIsNotAvailable() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = OTHER) })
        ops.submit = { throw failure(ErrorKind.CONFLICT, WriteProblem.InvalidTransition) }
        val state = answered(ops).state.value as TaskScreen.NotAvailable
        assertEquals(1, state.task.status?.code)
        assertTrue(state.reason.contains("did not accept"))
    }

    @Test
    fun unknownSkipIsNeverResent() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.skip = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Skipped).uncertain)
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it == "skip" })
        assertTrue(c.changed)
    }

    @Test
    fun cancelledScopeLetsTheWriteFinishButDiscardsItsResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val screen = CoroutineScope(SupervisorJob() + dispatcher)
        val ops = FakeOps().thenRead({ task() })
        val gate = CompletableDeferred<Unit>()
        var finished = false
        ops.submit = {
            gate.await()
            finished = true
            ChoiceResult(TaskStatus(1), 99)
        }
        val c = TaskWorkController(ops, Writer(ME, canEditOsm = true), screen, dispatcher)
        c.load(ID)
        advanceUntilIdle()
        c.select("backrest", "no")
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        var idle = false
        screen.cancel() // Activity destroyed or account switched while the submission is in flight.
        c.whenIdle { idle = true }
        assertFalse(idle)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(finished)
        assertTrue(idle)
        assertTrue("late result is discarded", c.state.value is TaskScreen.Submitting)
        assertEquals(listOf("get", "challenge", "check", "submit"), ops.calls)
    }
}
