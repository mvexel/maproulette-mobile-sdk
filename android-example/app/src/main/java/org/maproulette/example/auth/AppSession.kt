package org.maproulette.example.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.browser.customtabs.CustomTabsClient
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import org.maproulette.example.BuildConfig
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.OkHttpTransport
import org.maproulette.sdk.Transport
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public session state contains no credentials or provider error descriptions. */
data class SessionView(
    val generation: Long,
    val signedIn: Boolean,
    val userId: Long?,
    val message: String,
    /** The grant includes `tasks:write`. False for read-only grants and signed-out sessions. */
    val canWriteTasks: Boolean = false,
)
class SessionFailure(message: String) : Exception(message)

/** App-owned prototype. All mutable state is accessed on Dispatchers.Main. */
class AppSession private constructor(context: Context) {
    val endpoints = AuthEndpoints(BuildConfig.MAPROULETTE_BASE_URL, BuildConfig.MAPROULETTE_OAUTH_CLIENT_ID,
        BuildConfig.DEBUG && BuildConfig.MAPROULETTE_ALLOW_LOOPBACK)
    private val store = runCatching { EncryptedAuthStore(context, endpoints.storageBinding) }.getOrNull()
    val signInAvailable: Boolean get() = endpoints.enabled && store != null
    /** Task writes need sign-in and never target the production MapRoulette deployment. */
    val writesConfigured: Boolean get() = signInAvailable && writesAllowedFor(endpoints.origin, endpoints.loopbackAllowed)
    private val tokenMutex = Mutex()
    private var generation = 0L
    private var authState: AuthState? = null
    private var userId: Long? = null
    private var pendingState: String? = null
    private val clients = mutableSetOf<SessionClient>()
    private val configuration = AuthorizationServiceConfiguration(Uri.parse(endpoints.authorize), Uri.parse(endpoints.token))
    private val authorizationService = AuthorizationService(context, AppAuthConfiguration.Builder()
        .setConnectionBuilder { uri -> openConnection(uri.toString()) }.build())
    /** Browser cookies (including the OpenStreetMap login) are not shared with an ephemeral Custom
     * Tab, so every sign-in can choose an account. Without it, the browser's OSM login is reused. */
    val privateSignInSupported: Boolean = authorizationService.browserDescriptor.let { browser ->
        browser?.useCustomTab == true &&
            runCatching { CustomTabsClient.isEphemeralBrowsingSupported(context, browser.packageName) }.getOrDefault(false)
    }
    private val mutableView = MutableStateFlow(SessionView(0, false, null, signedOutMessage()))
    val view: StateFlow<SessionView> = mutableView
    val pendingFlow: String? get() = pendingState

    init {
        try {
            store?.read()?.let { encoded ->
                val saved = JSONObject(encoded)
                // A process death during refresh leaves its outcome uncertain. Never retry the old token.
                if (saved.optBoolean("refreshPending")) {
                    store?.clear()
                    publish("A previous refresh was interrupted. Sign in again.")
                } else {
                    pendingState = saved.optString("pending").takeIf { it.isNotBlank() }
                    userId = saved.optLong("userId").takeIf { it > 0 }
                    saved.optString("authState").takeIf { it.isNotBlank() }?.let {
                        val restored = AuthState.jsonDeserialize(it)
                        if (restored.isAuthorized && userId != null && endpoints.enabled) authState = restored
                    }
                    publish(if (authState != null) signedInMessage() else signedOutMessage())
                }
            }
        } catch (_: Exception) {
            runCatching { store?.clear() }
            authState = null
            userId = null
            pendingState = null
            publish("Saved sign-in could not be restored. Sign in again.")
        }
    }

    private fun signedOutMessage() = when {
        !endpoints.enabled -> "Sign-in needs a configured test backend and approved OAuth client ID. Public browsing is available."
        store == null -> "Secure credential storage is unavailable. Public browsing is available."
        else -> "Signed out · Public access"
    }

    private fun signedInMessage(): String {
        val readOnly = writesConfigured && authState?.scopeSet?.contains(WRITE_SCOPE) != true
        return "Signed in as MapRoulette user $userId" + if (readOnly) " · read-only sign-in" else ""
    }

    private fun publish(message: String) {
        val writable = authState?.scopeSet?.contains(WRITE_SCOPE) == true
        mutableView.value = SessionView(generation, authState != null, userId, message, writable)
    }

    private fun persist(refreshPending: Boolean = false) {
        val saved = JSONObject().put("refreshPending", refreshPending)
        authState?.let { saved.put("authState", it.jsonSerializeString()) }
        userId?.let { saved.put("userId", it) }
        pendingState?.let { saved.put("pending", it) }
        checkNotNull(store) { "Secure credential storage is unavailable" }.write(saved.toString())
    }

    private fun invalidate(message: String) {
        generation++
        authState = null
        userId = null
        pendingState = null
        clients.toList().forEach { it.close() }
        try {
            store?.clear()
        } finally {
            publish(message)
        }
    }

