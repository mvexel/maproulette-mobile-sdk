package org.maproulette.example.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.OkHttpTransport
import org.maproulette.sdk.Transport
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public session state contains no credentials or provider error descriptions. */
data class SessionView(val generation: Long, val signedIn: Boolean, val userId: Long?, val message: String)
class SessionFailure(message: String) : Exception(message)

/** App-owned prototype. All mutable state is accessed on Dispatchers.Main. */
class AppSession private constructor(context: Context) {
    val endpoints = AuthEndpoints(BuildConfig.MAPROULETTE_BASE_URL, BuildConfig.MAPROULETTE_OAUTH_CLIENT_ID,
        BuildConfig.DEBUG && BuildConfig.MAPROULETTE_ALLOW_LOOPBACK)
    private val store = runCatching { EncryptedAuthStore(context, endpoints.storageBinding) }.getOrNull()
    val signInAvailable: Boolean get() = endpoints.enabled && store != null
    private val tokenMutex = Mutex()
    private var generation = 0L
    private var authState: AuthState? = null
    private var userId: Long? = null
    private var pendingState: String? = null
    private val clients = mutableSetOf<SessionClient>()
    private val configuration = AuthorizationServiceConfiguration(Uri.parse(endpoints.authorize), Uri.parse(endpoints.token))
    private val authorizationService = AuthorizationService(context, AppAuthConfiguration.Builder()
        .setConnectionBuilder { uri -> openConnection(uri.toString()) }.build())
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
                    publish(if (authState != null) "Signed in as MapRoulette user $userId" else signedOutMessage())
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

    private fun publish(message: String) {
        mutableView.value = SessionView(generation, authState != null, userId, message)
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

    fun beginSignIn(): Intent {
        check(signInAvailable) { "Sign-in is not available" }
        invalidate("Opening browser sign-in…")
        val request = AuthorizationRequest.Builder(configuration, endpoints.clientId, ResponseTypeValues.CODE,
            Uri.parse(endpoints.redirect)).setScope("tasks:read").build()
        // AppAuth supplies fresh state and an S256 verifier/challenge pair.
        pendingState = request.state
        persist()
        return authorizationService.getAuthorizationRequestIntent(request)
    }

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
        try {
            tokenMutex.withLock {
                // AppAuth token calls cannot be canceled. Finish this exchange, but never save it after logout.
                withContext(NonCancellable) {
                    val token = requestToken(response.createTokenExchangeRequest())
                    checkGeneration(expected)
                    validateToken(token)
                    val next = AuthState(response, null).apply { update(token, null) }
                    val identity = OkHttpTransport().use { transport ->
                        MapRouletteClient(serviceUrl = endpoints.api, transport = transport,
                            accessToken = { token.accessToken }).getCurrentUser()
                    }
                    checkGeneration(expected)
                    check(!identity.guest && identity.id > 0)
                    authState = next
                    userId = identity.id
                    persist()
                    generation++
                    clients.toList().forEach { it.close() }
                    publish("Signed in as MapRoulette user ${identity.id}")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == expected) invalidate("Sign-in could not complete. Please try again.")
        }
    }

    private fun checkGeneration(expected: Long) {
        if (expected != generation) throw CancellationException("Session changed")
    }

    private fun validateToken(token: TokenResponse) {
        require(token.tokenType.equals("Bearer", true) && !token.accessToken.isNullOrBlank())
        require(!token.refreshToken.isNullOrBlank() && token.scope == "tasks:read")
        require((token.accessTokenExpirationTime ?: 0L) > System.currentTimeMillis())
    }

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
                } catch (_: Exception) {
                    if (generation == expected) invalidate("Refresh could not complete safely. Sign in again.")
                    throw SessionFailure("Sign in again before retrying")
                }
            }
            currentCoroutineContext().ensureActive()
            checkGeneration(expected)
            authState?.accessToken
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
            withContext(Dispatchers.Main.immediate) { checkGeneration(expected) }
            val response = transport.execute(request)
            withContext(Dispatchers.Main.immediate) {
                checkGeneration(expected)
                if (response.status == 401 && request.headers.containsKey("Authorization")) {
                    invalidate("This sign-in is no longer valid. Sign in again.")
                    throw SessionFailure("Sign in again before retrying")
                }
            }
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
        @Volatile private var instance: AppSession? = null
        fun get(context: Context): AppSession = instance ?: synchronized(this) {
            instance ?: AppSession(context.applicationContext).also { instance = it }
        }
    }
}
