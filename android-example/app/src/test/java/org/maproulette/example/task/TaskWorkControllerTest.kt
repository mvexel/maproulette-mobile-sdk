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
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.ProjectId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskStatus
import org.maproulette.sdk.WriteProblem

private const val ME = 7L
private const val OTHER = 8L
private val ID = TaskId(42)

internal fun task(status: Int = 0, lockedBy: Long? = null, completedBy: Long? = null, cooperativeWork: JsonObject? = null,
                  bundleId: Long? = null, instruction: String? = null) = Task(
    id = ID, challengeId = ChallengeId(1), name = "Bench", instruction = instruction, status = TaskStatus(status),
    geometry = Json.parseToJsonElement("""{"type":"FeatureCollection","features":[]}""").jsonObject,
    location = null, cooperativeWork = cooperativeWork, lockedBy = lockedBy, completedBy = completedBy, bundleId = bundleId,
)

internal val CHALLENGE = Challenge(ChallengeId(1), ProjectId(2), "Benches", "Add backrest", null, null, true, false, false)

private fun failure(kind: ErrorKind, problem: WriteProblem? = null) = MapRouletteException(kind, problem = problem)

/** Records every call; each operation's behavior is replaceable per test. No network. */
private class FakeOps : TaskOps {
    val calls = mutableListOf<String>()
    var reads = ArrayDeque<() -> Task>()
    var commit: suspend (TaskResolution) -> Unit = {}
    var skip: suspend () -> Unit = {}
    var release: suspend () -> Unit = {}

    fun thenRead(vararg next: () -> Task) = apply { reads.addAll(next) }

    override suspend fun getTask(id: TaskId): Task {
        calls += "get"
        return (reads.removeFirstOrNull() ?: error("unexpected read"))()
    }

    override suspend fun getChallenge(task: Task) = CHALLENGE.also { calls += "challenge" }
    override suspend fun commitResolution(id: TaskId, resolution: TaskResolution) {
        calls += "commit:${resolution.code}"
        commit(resolution)
    }

    override suspend fun skipTask(id: TaskId) {
        calls += "skip"
        skip()
    }

    override suspend fun releaseTask(id: TaskId) {
        calls += "release"
        release()
    }
}

private const val CHOICE_PHASE =
    "SDK choice phase: standard tasks are UNSUPPORTED and allowedResolutions() is empty; the demo needs the choice UI"

@OptIn(ExperimentalCoroutinesApi::class)
class TaskWorkControllerTest {
    private fun TestScope.controller(ops: FakeOps, writer: Writer? = Writer(ME)): TaskWorkController =
        TaskWorkController(ops, writer, this).also {
            it.load(ID)
            advanceUntilIdle()
        }

    private val notAnIssue = TaskAction.Resolve(TaskResolution.NOT_AN_ISSUE)

    @Test
    fun viewingNeverWrites() = runTest {
        val ops = FakeOps().thenRead({ task(lockedBy = OTHER) })
        val c = controller(ops)
        val state = c.state.value as TaskScreen.Viewing
        assertEquals(OTHER, state.task.lockedBy)
        assertEquals(listOf("get", "challenge"), ops.calls)
        assertFalse(c.changed)
    }

