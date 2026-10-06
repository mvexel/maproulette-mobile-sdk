package org.maproulette.example.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChoiceEligibility
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceProblem
import org.maproulette.sdk.ChoiceResult
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.MobileSupport
import org.maproulette.sdk.ResolutionCheck
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.WriteProblem
import org.maproulette.sdk.canSkip
import org.maproulette.sdk.mobileSupport
import org.maproulette.sdk.verifyResolution
import org.maproulette.sdk.work

/** The SDK operations the task screen uses; a fake replaces it in unit tests. */
interface TaskOps {
    /** Outcomes decoded with the client's deletion setting (the SDK rejects others, except their
     * [ChoiceOutcome.withoutDeletion] form). */
    fun choiceOutcomes(task: Task): List<ChoiceOutcome>
    suspend fun getTask(id: TaskId): Task
    suspend fun getChallenge(task: Task): Challenge
    suspend fun checkChoice(id: TaskId): ChoiceEligibility
    suspend fun submitChoice(task: Task, submission: ChoiceSubmission): ChoiceResult
    suspend fun skipTask(id: TaskId)
}

/**
 * Bound to one account's client: a controller never sends through a later account's client.
 * The client's deletion setting is the cap; "gone" without deletion (after `element_in_use`, or
 * when the check disallows deletion) is sent as [ChoiceOutcome.withoutDeletion].
 */
class ClientTaskOps(private val client: MapRouletteClient) : TaskOps {
    override fun choiceOutcomes(task: Task) = client.choiceOutcomes(task)
    override suspend fun getTask(id: TaskId) = client.getTask(id)
    override suspend fun getChallenge(task: Task) = client.getChallenge(task.challengeId)
    override suspend fun checkChoice(id: TaskId) = client.checkChoice(id)
    override suspend fun submitChoice(task: Task, submission: ChoiceSubmission) = client.submitChoice(task, submission)
    override suspend fun skipTask(id: TaskId) = client.skipTask(id)
}

sealed interface ChoiceAction {
    /** Question id → option id; questions left out are "Can't tell". */
    data class Answers(val byQuestion: Map<String, String>) : ChoiceAction
    data class Outcome(val outcome: ChoiceOutcome) : ChoiceAction
    data object Skip : ChoiceAction
}

/** Whether [action] edits OpenStreetMap (needs `osm:tagfix`). */
val ChoiceAction.editsOsm: Boolean
    get() = this is ChoiceAction.Answers || (this is ChoiceAction.Outcome && outcome.deletesElement)

/** [me] is the signed-in MapRoulette user id; [canEditOsm]: the grant includes `osm:tagfix`. */
data class Writer(val me: Long, val canEditOsm: Boolean)

/** The questions and offered outcomes of an eligible task, plus the user's current answers. */
data class ChoiceForm(
    val work: TaskWork.Choice,
    val outcomes: List<ChoiceOutcome>,
    val answers: Map<String, String> = emptyMap(),
    /** Ids of delete outcomes offered without deletion because the element cannot be deleted
     * (it is in a way or relation); they record Not an issue. */
    val notDeletable: Set<String> = emptySet(),
)

