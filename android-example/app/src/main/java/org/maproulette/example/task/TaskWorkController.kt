package org.maproulette.example.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.ResolutionCheck
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.WriteProblem
import org.maproulette.sdk.allowedResolutions
import org.maproulette.sdk.canSkip
import org.maproulette.sdk.verifyResolution

/** The SDK operations the task screen uses; a fake replaces it in unit tests. */
interface TaskOps {
    suspend fun getTask(id: TaskId): Task
    suspend fun getChallenge(task: Task): Challenge
    suspend fun commitResolution(id: TaskId, resolution: TaskResolution)
    suspend fun skipTask(id: TaskId)
    suspend fun releaseTask(id: TaskId)
}

/** Bound to one account's client: a controller never sends through a later account's client. */
class ClientTaskOps(private val client: MapRouletteClient) : TaskOps {
    override suspend fun getTask(id: TaskId) = client.getTask(id)
    override suspend fun getChallenge(task: Task) = client.getChallenge(task.challengeId)
    override suspend fun commitResolution(id: TaskId, resolution: TaskResolution) = client.commitResolution(id, resolution)
    override suspend fun skipTask(id: TaskId) = client.skipTask(id)
    override suspend fun releaseTask(id: TaskId) = client.releaseTask(id)
}

sealed interface TaskAction {
    data class Resolve(val resolution: TaskResolution) : TaskAction
    data object Skip : TaskAction
}

/** Who may write: [me] is the signed-in MapRoulette user id, or null when writes are not possible. */
data class Writer(val me: Long)

/** Screen states from docs/task-completion.md §11. Each carries the latest task read. */
sealed interface TaskScreen {
    data object Loading : TaskScreen
    data class LoadFailed(val message: String) : TaskScreen
    /** [notice] explains why the user is back here (for example a not-applied earlier attempt). */
    data class Viewing(val task: Task, val challenge: Challenge, val notice: String? = null) : TaskScreen
    data class Committing(val task: Task, val challenge: Challenge, val action: TaskAction) : TaskScreen
    data class TakenByOther(val task: Task, val challenge: Challenge) : TaskScreen
    data class StaleOwnLock(val task: Task, val challenge: Challenge, val action: TaskAction, val lockedTaskId: TaskId) : TaskScreen
    /** [task] is the fresh read after the write, or the pre-write task when [readFailed].
     * [byOther]: verification found someone else's result; this user's write was not recorded. */
    data class Resolved(
        val task: Task, val challenge: Challenge, val action: TaskAction.Resolve, val me: Long,
        val readFailed: Boolean, val byOther: Boolean = false,
    ) : TaskScreen
    /** A skip leaves the status unchanged. [uncertain]: the outcome is unknown and it was not resent. */
    data class Skipped(val task: Task, val challenge: Challenge, val uncertain: Boolean) : TaskScreen
    /** The write may or may not have been applied. Never resent automatically. */
    data class OutcomeUnknown(val task: Task, val challenge: Challenge, val action: TaskAction.Resolve, val checking: Boolean) : TaskScreen
    /** 401: the session tried one token refresh. Nothing was recorded and nothing is resent. */
    data class SessionExpired(val task: Task, val challenge: Challenge, val action: TaskAction) : TaskScreen
    data class InsufficientScope(val task: Task, val challenge: Challenge) : TaskScreen
    data class Failed(val task: Task, val challenge: Challenge, val message: String) : TaskScreen
}

/**
 * Late-locking task screen logic (docs/task-completion.md §3, §5, §6). Viewing never locks; a commit
 * runs start → status → release-on-failure through the SDK helper. Writes finish even if [scope] is
 * cancelled (so the helper can release its lock), but their results are then discarded.
 * All calls are made from [scope]'s thread (the main thread in the app).
 */
