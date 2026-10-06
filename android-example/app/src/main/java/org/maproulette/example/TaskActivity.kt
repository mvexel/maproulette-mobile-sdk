package org.maproulette.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maproulette.example.auth.AppSession
import org.maproulette.example.auth.SignInLauncher
import org.maproulette.example.task.ClientTaskOps
import org.maproulette.example.task.TaskAction
import org.maproulette.example.task.TaskScreen
import org.maproulette.example.task.TaskText
import org.maproulette.example.task.TaskWorkController
import org.maproulette.example.task.Writer
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution

/** Task detail with late-locking completion (docs/task-completion.md §11). Viewing never locks. */
class TaskActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: AppSession
    private lateinit var signIn: SignInLauncher
    private lateinit var sessionClient: AppSession.SessionClient
    private lateinit var controller: TaskWorkController
    private var taskId = TaskId(1)

    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var banner: TextView
    private lateinit var notice: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout
    private lateinit var instructions: TextView
    private var backBlocker: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = TaskId(intent.getLongExtra(EXTRA_TASK_ID, 0L).takeIf { it > 0 } ?: run { finish(); return })
        session = AppSession.get(this)
        signIn = SignInLauncher(this, session, scope).apply { restore(savedInstanceState) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        title = label("Task ${taskId.value}", 24f).also(content::addView)
        meta = label("", 14f).also(content::addView)
        banner = label("", 15f).apply {
            setBackgroundColor(Color.parseColor("#FFF4D6"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }.also(content::addView)
        notice = label("", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }.also(content::addView)
        progress = ProgressBar(this).apply { visibility = View.GONE }.also(content::addView)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(content::addView)
        content.addView(label("Instructions", 19f))
        instructions = label("", 16f).also(content::addView)

        sessionClient = session.newClient()
        replaceController()
        controller.load(taskId)
        var observedGeneration = session.view.value.generation
        scope.launch {
            session.view.collect { view ->
                if (view.generation != observedGeneration) {
                    // Account switch or sign-out: discard the old account's state and any late result.
                    observedGeneration = view.generation
                    // §7: the old account's in-flight write is cancelled; its result cannot be checked
                    // with the new account, so say so instead of implying nothing happened.
                    val interrupted = controller.busy || controller.state.value is TaskScreen.OutcomeUnknown
                    if (interrupted || controller.changed) setResult(RESULT_OK, Intent().putExtra(EXTRA_CHANGED, true))
                    controller.cancel()
                    sessionClient.close()
                    sessionClient = session.newClient()
                    replaceController()
                    val reason = if (interrupted) {
                        "Your sign-in changed while an action was being recorded, so its result is unknown. Check the task status below. "
                    } else ""
                    controller.load(taskId, "$reason${view.message}")
                } else {
                    render(controller.state.value)
                }
            }
        }
    }

    /** A controller is bound to one account's client; results of a replaced one are never rendered. */
    private fun replaceController() {
        val view = session.view.value
        val me = view.userId.takeIf { session.writesConfigured && view.signedIn && view.canWriteTasks }
        val next = TaskWorkController(ClientTaskOps(sessionClient.client), me?.let(::Writer), scope)
        controller = next
        scope.launch {
            next.state.collect { state -> if (controller === next) render(state) }
        }
    }

    private fun render(state: TaskScreen) {
        renderState(state)
        notice.visibility = if (notice.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderState(state: TaskScreen) {
        if (controller.changed) setResult(RESULT_OK, Intent().putExtra(EXTRA_CHANGED, true))
        updateBackBlocking(controller.busy)
        actions.removeAllViews()
        banner.visibility = View.GONE
        progress.visibility = View.GONE
        when (state) {
            TaskScreen.Loading -> {
                progress.visibility = View.VISIBLE
                notice.text = "Loading task…"
            }
            is TaskScreen.LoadFailed -> {
                notice.text = state.message
                actions.addView(button("Retry") { controller.load(taskId) })
                actions.addView(button("Back") { finish() })
            }
            is TaskScreen.Viewing -> {
                showTask(state.task, state.challenge)
                notice.text = state.notice ?: ""
                taskActions(state.task, committing = null)
            }
            is TaskScreen.Committing -> {
                showTask(state.task, state.challenge)
                progress.visibility = View.VISIBLE
                notice.text = "Recording “${actionLabel(state.action)}”…"
                taskActions(state.task, committing = state.action)
            }
            is TaskScreen.TakenByOther -> {
                showTask(state.task, state.challenge)
                notice.text = "Someone else is working on this task. Pick another one."
                actions.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.StaleOwnLock -> {
                showTask(state.task, state.challenge)
                notice.text = "A previous attempt on task ${state.lockedTaskId.value} is still open, so this task could not be started. Nothing was recorded."
                actions.addView(button("Retry “${actionLabel(state.action)}”") { confirm(state.task, state.action) })
                actions.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.Resolved -> {
                showTask(state.task, state.challenge)
                notice.text = buildString {
                    if (state.byOther) {
                        append("Someone else already completed this task. Your “${TaskText.button(state.action.resolution)}” was not recorded.")
                    } else {
                        append("Recorded as “${TaskText.button(state.action.resolution)}”.")
                    }
                    if (state.readFailed) {
                        append("\nThe task could not be re-read to confirm its status.")
                    } else {
                        append("\nCurrent status: ${TaskText.status(state.task.status?.code)}.")
                        append("\n${TaskText.completedBy(state.task.completedBy, state.me)}")
                        TaskText.reviewStatus(state.task.reviewStatus)?.let { append("\n$it.") }
                    }
                }
                if (state.readFailed) actions.addView(button("Check again") { controller.recheck() })
                actions.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.Skipped -> {
                showTask(state.task, state.challenge)
                notice.text = if (state.uncertain) {
                    "The connection failed before MapRoulette confirmed the skip. It was not sent again; the task may or may not count as skipped."
                } else "Skipped. The task stays open for others."
                actions.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.OutcomeUnknown -> {
                showTask(state.task, state.challenge)
                if (state.checking) {
                    progress.visibility = View.VISIBLE
                    notice.text = "Checking result…"
                } else {
                    notice.text = "The connection failed before MapRoulette confirmed “${TaskText.button(state.action.resolution)}”, and the result could not be checked. It was not sent again."
                    actions.addView(button("Check again") { controller.recheck() })
                    actions.addView(button("Back to tasks") { finish() })
                }
            }
            is TaskScreen.SessionExpired -> {
                showTask(state.task, state.challenge)
                notice.text = "MapRoulette rejected the sign-in, so nothing was recorded. Your session was renewed; choose the action again."
                taskActions(state.task, committing = null)
            }
            is TaskScreen.InsufficientScope -> {
                showTask(state.task, state.challenge)
                notice.text = "This sign-in can only read tasks. Nothing was recorded."
                actions.addView(button("Sign in again to enable task actions") { signIn.signIn() })
                actions.addView(label(signIn.accountHint(), 13f))
            }
            is TaskScreen.Failed -> {
                showTask(state.task, state.challenge)
                notice.text = state.message
                taskActions(state.task, committing = null)
            }
        }
    }

    private fun showTask(task: Task, challenge: Challenge) {
        title.text = task.name
        meta.text = "Task ${task.id.value} · ${TaskText.status(task.status?.code)}\nChallenge ${challenge.id.value}: ${challenge.name}"
        val me = session.view.value.userId
        if (task.lockedBy != null) {
            banner.text = if (task.lockedBy == me) {
                "This task is still locked to you from an earlier attempt. Completing or skipping it releases the lock."
            } else "Someone is working on this task right now. You can still continue, but they may finish first."
            banner.visibility = View.VISIBLE
        }
        instructions.text = TaskText.instruction(task, challenge)
    }

    /** Write actions, only on a build and session that can write. The production build shows none. */
    private fun taskActions(task: Task, committing: TaskAction?) {
        if (!session.writesConfigured) return
        val view = session.view.value
        if (!view.signedIn || !view.canWriteTasks) {
            actions.addView(label(if (view.signedIn) "Your sign-in can only read tasks." else "Sign in to work on this task.", 15f))
            actions.addView(button(if (view.signedIn) "Sign in again to enable task actions" else "Sign in") { signIn.signIn() })
            actions.addView(label(signIn.accountHint(), 13f))
            return
        }
        TaskText.limitation(task)?.let { actions.addView(label(it, 14f)) }
        val offered = TaskResolution.entries.map(TaskAction::Resolve) + TaskAction.Skip
        offered.filter { controller.offers(task, it) }.forEach { action ->
            actions.addView(Button(this).apply {
                isAllCaps = false
                text = if (action == committing) "Recording…" else "${actionLabel(action)}\n${actionExplanation(action)}"
                isEnabled = committing == null
                setOnClickListener { confirm(task, action) }
            })
        }
    }

    private fun confirm(task: Task, action: TaskAction) {
        val user = session.view.value.userId
        val owner = controller // A dialog left open across an account switch must not write as the new account.
        val effect = if (action == TaskAction.Skip) "This records a skip for task ${task.id.value}"
            else "This records the result for task ${task.id.value}"
        AlertDialog.Builder(this)
            .setTitle(if (action == TaskAction.Skip) "Skip this task?" else "Mark as “${actionLabel(action)}”?")
            .setMessage("${actionExplanation(action)}\n\n$effect in MapRoulette as user $user. It does not edit OpenStreetMap.")
            .setPositiveButton("Confirm") { _, _ -> if (controller === owner) owner.perform(action) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun actionLabel(action: TaskAction) = when (action) {
        is TaskAction.Resolve -> TaskText.button(action.resolution)
        TaskAction.Skip -> TaskText.SKIP
    }

    private fun actionExplanation(action: TaskAction) = when (action) {
        is TaskAction.Resolve -> TaskText.explanation(action.resolution)
        TaskAction.Skip -> TaskText.SKIP_EXPLANATION
    }

    // Back is blocked while a write is in flight (normally under a second).
    private fun updateBackBlocking(busy: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) BackBlocker.update(this, busy)
    }

    // API 33+ blocks back with OnBackInvokedCallback (BackBlocker); this covers older versions.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Needed below API 33; newer versions use OnBackInvokedCallback")
    override fun onBackPressed() {
        if (::controller.isInitialized && controller.busy) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    @RequiresApi(33)
    private object BackBlocker {
        fun update(activity: TaskActivity, busy: Boolean) {
            val dispatcher = activity.onBackInvokedDispatcher
            val current = activity.backBlocker as OnBackInvokedCallback?
            if (busy && current == null) {
                val callback = OnBackInvokedCallback { }
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                activity.backBlocker = callback
            } else if (!busy && current != null) {
                dispatcher.unregisterOnBackInvokedCallback(current)
                activity.backBlocker = null
            }
        }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        isAllCaps = false
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextIsSelectable(true)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        signIn.save(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("AppAuth's activity-result flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        signIn.onActivityResult(requestCode, data)
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            // A write already sent finishes (so a failed commit can release its lock); close afterwards.
            val client = sessionClient
            controller.cancel()
            controller.whenIdle { client.close() }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_CHANGED = "changed"
        const val REQUEST = 200

        fun intent(context: Context, id: TaskId): Intent =
            Intent(context, TaskActivity::class.java).putExtra(EXTRA_TASK_ID, id.value)
    }
}