    /** [status] (for example the revocation result of a preceding sign-out) stays visible. */
    fun beginSignIn(status: String? = null): Intent {
        check(signInAvailable) { "Sign-in is not available" }
        invalidate(listOfNotNull(status, "Opening browser sign-in…").joinToString(" "))
        val request = AuthorizationRequest.Builder(configuration, endpoints.clientId, ResponseTypeValues.CODE,
            Uri.parse(endpoints.redirect)).setScope(requestedScope).build()
        // AppAuth supplies fresh state and an S256 verifier/challenge pair.
        pendingState = request.state
        persist()
        val tabs = authorizationService.createCustomTabsIntentBuilder()
            .apply { if (privateSignInSupported) setEphemeralBrowsingEnabled(true) }
            .build()
        return authorizationService.getAuthorizationRequestIntent(request, tabs)
    }

    private val requestedScope get() = if (writesConfigured) "$READ_SCOPE $WRITE_SCOPE" else READ_SCOPE

    fun cancelSignIn(message: String = "Sign-in canceled. Public browsing is available.") {
        invalidate(message)
    }

    suspend fun finishSignIn(data: Intent?, expectedFlow: String?): Unit = withContext(Dispatchers.Main.immediate) {
        // An old browser result must not cancel or replace a newer sign-in or signed-in account.
        if (expectedFlow == null || expectedFlow != pendingState) return@withContext
        val expected = generation
        val response = data?.let(AuthorizationResponse::fromIntent)
        val exception = data?.let(AuthorizationException::fromIntent)
        if (response == null || exception != null) {
            val reason = if (exception?.error == "access_denied") "Sign-in was declined."
                else "Sign-in canceled or could not complete."
            cancelSignIn(reason)
            return@withContext
        }
        if (pendingState == null || response.state != pendingState || response.request.state != pendingState ||
            data?.data?.toString()?.let(endpoints::acceptsCallback) != true ||
            response.request.clientId != endpoints.clientId || response.request.redirectUri.toString() != endpoints.redirect ||
            response.request.configuration.tokenEndpoint.toString() != endpoints.token) {
            cancelSignIn("Sign-in callback did not match this app's pending request.")
            return@withContext
        }
        pendingState = null
        persist()
        var stage = "token exchange"
        try {
            tokenMutex.withLock {
                // AppAuth token calls cannot be canceled. Finish this exchange, but never save it after logout.
                withContext(NonCancellable) {
                    val token = requestToken(response.createTokenExchangeRequest())
                    checkGeneration(expected)
                    stage = "token validation"
                    validateToken(token)
                    val next = AuthState(response, null).apply { update(token, null) }
                    stage = "account lookup"
                    val identity = withContext(Dispatchers.IO) {
                        OkHttpTransport().use { transport ->
                            MapRouletteClient(serviceUrl = endpoints.api, transport = transport,
                                accessToken = { token.accessToken }).getCurrentUser()
                        }
                    }
                    checkGeneration(expected)
                    check(!identity.guest && identity.id > 0 && identity.scopes == next.scopeSet)
                    stage = "secure storage"
                    authState = next
                    userId = identity.id
                    persist()
                    generation++
                    clients.toList().forEach { it.close() }
                    publish(signedInMessage())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) Log.w("MapRouletteAuth", "Sign-in failed during $stage: ${failure.javaClass.simpleName}")
            if (generation == expected) invalidate("Sign-in failed during $stage. Please try again.")
        }
    }

    private fun checkGeneration(expected: Long) {
        if (expected != generation) throw CancellationException("Session changed")
    }

    private fun validateToken(token: TokenResponse) {
        require(token.tokenType.equals("Bearer", true) && !token.accessToken.isNullOrBlank()) { "token type or access token" }
        require(!token.refreshToken.isNullOrBlank()) { "missing refresh token" }
        // A refresh must keep the grant's scope; a new grant may be read-only if the client is.
        val granted = scopes(token.scope)
        val expected = authState?.scopeSet
        require(if (expected != null) granted == expected else granted == scopes(READ_SCOPE) || granted == scopes(requestedScope)) {
            "unexpected scope"
        }
        require((token.accessTokenExpirationTime ?: 0L) > System.currentTimeMillis()) { "expired token" }
    }

    private fun scopes(value: String?): Set<String> = value?.split(' ')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private suspend fun requestToken(request: TokenRequest): TokenResponse = suspendCancellableCoroutine { continuation ->
        authorizationService.performTokenRequest(request) { response, exception ->
            if (response != null && exception == null) continuation.resume(response)
            else continuation.resumeWithException(SessionFailure("Token request failed"))
        }
    }

    private suspend fun accessToken(expected: Long): String? = withContext(Dispatchers.Main.immediate) {
        tokenMutex.withLock {
            checkGeneration(expected)
            val current = authState ?: return@withLock null
            if (current.needsTokenRefresh) {
                try {
                    // Persist uncertainty before a single-use refresh leaves the device.
                    persist(refreshPending = true)
                    withContext(NonCancellable) {
                        val response = requestToken(current.createTokenRefreshRequest())
                        checkGeneration(expected)
                        validateToken(response)
                        val next = AuthState.jsonDeserialize(current.jsonSerializeString()).apply { update(response, null) }
                        authState = next
                        persist()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Class and validation reason only; never token content.
                    if (BuildConfig.DEBUG) Log.w("MapRouletteAuth", "Refresh failed: ${failure.javaClass.simpleName} ${failure.message.orEmpty()}")
                    if (generation == expected) invalidate("Refresh could not complete safely. Sign in again.")
                    throw SessionFailure("Sign in again before retrying")
                }
            }
            currentCoroutineContext().ensureActive()
            checkGeneration(expected)
            authState?.accessToken
        }
    }

    /** One forced refresh after the server rejected [usedHeader]. Refresh failure signs out. */
    private suspend fun renewAfterRejection(expected: Long, usedHeader: String) = withContext(Dispatchers.Main.immediate) {
        val current = authState
        // Only the request that used the current token triggers a refresh; others see the renewed one.
        if (generation != expected || current == null || usedHeader != "Bearer ${current.accessToken}") return@withContext
        current.needsTokenRefresh = true
        try {
            accessToken(expected)
        } catch (_: SessionFailure) {
            // accessToken already cleared the session and published the sign-in prompt.
        }
    }

    /** Clear locally before contacting the server; old requests cannot acquire new-account tokens. */
    suspend fun signOut(): String = withContext(Dispatchers.Main.immediate) {
        val revocationToken = authState?.refreshToken ?: authState?.accessToken
        val localCleared = runCatching { invalidate("Signed out locally. Checking server revocation…") }.isSuccess
        val expected = generation
        val confirmed = if (revocationToken == null) true else withContext(Dispatchers.IO) {
            runCatching {
                val connection = openConnection(endpoints.revoke)
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = "client_id=${URLEncoder.encode(endpoints.clientId, "UTF-8")}&token=${URLEncoder.encode(revocationToken, "UTF-8")}"
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    connection.responseCode in 200..299
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }
        val message = if (!localCleared) "Signed out in memory, but secure storage could not be cleared. Server revocation ${if (confirmed) "confirmed" else "unconfirmed"}."
            else if (confirmed) "Signed out. Server revocation confirmed."
            else "Signed out locally. Server revocation could not be confirmed."
        if (generation == expected) publish(message)
        message
    }

    private fun openConnection(url: String): HttpURLConnection {
        require(endpoints.permitsConnection(url))
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
        }
    }

    fun newClient(): SessionClient {
        val expected = generation
        val transport = OkHttpTransport()
        val boundTransport = Transport { request ->
            withContext(Dispatchers.Main.immediate) {
                checkGeneration(expected)
                // Enforced below the UI: a lifecycle write leaves the device only for an allowlisted
                // backend and a grant that includes tasks:write. Everything else is refused unsent.
                if (isTaskWrite(request.url) && !(writesConfigured && authState?.scopeSet?.contains(WRITE_SCOPE) == true)) {
                    throw MapRouletteException(ErrorKind.PERMISSION)
                }
            }
            val response = transport.execute(request)
            withContext(Dispatchers.Main.immediate) {
                checkGeneration(expected)
            }
            // The rejected request is never resent: the SDK reports authentication to the caller.
            if (response.status == 401) request.headers["Authorization"]?.let { renewAfterRejection(expected, it) }
            response
        }
        val client = MapRouletteClient(serviceUrl = endpoints.api, transport = boundTransport,
            accessToken = { accessToken(expected) })
        return SessionClient(client, transport).also { clients.add(it) }
    }

    inner class SessionClient internal constructor(val client: MapRouletteClient, private val transport: OkHttpTransport) : Closeable {
        override fun close() {
            clients.remove(this)
            transport.close()
        }
    }

    companion object {
        const val READ_SCOPE = "tasks:read"
        const val WRITE_SCOPE = "tasks:write"

        /** Development writes may target only a disposable deployment, never maproulette.org. */
        /** Disposable deployments that may receive task writes (AGENTS.md). Never maproulette.org. */
        val WRITE_ORIGINS = setOf("https://mr-api.osm.lol")

        /** Exact origin allowlist; loopback only in a debug build that enabled it for local testing. */
        fun writesAllowedFor(origin: String, loopbackAllowed: Boolean): Boolean {
            val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
            if (uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return false
            if (origin in WRITE_ORIGINS) return true
            return loopbackAllowed && uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")
        }

        private val taskWrite = Regex("""/task/\d+/(start|refreshLock|release|skip|\d+)/?$""")

        /** Any task lifecycle write (start, refreshLock, release, skip or status), whatever the method. */
        fun isTaskWrite(url: String): Boolean =
            runCatching { java.net.URI(url).rawPath }.getOrNull()?.let { taskWrite.containsMatchIn(it) } ?: true

        @Volatile private var instance: AppSession? = null
        fun get(context: Context): AppSession = instance ?: synchronized(this) {
            instance ?: AppSession(context.applicationContext).also { instance = it }
        }
    }
}