/** Screen states (docs/design/task-completion.md §11, docs/design/mobile-choice-challenges.md §7 app rules). */
sealed interface TaskScreen {
    data object Loading : TaskScreen
    data class LoadFailed(val message: String) : TaskScreen
    /** Not a choice task, or nothing left to do: read-only, "Not available on mobile". */
    data class NotAvailable(val task: Task, val challenge: Challenge, val reason: String) : TaskScreen
    /** Signed out or read-only: the questions without actions. No eligibility check is made. */
    data class Preview(val task: Task, val challenge: Challenge, val work: TaskWork.Choice) : TaskScreen
    /** Ineligible (stale) at open or submit. No actions. */
    data class NoLongerNeeded(val task: Task, val challenge: Challenge) : TaskScreen
    /** The eligibility check failed (network, OSM unavailable, …): not actionable until a retry succeeds. */
    data class CheckFailed(val task: Task, val challenge: Challenge) : TaskScreen
    /** [notice] explains why the user is back here; the answers are kept. */
    data class Answering(val task: Task, val challenge: Challenge, val form: ChoiceForm, val notice: String? = null) : TaskScreen
    data class Submitting(val task: Task, val challenge: Challenge, val form: ChoiceForm, val action: ChoiceAction) : TaskScreen
    /** [task] is the fresh read after the write, or the pre-write task when [readFailed].
     * [byOther]: a check found someone else's result; this user's submission was not recorded. */
    data class Done(
        val task: Task, val challenge: Challenge, val action: ChoiceAction, val me: Long,
        val changesetId: Long?, val readFailed: Boolean, val byOther: Boolean = false, val checking: Boolean = false,
    ) : TaskScreen
    /** A skip leaves the status unchanged. [uncertain]: the outcome is unknown and it was not resent. */
    data class Skipped(val task: Task, val challenge: Challenge, val uncertain: Boolean) : TaskScreen
    data class TakenByOther(val task: Task, val challenge: Challenge) : TaskScreen
    data class StaleOwnLock(val task: Task, val challenge: Challenge, val form: ChoiceForm, val action: ChoiceAction, val lockedTaskId: TaskId) : TaskScreen
    /** The submission may still land (unknown outcome, pending submission or status). Never resent
     * automatically; the user re-reads with Check again. [canResend]: the user may send the identical
     * submission again (confirmed), which the server resumes without a second upload. */
    data class CheckAgain(
        val task: Task, val challenge: Challenge, val form: ChoiceForm, val action: ChoiceAction,
        val message: String, val changesetId: Long?, val checking: Boolean,
        val pending: Pending = Pending.UNKNOWN, val canResend: Boolean = false,
    ) : TaskScreen

    enum class Pending { UNKNOWN, SUBMISSION_PENDING, STATUS_PENDING }
    /** OSM re-consent or a missing scope. Nothing was changed. */
    data class SignInRequired(val task: Task, val challenge: Challenge, val reason: String) : TaskScreen
    /** A delete was refused (the node is in a way or relation); the same outcome may be sent without deletion. */
    data class ElementInUse(val task: Task, val challenge: Challenge, val form: ChoiceForm, val outcome: ChoiceOutcome) : TaskScreen
}

/**
 * Choice task screen logic with late locking. Opening checks eligibility (read-only for the caller);
 * a submission runs the SDK's start → POST choice → release-on-failure off the main thread. Writes
 * finish even if [scope] is cancelled (so the SDK can release its lock), but their results are then
 * discarded. An edit is never resent: uncertain results wait for a manual re-read.
 * State changes happen on [scope]'s thread (the main thread in the app).
 */