class TaskWorkController(
    private val ops: TaskOps,
    private val writer: Writer?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<TaskScreen>(TaskScreen.Loading)
    val state: StateFlow<TaskScreen> = mutableState
    private var job: Job? = null

    /** True once a write may have changed the task, so maps and lists should refresh. */
    var changed = false
        private set

    val busy: Boolean
        get() = state.value.let { it is TaskScreen.Committing || (it is TaskScreen.OutcomeUnknown && it.checking) }

    fun load(id: TaskId, notice: String? = null) {
        if (busy) return
        mutableState.value = TaskScreen.Loading
        job = scope.launch {
            mutableState.value = try {
                val task = ops.getTask(id)
                TaskScreen.Viewing(task, ops.getChallenge(task), notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TaskScreen.LoadFailed(readError(e))
            }
        }
    }

    /** Whether [action] may be offered on [task] for this writer. */
    fun offers(task: Task, action: TaskAction): Boolean = writer != null && when (action) {
        is TaskAction.Resolve -> action.resolution in task.allowedResolutions()
        TaskAction.Skip -> task.canSkip()
    }

    /** User-confirmed write. Allowed only from a screen that offers a fresh attempt. */
    fun perform(action: TaskAction) {
        val me = writer?.me ?: return
        val (task, challenge) = when (val current = state.value) {
            is TaskScreen.Viewing -> current.task to current.challenge
            is TaskScreen.StaleOwnLock -> current.task to current.challenge
            is TaskScreen.SessionExpired -> current.task to current.challenge
            is TaskScreen.Failed -> current.task to current.challenge
            else -> return
        }
        if (!offers(task, action)) return
        changed = true // Before publishing: observers read it while rendering Committing.
        mutableState.value = TaskScreen.Committing(task, challenge, action)
        job = scope.launch {
            // Only the write itself outlives cancellation; its result is discarded if cancelled.
            val failure = withContext(NonCancellable) { send(task.id, action) }
            ensureActive()
            val next = if (failure == null) succeeded(task, challenge, action, me)
                else failed(task, challenge, action, failure)
            mutableState.value = next
            if (next is TaskScreen.OutcomeUnknown) verify(next, me)
        }
    }

    /** Manual re-check after an unknown outcome or a failed post-write read. Never resends. */
    fun recheck() {
        val me = writer?.me ?: return
        when (val current = state.value) {
            is TaskScreen.OutcomeUnknown -> if (!current.checking) job = scope.launch { verify(current, me) }
            is TaskScreen.Resolved -> if (current.readFailed) job = scope.launch {
                mutableState.value = reread(current.task, current.challenge, current.action, me)
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

    /** Returns the failure, or null on success. */
    private suspend fun send(id: TaskId, action: TaskAction): Exception? = try {
        when (action) {
            is TaskAction.Resolve -> ops.commitResolution(id, action.resolution)
            TaskAction.Skip -> ops.skipTask(id)
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    private suspend fun succeeded(task: Task, challenge: Challenge, action: TaskAction, me: Long): TaskScreen = when (action) {
        is TaskAction.Resolve -> reread(task, challenge, action, me)
        TaskAction.Skip -> TaskScreen.Skipped(task, challenge, uncertain = false)
    }

    private suspend fun failed(task: Task, challenge: Challenge, action: TaskAction, error: Exception): TaskScreen {
        if (error !is MapRouletteException) {
            // Credential failures (for example a refresh that could not complete) happen before sending.
            return TaskScreen.Failed(task, challenge, "Could not send the request. Nothing was recorded.")
        }
        val e = error
        val problem = e.problem
        return when {
            problem is WriteProblem.LockedByOtherUser -> TaskScreen.TakenByOther(task, challenge)
            problem is WriteProblem.AlreadyHoldingTask -> TaskScreen.StaleOwnLock(task, challenge, action, problem.lockedTaskId)
            problem == WriteProblem.InsufficientScope -> TaskScreen.InsufficientScope(task, challenge)
            problem == WriteProblem.OutcomeUnknown -> when (action) {
                is TaskAction.Resolve -> TaskScreen.OutcomeUnknown(task, challenge, action, checking = true)
                TaskAction.Skip -> TaskScreen.Skipped(task, challenge, uncertain = true)
            }
            problem == WriteProblem.InvalidTransition -> freshViewing(task, challenge,
                "MapRoulette did not accept this result. The task may already be completed or its challenge paused.")
            e.kind == ErrorKind.AUTHENTICATION -> TaskScreen.SessionExpired(task, challenge, action)
            e.kind == ErrorKind.NOT_FOUND -> TaskScreen.LoadFailed("This task no longer exists.")
            else -> TaskScreen.Failed(task, challenge, writeError(e))
        }
    }

    private suspend fun reread(task: Task, challenge: Challenge, action: TaskAction.Resolve, me: Long): TaskScreen = try {
        TaskScreen.Resolved(ops.getTask(task.id), challenge, action, me, readFailed = false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TaskScreen.Resolved(task, challenge, action, me, readFailed = true)
    }

    private suspend fun freshViewing(task: Task, challenge: Challenge, notice: String): TaskScreen = try {
        TaskScreen.Viewing(ops.getTask(task.id), challenge, notice)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TaskScreen.Viewing(task, challenge, notice)
    }

    /** Applies docs/task-completion.md §5 to a fresh read. */
    private suspend fun verify(unknown: TaskScreen.OutcomeUnknown, me: Long) {
        mutableState.value = unknown.copy(checking = true)
        val fresh = try {
            ops.getTask(unknown.task.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = unknown.copy(checking = false)
            return
        }
        val notApplied = "Your earlier attempt was not recorded. You can try again."
        mutableState.value = when (fresh.verifyResolution(unknown.action.resolution, me)) {
            ResolutionCheck.APPLIED ->
                TaskScreen.Resolved(fresh, unknown.challenge, unknown.action, me, readFailed = false)
            ResolutionCheck.RESOLVED_BY_OTHER ->
                TaskScreen.Resolved(fresh, unknown.challenge, unknown.action, me, readFailed = false, byOther = true)
            ResolutionCheck.LOCKED_BY_OTHER -> TaskScreen.TakenByOther(fresh, unknown.challenge)
            ResolutionCheck.NOT_APPLIED_LOCK_HELD -> {
                // Our lock from the interrupted commit: release it (best effort, §6).
                val released = withContext(NonCancellable) {
                    try {
                        ops.releaseTask(fresh.id)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
                TaskScreen.Viewing(fresh, unknown.challenge, if (released) notApplied else
                    "$notApplied The task is still locked to you and could not be released; others may be blocked from it for up to two hours.")
            }
            ResolutionCheck.NOT_APPLIED_UNLOCKED -> TaskScreen.Viewing(fresh, unknown.challenge, notApplied)
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
        ErrorKind.HTTP -> "MapRoulette refused the request (HTTP ${error.status}). The challenge may be paused."
        ErrorKind.PERMISSION -> "MapRoulette refused the request. Nothing was recorded."
        else -> "MapRoulette returned an unexpected response. Nothing was recorded."
    }
}