    @Test
    fun withoutWriterNothingIsOfferedOrSent() = runTest {
        val ops = FakeOps().thenRead({ task() })
        val c = controller(ops, writer = null)
        assertFalse(c.offers(task(), notAnIssue))
        assertFalse(c.offers(task(), TaskAction.Skip))
        c.perform(notAnIssue)
        c.perform(TaskAction.Skip)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Viewing)
        assertEquals(listOf("get", "challenge"), ops.calls)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun disallowedResolutionsAreNotSent() = runTest {
        val tagFix = Json.parseToJsonElement("""{"meta":{"version":2,"type":1},"operations":[]}""").jsonObject
        val ops = FakeOps().thenRead({ task(cooperativeWork = tagFix) })
        val c = controller(ops)
        c.perform(TaskAction.Resolve(TaskResolution.FIXED))
        advanceUntilIdle()
        assertEquals(listOf("get", "challenge"), ops.calls)
        assertTrue(c.offers(task(cooperativeWork = tagFix), notAnIssue))
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun resolvedRereadsAndReportsCompletedBy() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        val c = controller(ops)
        c.perform(notAnIssue)
        assertTrue(c.state.value is TaskScreen.Committing)
        assertTrue(c.busy)
        advanceUntilIdle()
        val state = c.state.value as TaskScreen.Resolved
        assertEquals(2, state.task.status?.code)
        assertEquals(ME, state.task.completedBy)
        assertFalse(state.readFailed)
        assertEquals(listOf("get", "challenge", "commit:2", "get"), ops.calls)
        assertTrue(c.changed)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun failedRereadAfterSuccessCanBeRecheckedWithoutResending() = runTest {
        val ops = FakeOps().thenRead({ task() }, { throw failure(ErrorKind.NETWORK) }, { task(status = 2, completedBy = ME) })
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Resolved).readFailed)
        c.recheck()
        advanceUntilIdle()
        assertFalse((c.state.value as TaskScreen.Resolved).readFailed)
        assertEquals(1, ops.calls.count { it.startsWith("commit") })
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun lockedByOtherAtStartIsTakenByOther() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.commit = { throw failure(ErrorKind.PERMISSION, WriteProblem.LockedByOtherUser("locked")) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.TakenByOther)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun staleOwnLockOffersUserRetry() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        var attempts = 0
        ops.commit = {
            if (attempts++ == 0) throw failure(ErrorKind.CONFLICT, WriteProblem.AlreadyHoldingTask(TaskId(9), null, null, null))
        }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertEquals(TaskId(9), (c.state.value as TaskScreen.StaleOwnLock).lockedTaskId)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Resolved)
        assertEquals(2, ops.calls.count { it.startsWith("commit") })
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun unknownOutcomeIsVerifiedNotResent() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        ops.commit = { throw failure(ErrorKind.SERVER, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Resolved)
        assertEquals(listOf("get", "challenge", "commit:2", "get"), ops.calls)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun unknownOutcomeWithFailedCheckWaitsForManualRecheck() = runTest {
        val ops = FakeOps().thenRead({ task() }, { throw failure(ErrorKind.NETWORK) }, { task(status = 2, completedBy = ME) })
        ops.commit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        val unknown = c.state.value as TaskScreen.OutcomeUnknown
        assertFalse(unknown.checking)
        assertFalse(c.busy)
        c.perform(notAnIssue) // Not allowed from this state: no resend.
        c.recheck()
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Resolved)
        assertEquals(1, ops.calls.count { it.startsWith("commit") })
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun unknownOutcomeNotAppliedWithOwnLockReleasesAndReturnsToViewing() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(lockedBy = ME) })
        ops.commit = { throw failure(ErrorKind.SERVER, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Viewing).notice!!.contains("not recorded"))
        assertEquals(listOf("get", "challenge", "commit:2", "get", "release"), ops.calls)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun unknownOutcomeLockedByOtherIsTakenByOther() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(lockedBy = OTHER) })
        ops.commit = { throw failure(ErrorKind.SERVER, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.TakenByOther)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun unknownOutcomeResolvedByOtherShowsTheOtherUser() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = OTHER) })
        ops.commit = { throw failure(ErrorKind.SERVER, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        val state = c.state.value as TaskScreen.Resolved
        assertEquals(OTHER, state.task.completedBy)
        assertTrue("not presented as this user's result", state.byOther)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun appliedVerificationIsNotByOther() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        ops.commit = { throw failure(ErrorKind.SERVER, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertFalse((c.state.value as TaskScreen.Resolved).byOther)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun failedReleaseOfOwnLockIsReported() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(lockedBy = ME) })
        ops.commit = { throw failure(ErrorKind.SERVER, WriteProblem.OutcomeUnknown) }
        ops.release = { throw failure(ErrorKind.NETWORK) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Viewing).notice!!.contains("still locked to you"))
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun authenticationFailureIsSessionExpiredAndUserMayRetry() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        var attempts = 0
        ops.commit = { if (attempts++ == 0) throw failure(ErrorKind.AUTHENTICATION) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.SessionExpired)
        assertEquals(1, ops.calls.count { it.startsWith("commit") })
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Resolved)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun insufficientScopeBlocksFurtherWrites() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.commit = { throw failure(ErrorKind.PERMISSION, WriteProblem.InsufficientScope) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.InsufficientScope)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it.startsWith("commit") })
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun invalidTransitionRereadsAndExplains() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = OTHER) })
        ops.commit = { throw failure(ErrorKind.HTTP, WriteProblem.InvalidTransition) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        val state = c.state.value as TaskScreen.Viewing
        assertEquals(1, state.task.status?.code)
        assertTrue(state.notice!!.contains("did not accept"))
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun networkErrorIsFailedAndRetryable() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.commit = { throw failure(ErrorKind.RATE_LIMIT) }
        val c = controller(ops)
        c.perform(notAnIssue)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Failed)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun skipAndUnknownSkipAreNeverResent() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.skip = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(TaskAction.Skip)
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Skipped).uncertain)
        c.perform(TaskAction.Skip)
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it == "skip" })
        assertTrue(c.changed)
    }

    @Ignore(CHOICE_PHASE)

    @Test
    fun cancelledScopeLetsTheWriteFinishButDiscardsItsResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val screen = CoroutineScope(SupervisorJob() + dispatcher)
        val ops = FakeOps().thenRead({ task() })
        val gate = CompletableDeferred<Unit>()
        var finished = false
        ops.commit = {
            gate.await()
            finished = true
        }
        val c = TaskWorkController(ops, Writer(ME), screen)
        c.load(ID)
        advanceUntilIdle()
        c.perform(notAnIssue)
        advanceUntilIdle()
        var idle = false
        screen.cancel() // Activity destroyed or account switched while the commit is in flight.
        c.whenIdle { idle = true }
        assertFalse(idle)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(finished)
        assertTrue(idle)
        assertTrue("late result is discarded", c.state.value is TaskScreen.Committing)
        assertEquals(listOf("get", "challenge", "commit:2"), ops.calls)
    }
}
