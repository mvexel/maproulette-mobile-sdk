package org.maproulette.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ErrorKind
import org.maproulette.example.auth.AppSession
import org.maproulette.example.auth.SignInLauncher
import org.maproulette.example.task.TaskText
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId

/** A deliberately small SDK consumer. Task completion lives in [TaskActivity]. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: AppSession
    private lateinit var sessionClient: AppSession.SessionClient
    private val client get() = sessionClient.client
    private var requestJob: Job? = null
    private lateinit var signIn: SignInLauncher
    private lateinit var signInButton: Button
    private lateinit var authStatus: TextView
    private lateinit var accountHint: TextView
    private var loadedChallenge: ChallengeId? = null

    private lateinit var challengeInput: EditText
    private lateinit var loadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var retryButton: Button
    private lateinit var results: LinearLayout
    private var busy = false
    private var retryAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = AppSession.get(this)
        sessionClient = session.newClient()
        signIn = SignInLauncher(this, session, scope).apply { restore(savedInstanceState) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()

        content.addView(label("MapRoulette", 28f))
        content.addView(label("Browse a challenge and inspect its tasks.", 16f))
        authStatus = label(session.view.value.message, 14f)
        content.addView(authStatus)
        signInButton = Button(this).apply {
            text = signInText(session.view.value)
            isEnabled = session.signInAvailable
            setOnClickListener {
                val view = session.view.value
                if (view.signedIn && (view.canWriteTasks || !session.writesConfigured)) {
                    isEnabled = false
                    scope.launch {
                        try { session.signOut() } finally { isEnabled = session.signInAvailable }
                    }
                } else {
                    signIn.signIn()
                }
            }
        }
        content.addView(signInButton)
        accountHint = label(if (session.signInAvailable) signIn.accountHint() else "", 13f).apply {
            visibility = if (session.signInAvailable) View.VISIBLE else View.GONE
        }
        content.addView(accountHint)
        content.addView(Button(this).apply {
            text = "Nearby task map"
            setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java)) }
        })
        content.addView(label("Challenge ID", 16f))
        challengeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            contentDescription = "Challenge ID"
            setText(savedInstanceState?.getString("challengeId") ?: "16441")
            selectAll()
        }
        content.addView(challengeInput)
        loadButton = Button(this).apply {
            text = "Load challenge"
            setOnClickListener { loadChallenge() }
        }
        content.addView(loadButton)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        content.addView(progress)
        status = label("Enter a challenge ID to begin.", 15f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        retryButton = Button(this).apply {
            text = "Retry"
            visibility = View.GONE
            setOnClickListener { retryAction?.invoke() }
        }
        content.addView(retryButton)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        var observedGeneration = session.view.value.generation
        scope.launch {
            session.view.collect { view ->
                if (view.generation != observedGeneration) {
                    requestJob?.cancel()
                    sessionClient.close()
                    sessionClient = session.newClient()
                    results.removeAllViews()
                    retryAction = null
                    retryButton.visibility = View.GONE
                    observedGeneration = view.generation
                    // Reload the list as the new account (or anonymously) after a switch.
                    if (loadedChallenge != null && !busy) loadChallenge()
                }
                authStatus.text = view.message
                signInButton.text = signInText(view)
                signInButton.isEnabled = session.signInAvailable
            }
        }
    }

    private fun loadChallenge() {
        if (busy) return
        val value = challengeInput.text.toString().trim().toLongOrNull()
        if (value == null || value <= 0) {
            challengeInput.error = "Enter a positive challenge ID"
            return
        }
        val id = ChallengeId(value)
        results.removeAllViews()
        loadedChallenge = id
        runRequest("Loading challenge…", ::loadChallenge) {
            val currentClient = client
            val challenge = currentClient.getChallenge(id)
            val page = currentClient.listTasks(id, pageSize = 20)
            results.addView(label(challenge.name, 23f))
            results.addView(label("Challenge ${challenge.id.value}", 14f))
            challenge.description?.takeIf { it.isNotBlank() }?.let {
                results.addView(label(it, 16f))
            }
            results.addView(label("Instructions", 19f))
            results.addView(label(challenge.instruction?.takeIf { it.isNotBlank() } ?: "No challenge instructions.", 16f))
            results.addView(label("Tasks", 21f))
            if (page.items.isEmpty()) {
                results.addView(label("No tasks returned for this challenge.", 16f))
            }
            page.items.forEach { task ->
                results.addView(Button(this).apply {
                    isAllCaps = false
                    text = "${task.name}\nTask ${task.id.value} · ${statusLabel(task)}"
                    setOnClickListener { showTask(task.id) }
                })
            }
            status.text = if (page.next != null) {
                "Showing the first ${page.items.size} tasks. This example loads one page."
            } else {
                "Loaded ${page.items.size} tasks. Tap a task for details."
            }
        }
    }

    private fun showTask(id: TaskId) {
        @Suppress("DEPRECATION") // Plain Activity result API, as for AppAuth.
        startActivityForResult(TaskActivity.intent(this, id), TaskActivity.REQUEST)
    }

    private fun signInText(view: org.maproulette.example.auth.SessionView) = when {
        !view.signedIn -> "Sign in"
        session.writesConfigured && !view.canWriteTasks -> "Sign in again to enable task actions"
        else -> "Sign out"
    }

    private fun runRequest(message: String, retry: () -> Unit, block: suspend () -> Unit) {
        busy = true
        loadButton.isEnabled = false
        challengeInput.isEnabled = false
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        retryAction = null
        status.text = message
        requestJob = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = errorMessage(error)
                retryAction = retry
                retryButton.visibility = View.VISIBLE
            } finally {
                busy = false
                loadButton.isEnabled = true
                challengeInput.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is MapRouletteException -> when (error.kind) {
            ErrorKind.NOT_FOUND -> "Not found. Check the challenge ID or try another task."
            ErrorKind.AUTHENTICATION, ErrorKind.PERMISSION -> "This request is not available anonymously. Try a public challenge."
            ErrorKind.NETWORK -> "Could not connect to MapRoulette. Check your connection and retry."
            ErrorKind.RATE_LIMIT -> "MapRoulette is limiting requests. Wait a moment and retry."
            ErrorKind.SERVER -> "MapRoulette is temporarily unavailable. Please retry."
            else -> "MapRoulette returned an unexpected response. Please retry."
        }
        else -> "Could not load the data. Please retry."
    }

    private fun statusLabel(task: Task): String = TaskText.status(task.status?.code)

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextIsSelectable(true)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("challengeId", challengeInput.text.toString())
        signIn.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        sessionClient.close()
        super.onDestroy()
    }

    @Deprecated("Prototype uses AppAuth's activity-result flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (signIn.onActivityResult(requestCode, data)) return
        // A resolution changes the task list; reload it so the new status shows.
        if (requestCode == TaskActivity.REQUEST && data?.getBooleanExtra(TaskActivity.EXTRA_CHANGED, false) == true &&
            loadedChallenge != null && !busy) loadChallenge()
    }
}
