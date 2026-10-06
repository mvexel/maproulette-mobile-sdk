package org.maproulette.example.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maproulette.example.BuildConfig

/** Starts browser sign-in from an activity and hands the callback to [AppSession]. */
class SignInLauncher(private val activity: Activity, private val session: AppSession, private val scope: CoroutineScope) {
    private var pendingFlow: String? = null

    fun restore(state: Bundle?) {
        pendingFlow = state?.getString(KEY)
    }

    fun save(state: Bundle) {
        state.putString(KEY, pendingFlow)
    }

    /** Signs out first (revoking the current grant) when a session exists, e.g. a read-only one. */
    fun signIn() {
        scope.launch {
            // Keep the revocation result visible: the old grant may still be valid on the server.
            val revocation = if (session.view.value.signedIn) session.signOut() else null
            try {
                val intent = session.beginSignIn(revocation)
                pendingFlow = session.pendingFlow
                @Suppress("DEPRECATION") // AppAuth's activity-result flow, as in the original prototype.
                activity.startActivityForResult(intent, REQUEST)
            } catch (_: ActivityNotFoundException) {
                session.cancelSignIn("No browser is available for sign-in. Install or enable a browser.")
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.w("MapRouletteAuth", "Sign-in launch failed: ${failure.javaClass.simpleName}")
                session.cancelSignIn("Could not open browser sign-in. Check the test-backend configuration.")
            }
        }
    }

    /** Returns true when [requestCode] belonged to sign-in. */
    fun onActivityResult(requestCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val expected = pendingFlow
        pendingFlow = null
        scope.launch { session.finishSignIn(data, expected) }
        return true
    }

    /** Explains how account choice works for this browser. */
    fun accountHint(): String = if (session.privateSignInSupported) {
        "Sign-in opens a private browser tab, so you can choose any OpenStreetMap account each time."
    } else {
        "Your browser keeps you signed in to OpenStreetMap. To use another OSM account, sign out on the OpenStreetMap website in your browser first."
    }

    private companion object {
        const val REQUEST = 100
        const val KEY = "pendingAuthFlow"
    }
}