class TaskWorkController(
    private val ops: TaskOps,
    private val writer: Writer?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Diagnostics without credentials or server text (debug builds only in the app). */
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<TaskScreen>(TaskScreen.Loading)
    val state: StateFlow<TaskScreen> = mutableState
    private var job: Job? = null

    /** True once a write or a stale finding may have changed the task, so maps and lists should refresh. */
    var changed = false
        private set

    val busy: Boolean
        get() = state.value.let { it is TaskScreen.Submitting || (it is TaskScreen.CheckAgain && it.checking) }

    val canEditOsm: Boolean get() = writer?.canEditOsm == true

    fun load(id: TaskId) {
        if (busy) return
        mutableState.value = TaskScreen.Loading
        job = scope.launch {
            mutableState.value = try {
                val task = ops.getTask(id)
                val challenge = ops.getChallenge(task)
                opened(task, challenge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TaskScreen.LoadFailed(readError(e))
            }
        }
    }

    private suspend fun opened(task: Task, challenge: Challenge): TaskScreen {
        val work = task.work()
        if (task.mobileSupport() != MobileSupport.IN_PLACE || work !is TaskWork.Choice) {
            return TaskScreen.NotAvailable(task, challenge, TaskText.unavailableReason(task))
        }
        if (writer == null) return TaskScreen.Preview(task, challenge, work)
        val eligibility = try {
            ops.checkChoice(task.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not checked: not offered (spec §5a). Class name only: messages may carry server text.
            log("Choice check for task ${task.id.value} failed: ${e.javaClass.simpleName}")
            null
        }
        if (eligibility == null) return TaskScreen.CheckFailed(task, challenge)
        if (!eligibility.eligible) {
            changed = true // The server now hides it from discovery.
            return TaskScreen.NoLongerNeeded(task, challenge)
        }
        return TaskScreen.Answering(task, challenge, form(work, ops.choiceOutcomes(task), eligibility.deleteAllowed))
    }

    /** [optionId] null means "Can't tell": the question is left out of the submission. */
    fun select(questionId: String, optionId: String?) {
        val current = state.value as? TaskScreen.Answering ?: return
        val question = current.form.work.questions.firstOrNull { it.id == questionId } ?: return
        if (optionId != null && question.options.none { it.id == optionId }) return
        val answers = if (optionId == null) current.form.answers - questionId else current.form.answers + (questionId to optionId)
        mutableState.value = current.copy(form = current.form.copy(answers = answers))
    }

    /** Whether [action] may be offered on [form]'s task for this writer. */
    fun offers(task: Task, form: ChoiceForm, action: ChoiceAction): Boolean {
        val w = writer ?: return false
        if (action.editsOsm && !w.canEditOsm) return false
        return when (action) {
            is ChoiceAction.Answers -> action.byQuestion.isNotEmpty() && action.byQuestion.all { (q, o) ->
                form.work.questions.any { it.id == q && it.options.any { option -> option.id == o } }
            }
            is ChoiceAction.Outcome -> action.outcome in form.outcomes
            ChoiceAction.Skip -> task.canSkip()
        }
    }

    /** User-confirmed submission, from a screen that offers a fresh attempt. */
    fun perform(action: ChoiceAction) {
        val (task, challenge, form) = when (val current = state.value) {
            is TaskScreen.Answering -> Triple(current.task, current.challenge, current.form)
            is TaskScreen.StaleOwnLock -> Triple(current.task, current.challenge, current.form)
            else -> return
        }
        if (!offers(task, form, action)) return
        submit(task, challenge, form, action)
    }

    /** After `element_in_use`: the same outcome without deletion (Not an issue), once the user confirms. */
    fun sendWithoutDeletion() {
        val current = state.value as? TaskScreen.ElementInUse ?: return
        val plain = withoutDeletion(current.outcome) ?: return
        if (writer == null) return
        submit(current.task, current.challenge, current.form, ChoiceAction.Outcome(plain))
    }

    private fun submit(task: Task, challenge: Challenge, form: ChoiceForm, action: ChoiceAction) {
        val me = writer?.me ?: return
        changed = true // Before publishing: observers read it while rendering Submitting.
        mutableState.value = TaskScreen.Submitting(task, challenge, form, action)
        job = scope.launch {
            // Only the write itself outlives cancellation; its result is discarded if cancelled.
            val outcome = withContext(NonCancellable + io) { send(task, action) }
            ensureActive()
            mutableState.value = outcome.fold(
                onSuccess = { result -> succeeded(task, challenge, action, me, result) },
                onFailure = { error -> failed(task, challenge, form, action, error as Exception) },
            )
        }
    }

    /** User-confirmed resend of the identical submission from Check again. The server's idempotency
     * resumes it (for example finishes a pending status) without a second OSM upload. */
    fun resendSame() {
        val current = state.value as? TaskScreen.CheckAgain ?: return
        if (!current.canResend || current.checking || writer == null) return
        submit(current.task, current.challenge, current.form, current.action)
    }

    /** Manual re-check after an uncertain result or a failed post-write read. Never resends. */
    fun recheck() {
        val me = writer?.me ?: return
        when (val current = state.value) {
            is TaskScreen.CheckAgain -> if (!current.checking) job = scope.launch { verify(current, me) }
            is TaskScreen.Done -> if (current.readFailed && !current.checking) {
                mutableState.value = current.copy(checking = true)
                job = scope.launch {
                    mutableState.value = reread(current.task, current.challenge, current.action, me, current.changesetId)
                }
            }
            else -> Unit
        }
    }

    /** Cancels reads and discards results. A write already sent still finishes (see class doc). */
    fun cancel() {
        job?.cancel()
    }

    /** Runs [block] once the current operation, including a write that outlives [cancel], is done. */
    fun whenIdle(block: () -> Unit) {
        val current = job
        if (current == null || current.isCompleted) block() else current.invokeOnCompletion { block() }
    }

    private suspend fun send(task: Task, action: ChoiceAction): Result<ChoiceResult?> = try {
        Result.success(when (action) {
            is ChoiceAction.Answers -> ops.submitChoice(task, ChoiceSubmission.Answers(action.byQuestion))
            is ChoiceAction.Outcome -> ops.submitChoice(task, ChoiceSubmission.Outcome(action.outcome))
            ChoiceAction.Skip -> {
                ops.skipTask(task.id)
                null
            }
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun succeeded(task: Task, challenge: Challenge, action: ChoiceAction, me: Long, result: ChoiceResult?): TaskScreen =
        if (action == ChoiceAction.Skip) TaskScreen.Skipped(task, challenge, uncertain = false)
        else reread(task, challenge, action, me, result?.changesetId)

    private suspend fun failed(task: Task, challenge: Challenge, form: ChoiceForm, action: ChoiceAction, error: Exception): TaskScreen {
        fun answering(notice: String) = TaskScreen.Answering(task, challenge, form, notice)
        fun checkAgain(message: String, pending: TaskScreen.Pending, changesetId: Long? = null) =
            TaskScreen.CheckAgain(task, challenge, form, action, message, changesetId, checking = false, pending = pending,
                canResend = pending == TaskScreen.Pending.STATUS_PENDING)
        if (error is IllegalArgumentException) {
            // The SDK checks the submission against the payload before sending anything.
            return answering("The app could not complete this submission. Nothing was recorded.")
        }
        if (error !is MapRouletteException) {
            // Credential failures (for example a refresh that could not complete) happen before sending.
            return answering("Could not send the request. Nothing was recorded.")
        }
        return when (val problem = error.problem) {
            is ChoiceProblem.TaskIneligible -> TaskScreen.NoLongerNeeded(task, challenge)
            ChoiceProblem.ElementInUse -> if (action is ChoiceAction.Outcome && withoutDeletion(action.outcome) != null) {
                TaskScreen.ElementInUse(task, challenge, form, action.outcome)
            } else answering("OpenStreetMap refused the change because the element is in use. Nothing was changed.")
            ChoiceProblem.OsmReauthRequired -> TaskScreen.SignInRequired(task, challenge,
                "OpenStreetMap no longer accepts MapRoulette's permission to edit for you. Nothing was changed. Sign in again to enable editing.")
            ChoiceProblem.OsmScopeRequired -> TaskScreen.SignInRequired(task, challenge,
                "This sign-in cannot edit OpenStreetMap. Nothing was changed. Sign in again to enable editing.")
            WriteProblem.InsufficientScope -> TaskScreen.SignInRequired(task, challenge,
                "This sign-in can only read tasks. Nothing was recorded. Sign in again to enable task actions.")
            ChoiceProblem.OsmUnavailable -> answering(
                "OpenStreetMap edits are not available right now, so nothing was changed. Your answers are kept; try again later.")
            ChoiceProblem.SubmissionPending -> checkAgain(
                "An earlier submission for this task is still being processed. Nothing was sent.", TaskScreen.Pending.SUBMISSION_PENDING)
            is ChoiceProblem.StatusPending -> checkAgain(
                "The edit reached OpenStreetMap, but MapRoulette has not recorded the result yet. It was not sent again. " +
                    "Sending the same submission again finishes it without a second upload.",
                TaskScreen.Pending.STATUS_PENDING, problem.changesetId)
            WriteProblem.OutcomeUnknown -> if (action == ChoiceAction.Skip) TaskScreen.Skipped(task, challenge, uncertain = true)
                else checkAgain("The connection failed before MapRoulette confirmed the result. It was not sent again.", TaskScreen.Pending.UNKNOWN)
            is ChoiceProblem.InvalidSubmission -> answering("MapRoulette did not accept this submission. Nothing was recorded.")
            ChoiceProblem.UnsupportedTask -> TaskScreen.NotAvailable(task, challenge,
                "MapRoulette cannot process this task's questions. Complete it on the MapRoulette website.")
            is WriteProblem.LockedByOtherUser -> TaskScreen.TakenByOther(task, challenge)
            is WriteProblem.AlreadyHoldingTask -> TaskScreen.StaleOwnLock(task, challenge, form, action, problem.lockedTaskId)
            WriteProblem.LockLost -> answering("Your lock on this task ended before the result was recorded. Nothing was recorded; you can try again.")
            WriteProblem.InvalidTransition -> notAccepted(task, challenge)
            else -> when (error.kind) {
                ErrorKind.AUTHENTICATION -> answering("MapRoulette rejected the sign-in, so nothing was recorded. Choose again; if it keeps failing, sign in again.")
                ErrorKind.NOT_FOUND -> TaskScreen.LoadFailed("This task no longer exists.")
                else -> answering(writeError(error))
            }
        }
    }

    private suspend fun notAccepted(task: Task, challenge: Challenge): TaskScreen {
        val fresh = try {
            ops.getTask(task.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            task
        }
        return TaskScreen.NotAvailable(fresh, challenge,
            "MapRoulette did not accept this result. The task may already be completed or its challenge paused.")
    }

    private suspend fun reread(task: Task, challenge: Challenge, action: ChoiceAction, me: Long, changesetId: Long?): TaskScreen = try {
        val fresh = ops.getTask(task.id)
        TaskScreen.Done(fresh, challenge, action, me, changesetId ?: fresh.changesetId, readFailed = false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TaskScreen.Done(task, challenge, action, me, changesetId, readFailed = true)
    }

    /** Applies docs/design/task-completion.md §5 to a fresh read. Never resends and never releases: an edit may still be uploading. */
    private suspend fun verify(pending: TaskScreen.CheckAgain, me: Long) {
        mutableState.value = pending.copy(checking = true)
        val fresh = try {
            ops.getTask(pending.task.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = pending.copy(checking = false, message = "The task could not be re-read. Check your connection and try again.")
            return
        }
        val target = when (val action = pending.action) {
            is ChoiceAction.Answers -> TaskResolution.FIXED
            is ChoiceAction.Outcome -> action.outcome.resolution
            ChoiceAction.Skip -> { // Not reached: an uncertain skip goes straight to Skipped.
                mutableState.value = TaskScreen.Skipped(fresh, pending.challenge, uncertain = true)
                return
            }
        }
        val check = fresh.verifyResolution(target, me)
        val inOsm = pending.changesetId // Known only after status_pending: the edit is in OSM.
        mutableState.value = when {
            check == ResolutionCheck.APPLIED -> TaskScreen.Done(fresh, pending.challenge, pending.action, me,
                fresh.changesetId ?: inOsm, readFailed = false)
            // Our edit is in OSM but MapRoulette has no status for it: never call it "not recorded".
            inOsm != null -> pending.copy(task = fresh, checking = false,
                canResend = check == ResolutionCheck.NOT_APPLIED_LOCK_HELD || check == ResolutionCheck.NOT_APPLIED_UNLOCKED,
                message = "Your edit is in OpenStreetMap changeset $inOsm, but MapRoulette has not recorded the result " +
                    "(task status: ${TaskText.status(fresh.status?.code).lowercase()}).")
            check == ResolutionCheck.RESOLVED_BY_OTHER -> TaskScreen.Done(fresh, pending.challenge, pending.action, me,
                fresh.changesetId, readFailed = false, byOther = fresh.completedBy != null && fresh.completedBy != me)
            check == ResolutionCheck.LOCKED_BY_OTHER -> TaskScreen.TakenByOther(fresh, pending.challenge)
            // Another submission is unfinished; resubmitting would only be refused again.
            pending.pending == TaskScreen.Pending.SUBMISSION_PENDING -> pending.copy(task = fresh, checking = false,
                message = "Another submission for this task is still unfinished, so it cannot be answered now. Pick another task.")
            check == ResolutionCheck.NOT_APPLIED_LOCK_HELD -> pending.copy(task = fresh, checking = false, canResend = true,
                message = "Not recorded yet, and the task is still locked to you. Check again in a minute, or send the same submission again.")
            // An edit may have reached OSM: only the identical submission can be resumed (changed answers would be refused).
            pending.action.editsOsm -> pending.copy(task = fresh, checking = false, canResend = true,
                message = "Not recorded. You can send the same submission again; MapRoulette does not upload an edit twice.")
            else -> TaskScreen.Answering(fresh, pending.challenge, pending.form,
                "Your earlier submission was not recorded. You can submit again.")
        }
    }

    private fun readError(error: Exception): String = when ((error as? MapRouletteException)?.kind) {
        ErrorKind.NOT_FOUND -> "Task not found."
        ErrorKind.NETWORK -> "Could not connect to MapRoulette. Check your connection and retry."
        ErrorKind.AUTHENTICATION -> "Your sign-in is no longer valid. Sign in again."
        null -> "Could not load the task. Please retry."
        else -> "MapRoulette could not load the task. Please retry."
    }

    private fun writeError(error: MapRouletteException): String = when (error.kind) {
        ErrorKind.NETWORK -> "Could not connect to MapRoulette. Nothing was recorded. Check your connection and try again."
        ErrorKind.RATE_LIMIT -> "MapRoulette is limiting requests. Nothing was recorded. Wait a moment and try again."
        ErrorKind.HTTP -> "MapRoulette refused the request (HTTP ${error.status}). Nothing was recorded. The challenge may be paused."
        ErrorKind.PERMISSION -> "MapRoulette refused the request. Nothing was recorded."
        else -> "MapRoulette returned an unexpected response. Nothing was recorded."
    }

    companion object {
        /** The form for an eligible task: [outcomes] are the client's (declared plus Too hard, decoded
         * with the deletion setting). When the check does not allow deletion (the node is in a way or
         * relation), a delete outcome is offered without deletion instead (Not an issue) and listed in
         * [ChoiceForm.notDeletable]. */
        fun form(work: TaskWork.Choice, outcomes: List<ChoiceOutcome>, deleteAllowed: Boolean): ChoiceForm {
            val blocked = if (deleteAllowed) emptySet() else outcomes.filter { it.deletesElement }.map { it.id }.toSet()
            return ChoiceForm(work, outcomes.map { if (deleteAllowed) it else it.withoutDeletion() }, notDeletable = blocked)
        }

        /** The deleting [outcome] without deletion (Not an issue), or null if it is not a delete. */
        fun withoutDeletion(outcome: ChoiceOutcome): ChoiceOutcome? =
            if (outcome.deletesElement) outcome.withoutDeletion() else null
    }
}
