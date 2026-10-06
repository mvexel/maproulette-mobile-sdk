package org.maproulette.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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
import org.maproulette.example.task.ChoiceAction
import org.maproulette.example.task.ChoiceForm
import org.maproulette.example.task.ClientTaskOps
import org.maproulette.example.task.TaskScreen
import org.maproulette.example.task.TaskText
import org.maproulette.example.task.TaskWorkController
import org.maproulette.example.task.Writer
import org.maproulette.example.task.editsOsm
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChoiceOption
import org.maproulette.sdk.ChoiceQuestion
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.work

/** Multiple-choice task screen with late locking (docs/design/mobile-choice-challenges.md §7). Viewing never locks. */
class TaskActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: AppSession
    private lateinit var signIn: SignInLauncher
    private lateinit var sessionClient: AppSession.SessionClient
    private lateinit var controller: TaskWorkController
    private var taskId = TaskId(1)
    /** Tasks from the list or map to offer as "Next task", in order; never includes [taskId]. */
    private var upcoming = ArrayDeque<Long>()

    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var banner: TextView
    private lateinit var notice: TextView
    private lateinit var progress: ProgressBar
    private lateinit var body: LinearLayout
    private lateinit var instructions: TextView
    private var submitButton: Button? = null
    private var rendered: TaskScreen? = null
    private var backBlocker: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = TaskId((savedInstanceState?.getLong(EXTRA_TASK_ID) ?: intent.getLongExtra(EXTRA_TASK_ID, 0L)).takeIf { it > 0 }
            ?: run { finish(); return })
        upcoming = ArrayDeque(savedInstanceState?.getLongArray(KEY_UPCOMING)?.toList()
            ?: TaskText.nextTasks(intent.getLongArrayExtra(EXTRA_TASK_IDS)?.toList().orEmpty(), taskId.value))
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
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(content::addView)
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
                    val interrupted = controller.busy || controller.state.value is TaskScreen.CheckAgain
                    if (interrupted || controller.changed) setResult(RESULT_OK, Intent().putExtra(EXTRA_CHANGED, true))
                    controller.cancel()
                    sessionClient.close()
                    sessionClient = session.newClient()
                    replaceController()
                    switchNotice = (if (interrupted) {
                        "Your sign-in changed while a result was being recorded, so its outcome is unknown. Check the task status below. "
                    } else "") + view.message
                    controller.load(taskId)
                } else {
                    render(controller.state.value)
                }
            }
        }
    }

    /** Shown above the next screen after an account switch. */
    private var switchNotice: String? = null

    /** A controller is bound to one account's clients; results of a replaced one are never rendered. */
    private fun replaceController() {
        val view = session.view.value
        val writer = view.userId.takeIf { session.writesConfigured && view.signedIn && view.canWriteTasks }
            ?.let { Writer(it, view.canEditOsm) }
        val ops = ClientTaskOps(sessionClient.client)
        val next = TaskWorkController(ops, writer, scope) { if (BuildConfig.DEBUG) Log.w("MapRouletteTask", it) }
        controller = next
        rendered = null
        scope.launch {
            next.state.collect { state -> if (controller === next) render(state) }
        }
    }

    private fun render(state: TaskScreen) {
        if (controller.changed) setResult(RESULT_OK, Intent().putExtra(EXTRA_CHANGED, true))
        updateBackBlocking(controller.busy)
        val previous = rendered
        rendered = state
        // Picking an answer only changes the answers: keep the views (and the scroll position).
        if (state is TaskScreen.Answering && previous is TaskScreen.Answering &&
            previous.copy(form = previous.form.copy(answers = state.form.answers)) == state) {
            submitButton?.isEnabled = canSubmit(state.form)
            return
        }
        body.removeAllViews()
        submitButton = null
        banner.visibility = View.GONE
        progress.visibility = View.GONE
        renderState(state)
        switchNotice?.let { if (state !is TaskScreen.Loading) { notice.text = "$it\n${notice.text}".trim(); switchNotice = null } }
        notice.visibility = if (notice.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderState(state: TaskScreen) {
        when (state) {
            TaskScreen.Loading -> {
                progress.visibility = View.VISIBLE
                notice.text = "Loading task…"
            }
            is TaskScreen.LoadFailed -> {
                notice.text = state.message
                body.addView(button("Retry") { controller.load(taskId) })
                body.addView(button("Back") { finish() })
            }
            is TaskScreen.NotAvailable -> {
                showTask(state.task, state.challenge)
                notice.text = "Not available on mobile. ${state.reason}"
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.Preview -> {
                showTask(state.task, state.challenge)
                notice.text = ""
                questionCards(state.work, null, enabled = false)
                signInPrompt()
            }
            is TaskScreen.CheckFailed -> {
                showTask(state.task, state.challenge)
                notice.text = "Couldn't check this right now. The app checks OpenStreetMap before a task can be answered; check your connection and retry."
                body.addView(button("Retry") { controller.load(taskId) })
                upcoming.firstOrNull()?.let { body.addView(button("Next task") { openNext() }) }
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.NoLongerNeeded -> {
                showTask(state.task, state.challenge)
                notice.text = TaskText.NO_LONGER_NEEDED
                upcoming.firstOrNull()?.let { body.addView(button("Next task") { openNext() }) }
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.Answering -> {
                showTask(state.task, state.challenge)
                notice.text = state.notice ?: ""
                choiceActions(state.task, state.form, submitting = null)
            }
            is TaskScreen.Submitting -> {
                showTask(state.task, state.challenge)
                progress.visibility = View.VISIBLE
                notice.text = if (state.action.editsOsm) "Uploading to OpenStreetMap and recording the result…" else "Recording “${actionLabel(state.action)}”…"
                choiceActions(state.task, state.form, submitting = state.action)
            }
            is TaskScreen.Done -> {
                showTask(state.task, state.challenge)
                notice.text = buildString {
                    if (state.byOther) {
                        append("Someone else already completed this task. Your “${actionLabel(state.action)}” was not recorded.")
                    } else {
                        append(doneText(state))
                    }
                    if (state.readFailed) {
                        append("\nThe task could not be re-read to confirm its status.")
                    } else {
                        append("\nCurrent status: ${TaskText.status(state.task.status?.code)}.")
                        append("\n${TaskText.completedBy(state.task.completedBy, state.me)}")
                        TaskText.reviewStatus(state.task.reviewStatus)?.let { append("\n$it.") }
                    }
                    state.changesetId?.let { append("\nOpenStreetMap changeset $it.") }
                }
                if (!state.byOther) state.changesetId?.let { changesetButton(it) }
                if (state.checking) progress.visibility = View.VISIBLE
                if (state.readFailed) body.addView(button("Check again") { controller.recheck() }.apply { isEnabled = !state.checking })
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.Skipped -> {
                showTask(state.task, state.challenge)
                notice.text = if (state.uncertain) {
                    "The connection failed before MapRoulette confirmed the skip. It was not sent again; the task may or may not count as skipped."
                } else "Skipped. The task stays open for others."
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.TakenByOther -> {
                showTask(state.task, state.challenge)
                notice.text = "Someone else is working on this task. Pick another one."
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.StaleOwnLock -> {
                showTask(state.task, state.challenge)
                notice.text = "A previous attempt on task ${state.lockedTaskId.value} is still open, so this task could not be started. Nothing was recorded."
                body.addView(button("Retry “${actionLabel(state.action)}”") { confirm(state.task, state.form, state.action) })
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.CheckAgain -> {
                showTask(state.task, state.challenge)
                if (state.checking) {
                    progress.visibility = View.VISIBLE
                    notice.text = "Checking result…"
                } else {
                    notice.text = state.message + (state.changesetId?.let { "\nOpenStreetMap changeset $it." } ?: "")
                    state.changesetId?.let { changesetButton(it) }
                    body.addView(button("Check again") { controller.recheck() })
                    if (state.canResend) body.addView(button("Send the same submission again") { confirmResend(state) })
                    body.addView(button("Back to tasks") { finish() })
                }
            }
            is TaskScreen.SignInRequired -> {
                showTask(state.task, state.challenge)
                notice.text = state.reason
                body.addView(button("Sign in again to enable editing") { signIn.signIn() })
                body.addView(label(signIn.accountHint(), 13f))
                body.addView(button("Back to tasks") { finish() })
            }
            is TaskScreen.ElementInUse -> {
                showTask(state.task, state.challenge)
                val element = TaskText.element(state.form.work.element)
                notice.text = "$element is part of a way or relation in OpenStreetMap, so it was not deleted. Nothing was changed."
                val plain = TaskWorkController.withoutDeletion(state.outcome)
                if (plain != null) {
                    body.addView(button("Send “${state.outcome.label}” without deleting\n${TaskText.outcomeExplanation(plain, state.form.work.element, notDeletable = true)}") {
                        val user = session.view.value.userId ?: return@button
                        val owner = controller
                        AlertDialog.Builder(this)
                            .setTitle("Send “${state.outcome.label}” without deleting?")
                            .setMessage(TaskText.outcomeSummary(plain, state.form.work.element, state.task.id.value, user, session.osmServer, notDeletable = true))
                            .setPositiveButton("Confirm") { _, _ -> if (controller === owner) owner.sendWithoutDeletion() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    })
                }
                body.addView(button("Back to tasks") { finish() })
            }
        }
    }

    /** Opens the next task from the list or map in this screen; [controller.changed] carries over. */
    private fun openNext() {
        if (controller.busy) return
        val next = upcoming.removeFirstOrNull() ?: return
        taskId = TaskId(next)
        title.text = "Task $next"
        meta.text = ""
        instructions.text = ""
        controller.load(taskId)
    }

    private fun showTask(task: Task, challenge: Challenge) {
        title.text = task.name
        val element = (task.work() as? TaskWork.Choice)?.element?.let { " · ${TaskText.element(it)}" } ?: ""
        meta.text = "Task ${task.id.value} · ${TaskText.status(task.status?.code)}$element\nChallenge ${challenge.id.value}: ${challenge.name}"
        val me = session.view.value.userId
        if (task.lockedBy != null) {
            banner.text = if (task.lockedBy == me) {
                "This task is still locked to you from an earlier attempt."
            } else "Someone is working on this task right now. You can still continue, but they may finish first."
            banner.visibility = View.VISIBLE
        }
        instructions.text = TaskText.instruction(task, challenge)
    }

    private fun signInPrompt() {
        if (!session.writesConfigured) return
        val view = session.view.value
        body.addView(label(if (view.signedIn) "Your sign-in can only read tasks." else "Sign in to answer this task.", 15f))
        body.addView(button(if (view.signedIn) "Sign in again to enable editing" else "Sign in") { signIn.signIn() })
        body.addView(label(signIn.accountHint(), 13f))
    }

    private fun canSubmit(form: ChoiceForm) = controller.canEditOsm && form.answers.isNotEmpty()

    /** Question cards, Submit, then the task-level outcomes and Skip. Writable sessions only. */
    private fun choiceActions(task: Task, form: ChoiceForm, submitting: ChoiceAction?) {
        val idle = submitting == null
        if (!controller.canEditOsm) {
            body.addView(label("This sign-in cannot edit OpenStreetMap, so answers cannot be uploaded. Outcomes that only record a result still work.", 15f))
            body.addView(button("Sign in again to enable editing") { signIn.signIn() }.apply { isEnabled = idle })
            body.addView(label(signIn.accountHint(), 13f))
        }
        questionCards(form.work, form, enabled = idle)
        submitButton = Button(this).apply {
            isAllCaps = false
            text = if (submitting is ChoiceAction.Answers) "Uploading…" else "Submit answers"
            isEnabled = idle && canSubmit(form)
            setOnClickListener {
                val current = controller.state.value as? TaskScreen.Answering ?: return@setOnClickListener
                confirm(current.task, current.form, ChoiceAction.Answers(current.form.answers))
            }
        }.also(body::addView)
        body.addView(label("Or, for the whole task:", 15f))
        val actions = form.outcomes.map(ChoiceAction::Outcome) + ChoiceAction.Skip
        actions.filter { controller.offers(task, form, it) }.forEach { action ->
            body.addView(Button(this).apply {
                isAllCaps = false
                text = if (action == submitting) "Recording…" else "${actionLabel(action)}\n${actionExplanation(form, action)}"
                isEnabled = idle
                setOnClickListener {
                    val current = controller.state.value as? TaskScreen.Answering ?: return@setOnClickListener
                    confirm(current.task, current.form, action)
                }
            })
        }
    }

    /** One card per question; [form] null shows them read-only without a selection. */
    private fun questionCards(work: TaskWork.Choice, form: ChoiceForm?, enabled: Boolean) {
        work.questions.forEach { question ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#C9D3CF"))
                }
            }
            card.addView(label(question.prompt, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            question.description?.takeIf { it.isNotBlank() }?.let { card.addView(label(it, 14f)) }
            if (form == null) {
                question.options.forEach { card.addView(label(optionText(it), 15f)) }
            } else {
                card.addView(optionGroup(question, form, enabled))
            }
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    private fun optionGroup(question: ChoiceQuestion, form: ChoiceForm, enabled: Boolean) = RadioGroup(this).apply {
        val ids = mutableMapOf<Int, String?>()
        (question.options.map { it.id to optionText(it) } + (null to SpannableStringBuilder(TaskText.CANT_TELL)))
            .forEach { (optionId, text) ->
                val radio = RadioButton(context).apply {
                    id = View.generateViewId()
                    this.text = text
                    isEnabled = enabled
                    setPadding(dp(6), dp(6), 0, dp(6))
                }
                ids[radio.id] = optionId
                addView(radio)
                if (form.answers[question.id] == optionId) check(radio.id)
            }
        setOnCheckedChangeListener { _, checked -> if (checked in ids) controller.select(question.id, ids[checked]) }
    }

    /** Label, then the exact tag change in monospace, then the optional description. */
    private fun optionText(option: ChoiceOption): CharSequence = SpannableStringBuilder(option.label).apply {
        append("\n")
        val start = length
        append(TaskText.tagChange(option))
        setSpan(TypefaceSpan("monospace"), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        option.description?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            val from = length
            append(it)
            setSpan(RelativeSizeSpan(0.85f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun changesetButton(changesetId: Long) {
        val url = TaskText.changesetUrl(session.osmServer, changesetId) ?: return
        val where = if (session.osmServer == TaskText.DEV_OSM) " on development OSM" else ""
        body.addView(button("View changeset $changesetId$where") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                notice.text = "No browser is available. Changeset: $url"
            }
        })
    }

    private fun confirm(task: Task, form: ChoiceForm, action: ChoiceAction) {
        val user = session.view.value.userId ?: return
        val owner = controller // A dialog left open across an account switch must not write as the new account.
        val element = form.work.element
        val (heading, message) = when (action) {
            is ChoiceAction.Answers -> "Upload these answers?" to
                TaskText.answersSummary(form, task.id.value, user, session.osmServer)
            is ChoiceAction.Outcome -> "“${action.outcome.label}”?" to
                TaskText.outcomeSummary(action.outcome, element, task.id.value, user, session.osmServer,
                    notDeletable = action.outcome.id in form.notDeletable)
            ChoiceAction.Skip -> "Skip this task?" to
                "${TaskText.SKIP_EXPLANATION}\n\nThis records a skip for task ${task.id.value} in MapRoulette as user $user. It does not edit OpenStreetMap."
        }
        AlertDialog.Builder(this)
            .setTitle(heading)
            .setMessage(message)
            .setPositiveButton(if (action.editsOsm) "Upload" else "Confirm") { _, _ -> if (controller === owner) owner.perform(action) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doneText(state: TaskScreen.Done): String {
        val action = state.action
        return when {
            action is ChoiceAction.Answers && state.changesetId == null -> "Done. MapRoulette reported no OpenStreetMap changeset for your answers."
            action is ChoiceAction.Answers -> "Done. Your answers were uploaded to OpenStreetMap."
            action is ChoiceAction.Outcome && action.outcome.deletesElement -> {
                val element = (state.task.work() as? TaskWork.Choice)?.element?.let(TaskText::element) ?: "The element"
                "Done. $element was deleted from OpenStreetMap."
            }
            else -> "Done. Recorded “${actionLabel(action)}”."
        }
    }

    private fun confirmResend(state: TaskScreen.CheckAgain) {
        val user = session.view.value.userId ?: return
        val owner = controller
        val summary = when (val action = state.action) {
            is ChoiceAction.Answers -> TaskText.answersSummary(state.form.copy(answers = action.byQuestion), state.task.id.value, user, session.osmServer)
            is ChoiceAction.Outcome -> TaskText.outcomeSummary(action.outcome, state.form.work.element, state.task.id.value, user,
                session.osmServer, notDeletable = action.outcome.id in state.form.notDeletable)
            ChoiceAction.Skip -> return
        }
        AlertDialog.Builder(this)
            .setTitle("Send the same submission again?")
            .setMessage("MapRoulette resumes an unfinished submission and never uploads the same edit twice.\n\n$summary")
            .setPositiveButton("Send again") { _, _ -> if (controller === owner) owner.resendSame() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun actionLabel(action: ChoiceAction) = when (action) {
        is ChoiceAction.Answers -> "Submit answers"
        is ChoiceAction.Outcome -> action.outcome.label
        ChoiceAction.Skip -> TaskText.SKIP
    }

    private fun actionExplanation(form: ChoiceForm, action: ChoiceAction) = when (action) {
        is ChoiceAction.Answers -> ""
        is ChoiceAction.Outcome -> TaskText.outcomeExplanation(action.outcome, form.work.element, action.outcome.id in form.notDeletable)
        ChoiceAction.Skip -> TaskText.SKIP_EXPLANATION
    }

    // Back is blocked while a write is in flight (normally a few seconds).
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

    private fun label(value: CharSequence, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextIsSelectable(true)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(EXTRA_TASK_ID, taskId.value)
        outState.putLongArray(KEY_UPCOMING, upcoming.toLongArray())
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
            // A write already sent finishes (so a failed submit can release its lock); close afterwards.
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
        private const val EXTRA_TASK_IDS = "taskIds"
        private const val KEY_UPCOMING = "upcoming"
        const val REQUEST = 200

        /** [order] is the list or map order, used for "Next task". */
        fun intent(context: Context, id: TaskId, order: List<TaskId> = emptyList()): Intent =
            Intent(context, TaskActivity::class.java).putExtra(EXTRA_TASK_ID, id.value)
                .putExtra(EXTRA_TASK_IDS, order.map { it.value }.toLongArray())
    }
}
