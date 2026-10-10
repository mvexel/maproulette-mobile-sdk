package org.maproulette.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** The native OAuth client an app registered on a mobile-enabled backend (fork backend only).
 * Throws IllegalArgumentException for a malformed client ID, or a redirect URI without a scheme or
 * with credentials, a query or a fragment.
 *
 * @param redirectUri the exact registered callback, e.g. `com.example.app:/oauth2redirect`. */
data class MobileSignInConfiguration(
    val environment: MapRouletteEnvironment,
    val clientId: String,
    val redirectUri: String,
) {
    private val redirect: URI

    init {
        require(clientId.matches(Regex("[A-Za-z0-9._-]{1,100}"))) {
            "clientId must be 1-100 characters of A-Z a-z 0-9 . _ -"
        }
        val uri = runCatching { URI(redirectUri) }.getOrNull()
        require(
            uri != null && !uri.scheme.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null,
        ) { "redirectUri must have a scheme and no credentials, query or fragment" }
        redirect = uri
    }

    /** The scheme to register for the browser callback (e.g. Custom Tabs / AppAuth redirect). */
    val callbackScheme: String get() = redirect.scheme

    /** Separates stored credentials per backend origin and client. */
    val storageBinding: String get() = "${origin("")}|$clientId"

    internal val authorizeUrl get() = origin("/oauth/mobile/authorize")
    internal val tokenUrl get() = origin("/oauth/mobile/token")
    internal val revokeUrl get() = origin("/oauth/mobile/revoke")
    internal val guestUrl get() = origin("/oauth/mobile/guest")

    /** OAuth routes live at the backend origin, not under the API path. */
    private fun origin(path: String): String {
        val service = URI(environment.serviceUrl)
        return "${service.scheme}://${service.rawAuthority}$path"
    }

    /** The browser callback must be exactly the registered redirect (a query is allowed). */
    internal fun accepts(callback: URI): Boolean =
        callback.scheme?.lowercase() == redirect.scheme.lowercase() && callback.host == redirect.host &&
            callback.port == redirect.port && callback.path == redirect.path && callback.rawFragment == null

    companion object {
        const val READ_SCOPE: String = "tasks:read"
        const val WRITE_SCOPE: String = "tasks:write"
        const val TAGFIX_SCOPE: String = "osm:tagfix"

        /** Scopes for an app that answers choice tasks when [writes], else read only. */
        fun scopes(writes: Boolean): Set<String> =
            if (writes) setOf(READ_SCOPE, WRITE_SCOPE, TAGFIX_SCOPE) else setOf(READ_SCOPE)
    }
}

/** Where a signed-in grant (and a guest credential) is kept between launches, as one opaque text.
 * Implementations must keep it secret (on Android, e.g. encrypted storage backed by the Keystore)
 * and never log it. They may suspend for I/O. */
interface CredentialStore {
    suspend fun read(): String?
    suspend fun write(data: String)
    suspend fun clear()
}

/** A store that forgets everything when released: for tests, previews and demo modes. */
class InMemoryCredentialStore(data: String? = null) : CredentialStore {
    @Volatile private var data: String? = data
    override suspend fun read(): String? = data
    override suspend fun write(data: String) { this.data = data }
    override suspend fun clear() { data = null }
}

/** A signed-in account. [generation] changes on every sign-in and sign-out; a client bound to an
 * older generation can no longer use credentials. [displayName] is the OSM display name, or null
 * when the server didn't send one (or the account was restored from storage written before it). */
data class MobileAccount(
    val userId: Long,
    val scopes: Set<String>,
    val generation: Int,
    val displayName: String? = null,
) {
    val canWriteTasks: Boolean get() = MobileSignInConfiguration.WRITE_SCOPE in scopes
    val canEditOsm: Boolean get() = canWriteTasks && MobileSignInConfiguration.TAGFIX_SCOPE in scopes
}

/** A guest: answers are stored, not published, until the guest is claimed with an OSM account.
 * [generation] changes like [MobileAccount.generation]. */
data class MobileGuest(val guestId: String, val generation: Int)

/** What [MobileSignIn.upgradeGuest] found. */
sealed interface GuestUpgrade {
    /** The guest was claimed: now signed in as this account; the guest credentials are gone. */
    data class SignedIn(val account: MobileAccount) : GuestUpgrade

    /** Not claimed yet, or the backend can't issue write grants right now: still a guest; try later. */
    data object NotYet : GuestUpgrade
}

/** Why a sign-in did not finish. None of these carry server text or credentials. */
sealed interface SignInFailure {
    /** The browser step was canceled or failed. */
    data object Canceled : SignInFailure

    /** The callback was not the registered redirect, or its `state` did not match. */
    data object CallbackMismatch : SignInFailure

    /** The user declined on the provider's page (`access_denied`). */
    data object Denied : SignInFailure

    /** Another OAuth error code from the provider. */
    data class ProviderError(val code: String) : SignInFailure

    /** The token exchange failed or returned an unacceptable grant. */
    data object TokenExchange : SignInFailure

    /** The new token could not be checked with `oauth/mobile/me`, or it did not match the grant. */
    data object AccountLookup : SignInFailure

    /** The credential store failed. */
    data object Storage : SignInFailure

    /** A newer sign-in or a sign-out started meanwhile; this result was discarded. */
    data object Superseded : SignInFailure
}

/** Thrown by [MobileSignIn] when a sign-in does not finish; see [failure]. Safe to log. */
class SignInException(val failure: SignInFailure) :
    Exception("sign-in failed: ${failure::class.simpleName}")

/** What [MobileSignIn.restore] found in the credential store. */
sealed interface RestoreResult {
    data class SignedIn(val account: MobileAccount) : RestoreResult

    /** A guest (no signed-in account). */
    data class Guest(val guest: MobileGuest) : RestoreResult

    data object SignedOut : RestoreResult

    /** The app died during a token refresh, so the saved refresh token may be spent. Signed out. */
    data object RefreshInterrupted : RestoreResult

    /** The saved data was unreadable or belongs to another backend or client. Signed out. */
    data object Unreadable : RestoreResult
}

/** The result of [MobileSignIn.signOut]. */
data class SignOutResult(
    /** The stored grant was removed. When false, it is gone from memory only. */
    val localCleared: Boolean,
    /** The backend confirmed the revocation. False when it failed or there was nothing to revoke. */
    val revocationConfirmed: Boolean,
)

/** Browser sign-in for a mobile-enabled backend: OAuth authorization code with S256 PKCE, grant
 * storage, serialized refresh, and revocation, plus guests (deferred sign-up). UI-free: the app
 * opens the authorization URL in a browser (e.g. a Custom Tab) and passes the callback back.
 *
 * A refresh token is used at most once. Before a refresh leaves the device, the store records it as
 * pending; if the app dies before the result is saved, [restore] signs out instead of retrying a
 * token that may already be spent. A failed refresh signs out too.
 *
 * Methods are safe to call from several coroutines. A request that belongs to an older
 * [generation] (after a sign-out or account switch) fails with [CancellationException].
 *
 * @param transport used for the OAuth routes and identity check, and by [client]; the caller closes it.
 * @param now the clock used for token expiry. */
class MobileSignIn(
    val configuration: MobileSignInConfiguration,
    private val store: CredentialStore,
    private val transport: Transport,
    private val now: () -> Instant = Instant::now,
) {
    /** The signed-in account, null when signed out. */
    @Volatile var account: MobileAccount? = null
        private set

    /** The guest, when the app answers as a guest. Kept across sign-in and sign-out so that the
     * guest's answers can still be claimed; [forgetGuest] removes it. */
    @Volatile var guest: MobileGuest? = null
        private set

    /** Bumped on every sign-in and sign-out. */
    @Volatile var generation: Int = 0
        private set

    // Credential values stay in this class; no data-class toString can print them.
    private class Grant(val accessToken: String, val refreshToken: String, val expiresAt: Instant, val scopes: Set<String>) {
        fun expired() = Grant(accessToken, refreshToken, Instant.EPOCH, scopes)
    }
    private class GuestCredential(val id: String, val secret: String)
    private class GuestToken(val value: String, val expiresAt: Instant)

    private val lock = Mutex()
    private var grant: Grant? = null
    private var guestCredential: GuestCredential? = null
    private var guestToken: GuestToken? = null
    private var refreshing: CompletableDeferred<Unit>? = null

    // Persistence

    /** Loads the saved grant. Call once at launch, before handing out clients. */
    suspend fun restore(): RestoreResult = lock.withLock {
        try {
            val text = store.read() ?: return@withLock RestoreResult.SignedOut
            val saved = Json.parseToJsonElement(text).jsonObject
            require(saved.string("binding") == configuration.storageBinding)
            val savedGuest = saved.objectOrNull("guest")?.let { GuestCredential(it.string("id"), it.string("secret")) }
            val savedGrant = saved.objectOrNull("grant")?.let {
                Grant(
                    it.string("accessToken"), it.string("refreshToken"),
                    Instant.ofEpochMilli(it.getValue("expiresAt").jsonPrimitive.long),
                    it.getValue("scopes").jsonArray.map { scope -> scope.jsonPrimitive.content }.toSet(),
                )
            }
            val user = saved["userID"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
            val name = (saved["displayName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val pending = saved["refreshPending"]?.jsonPrimitive?.boolean ?: false
            guestCredential = savedGuest
            if (pending) {
                // The guest credential doesn't rotate, so it survives an interrupted refresh.
                quietly { invalidate(keepGuest = true) }
                return@withLock guest?.let(RestoreResult::Guest) ?: RestoreResult.RefreshInterrupted
            }
            if (savedGrant == null || user == null || user <= 0) {
                generation += 1
                val credential = savedGuest ?: return@withLock RestoreResult.SignedOut
                val guest = MobileGuest(credential.id, generation)
                this.guest = guest
                return@withLock RestoreResult.Guest(guest)
            }
            grant = savedGrant
            generation += 1
            savedGuest?.let { guest = MobileGuest(it.id, generation) }
            val account = MobileAccount(user, savedGrant.scopes, generation, name)
            this.account = account
            RestoreResult.SignedIn(account)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            quietly { store.clear() }
            grant = null
            account = null
            guest = null
            guestCredential = null
            RestoreResult.Unreadable
        }
    }

    // Callers hold the lock.
    private suspend fun persist(refreshPending: Boolean = false) {
        val saved = buildJsonObject {
            put("binding", configuration.storageBinding)
            grant?.let {
                putJsonObject("grant") {
                    put("accessToken", it.accessToken)
                    put("refreshToken", it.refreshToken)
                    put("expiresAt", it.expiresAt.toEpochMilli())
                    putJsonArray("scopes") { it.scopes.sorted().forEach(::add) }
                }
            }
            account?.let {
                put("userID", it.userId)
                it.displayName?.let { name -> put("displayName", name) }
            }
            put("refreshPending", refreshPending)
            guestCredential?.let {
                putJsonObject("guest") {
                    put("id", it.id)
                    put("secret", it.secret)
                }
            }
        }
        store.write(saved.toString())
    }

    /** Ends the current generation: older clients and in-flight requests lose their credentials.
     * With [keepGuest], the guest credential stays (and stays stored). Callers hold the lock. */
    private suspend fun invalidate(keepGuest: Boolean = false) {
        generation += 1
        grant = null
        account = null
        refreshing = null
        guestToken = null
        if (!keepGuest) guestCredential = null
        guest = guestCredential?.let { MobileGuest(it.id, generation) }
        if (guestCredential == null) store.clear() else persist()
    }

    private fun checkGeneration(expected: Int) {
        if (expected != generation) throw CancellationException("signed out or switched account")
    }

    // Sign-in

    /** Signs in with the browser. [authenticate] opens the URL in a browser session for
     * [MobileSignInConfiguration.callbackScheme] and returns the callback URL; throwing from it
     * means canceled. A current account is signed out (and revoked) first. Throws [SignInException]. */
    suspend fun signIn(scopes: Set<String>, authenticate: suspend (String) -> String): MobileAccount {
        if (account != null) signOut()
        val expected = lock.withLock {
            quietly { invalidate(keepGuest = true) }
            generation
        }
        val verifier = randomToken()
        val state = randomToken()
        val url = configuration.authorizeUrl + "?" + formEncode(
            linkedMapOf(
                "response_type" to "code",
                "client_id" to configuration.clientId,
                "redirect_uri" to configuration.redirectUri,
                "scope" to scopes.sorted().joinToString(" "),
                "state" to state,
                "code_challenge" to challenge(verifier),
                "code_challenge_method" to "S256",
            ),
        )
        val callback = try {
            authenticate(url)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw SignInException(if (generation == expected) SignInFailure.Canceled else SignInFailure.Superseded)
        }
        // An old browser result must not replace a newer sign-in.
        if (generation != expected) throw SignInException(SignInFailure.Superseded)
        val callbackUri = runCatching { URI(callback) }.getOrNull()
        val items = callbackUri?.rawQuery?.let(::formDecode) ?: emptyMap()
        if (callbackUri == null || !configuration.accepts(callbackUri) || items["state"] != state) {
            throw SignInException(SignInFailure.CallbackMismatch)
        }
        items["error"]?.let {
            throw SignInException(if (it == "access_denied") SignInFailure.Denied else SignInFailure.ProviderError(it))
        }
        val code = items["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException(SignInFailure.CallbackMismatch)

        val next = try {
            parseGrant(
                post(
                    configuration.tokenUrl,
                    mapOf(
                        "grant_type" to "authorization_code", "code" to code,
                        "redirect_uri" to configuration.redirectUri,
                        "client_id" to configuration.clientId, "code_verifier" to verifier,
                    ),
                ),
                current = null, requested = scopes,
            )
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw SignInException(if (generation == expected) SignInFailure.TokenExchange else SignInFailure.Superseded)
        }
        if (generation != expected) throw SignInException(SignInFailure.Superseded)
        val identity = try {
            identity(next)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw SignInException(if (generation == expected) SignInFailure.AccountLookup else SignInFailure.Superseded)
        }
        return lock.withLock {
            if (generation != expected) throw SignInException(SignInFailure.Superseded)
            if (identity.guest || identity.id <= 0 || identity.scopes != next.scopes) {
                throw SignInException(SignInFailure.AccountLookup)
            }
            grant = next
            generation += 1
            guestToken = null
            val account = MobileAccount(identity.id, next.scopes, generation, identity.displayName)
            this.account = account
            guestCredential?.let { guest = MobileGuest(it.id, generation) }
            try {
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                quietly { invalidate(keepGuest = true) }
                throw SignInException(SignInFailure.Storage)
            }
            account
        }
    }

    private suspend fun identity(grant: Grant): UserIdentity {
        val token = grant.accessToken
        return MapRouletteClient(configuration.environment, transport, accessToken = { token }).getCurrentUser()
    }

    private fun parseGrant(response: HttpResponse, current: Grant?, requested: Set<String>): Grant {
        val grant = try {
            require(response.status == 200)
            val o = Json.parseToJsonElement(response.body).jsonObject
            require(o.string("token_type").lowercase() == "bearer")
            val access = o.string("access_token")
            val refresh = o.string("refresh_token")
            require(access.isNotEmpty() && refresh.isNotEmpty())
            val expiresIn = o.getValue("expires_in").jsonPrimitive.also { require(!it.isString) }.double
            require(expiresIn > 0)
            val granted = (o["scope"]?.takeUnless { it == JsonNull }?.let { (it as JsonPrimitive).also { p -> require(p.isString) }.content } ?: "")
                .split(' ').filter { it.isNotEmpty() }.toSet()
            Grant(access, refresh, now().plusMillis((expiresIn * 1000).toLong()), granted)
        } catch (_: Exception) {
            throw SignInException(SignInFailure.TokenExchange)
        }
        // A refresh must keep the grant's scope; a new grant may be narrower than requested.
        val ok = if (current != null) grant.scopes == current.scopes else acceptableGrant(grant.scopes, requested)
        if (!ok) throw SignInException(SignInFailure.TokenExchange)
        return grant
    }

    // Tokens

    private sealed interface Step {
        class Done(val token: String?) : Step
        class Wait(val job: CompletableDeferred<Unit>) : Step
        class Run(val job: CompletableDeferred<Unit>, val guest: Boolean, val work: suspend () -> Unit) : Step
    }

    /** The access token for [generation], refreshed when it expires within a minute; for a guest, a
     * guest token (scope `guest`); null when signed out. Throws [CancellationException] when the
     * generation is stale. Refreshes are serialized; a failed refresh signs out (MapRouletteException
     * AUTHENTICATION). For a guest whose answers were claimed it throws CONFLICT with reason
     * "guest_claimed": run [upgradeGuest]. */
    suspend fun accessToken(generation: Int): String? {
        val expected = generation
        while (true) {
            val step = lock.withLock {
                checkGeneration(expected)
                refreshing?.let { return@withLock Step.Wait(it) }
                val current = grant
                if (current == null) {
                    val credential = guestCredential ?: return@withLock Step.Done(null)
                    guestToken?.takeIf { msLeft(it.expiresAt) >= 60_000 }?.let { return@withLock Step.Done(it.value) }
                    Step.Run(CompletableDeferred<Unit>().also { refreshing = it }, guest = true) {
                        fetchGuestToken(credential, expected)
                    }
                } else if (msLeft(current.expiresAt) < 60_000) {
                    Step.Run(CompletableDeferred<Unit>().also { refreshing = it }, guest = false) {
                        refresh(current, expected)
                    }
                } else Step.Done(current.accessToken)
            }
            when (step) {
                is Step.Done -> {
                    currentCoroutineContext().ensureActive()
                    return step.token
                }
                is Step.Wait -> step.job.await()
                is Step.Run -> {
                    run(step)
                    currentCoroutineContext().ensureActive()
                    return lock.withLock {
                        checkGeneration(expected)
                        if (step.guest) guestToken?.value else grant?.accessToken
                    }
                }
            }
        }
    }

    /** Runs a refresh to completion even when the caller is canceled (a refresh token is single-use),
     * sharing its outcome with concurrent callers. */
    private suspend fun run(step: Step.Run) {
        try {
            withContext(NonCancellable) { step.work() }
            step.job.complete(Unit)
        } catch (e: Throwable) {
            step.job.completeExceptionally(e)
            throw e
        } finally {
            withContext(NonCancellable) { lock.withLock { if (refreshing === step.job) refreshing = null } }
        }
    }

    private fun msLeft(at: Instant): Long = java.time.Duration.between(now(), at).toMillis()

    private suspend fun refresh(current: Grant, expected: Int) {
        try {
            // Record the uncertainty before a single-use refresh token leaves the device.
            lock.withLock { persist(refreshPending = true) }
            val next = parseGrant(
                post(
                    configuration.tokenUrl,
                    mapOf(
                        "grant_type" to "refresh_token", "client_id" to configuration.clientId,
                        "refresh_token" to current.refreshToken,
                    ),
                ),
                current = current, requested = emptySet(),
            )
            lock.withLock {
                checkGeneration(expected)
                grant = next
                persist()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            lock.withLock { if (generation == expected) quietly { invalidate(keepGuest = true) } }
            throw MapRouletteException(ErrorKind.AUTHENTICATION)
        }
    }

    /** One forced refresh after the server rejected the token in [usedHeader] (`Bearer …`). Does
     * nothing when the token was already replaced or a refresh is running. */
    suspend fun renewAfterRejection(generation: Int, usedHeader: String) {
        val expected = generation
        val renew = lock.withLock {
            val token = guestToken
            if (grant == null && this.generation == expected && token != null && usedHeader == "Bearer ${token.value}") {
                guestToken = null
                return@withLock false
            }
            val current = grant
            if (this.generation != expected || current == null || usedHeader != "Bearer ${current.accessToken}" ||
                refreshing != null
            ) return@withLock false
            grant = current.expired()
            true
        }
        if (!renew) return
        try {
            accessToken(expected)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }

    /** Signs out: clears locally first (older clients lose their credentials at once), then asks the
     * backend to revoke the grant. A guest credential stays; see [forgetGuest]. */
    suspend fun signOut(): SignOutResult {
        var token: String? = null
        val localCleared = lock.withLock {
            token = grant?.refreshToken ?: grant?.accessToken
            quietly { invalidate(keepGuest = true) }
        }
        val confirmed = token?.let {
            try {
                post(configuration.revokeUrl, mapOf("client_id" to configuration.clientId, "token" to it)).status in 200..299
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        } ?: false
        return SignOutResult(localCleared, confirmed)
    }

    // Guests

    /** Registers a guest (`POST /oauth/mobile/guest`) when nobody is signed in and there is no guest
     * yet; otherwise returns the current guest. The secret goes to the credential store and never
     * leaves this object. Throws [MapRouletteException]: NOT_FOUND when the backend has guests off,
     * RATE_LIMIT (with `retryAfter`), AUTHENTICATION for a client without guest access;
     * IllegalStateException while signed in. */
    suspend fun startGuest(): MobileGuest {
        val expected = lock.withLock {
            guest?.let { return it }
            check(account == null) { "sign out before starting a guest" }
            generation
        }
        val response = post(configuration.guestUrl, mapOf("client_id" to configuration.clientId))
        if (response.status != 201) throw oauthFailure(response)
        val credential = try {
            val o = Json.parseToJsonElement(response.body).jsonObject
            val id = o.string("guestId")
            val secret = o.string("guestSecret")
            require(id.matches(Regex("[A-Za-z0-9-]{1,64}")) && isToken(secret))
            GuestCredential(id, secret)
        } catch (_: Exception) {
            throw MapRouletteException(ErrorKind.PROTOCOL, response.status)
        }
        return lock.withLock {
            // A sign-in or another guest started meanwhile.
            if (generation != expected || account != null || guest != null) {
                throw CancellationException("signed in or another guest started meanwhile")
            }
            guestCredential = credential
            generation += 1
            val guest = MobileGuest(credential.id, generation)
            this.guest = guest
            try {
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                quietly { invalidate() }
                throw SignInException(SignInFailure.Storage)
            }
            guest
        }
    }

    /** Signs in a claimed guest (`guest_claim` grant): when the guest's answers were claimed on the
     * web, the phone becomes that account and the guest credentials are dropped. [GuestUpgrade.NotYet]
     * while unclaimed or while the backend issues no write grants. IllegalStateException without a guest
     * or while signed in. */
    suspend fun upgradeGuest(): GuestUpgrade {
        val (expected, credential) = lock.withLock {
            val credential = guestCredential
            check(account == null && credential != null) { "no guest to upgrade" }
            generation to credential
        }
        val requested = MobileSignInConfiguration.scopes(writes = true)
        val response = post(
            configuration.tokenUrl,
            mapOf(
                "grant_type" to GUEST_CLAIM_GRANT, "client_id" to configuration.clientId,
                "guest_id" to credential.id, "guest_secret" to credential.secret,
            ),
        )
        if (response.status == 400 && oauthError(response.body) in setOf("claim_pending", "invalid_scope")) {
            return GuestUpgrade.NotYet
        }
        if (response.status != 200) throw oauthFailure(response)
        val next = parseGrant(response, current = null, requested = requested)
        checkGeneration(expected)
        val identity = identity(next)
        return lock.withLock {
            checkGeneration(expected)
            if (identity.guest || identity.id <= 0 || identity.scopes != next.scopes) {
                throw SignInException(SignInFailure.AccountLookup)
            }
            grant = next
            guestCredential = null
            guestToken = null
            guest = null
            generation += 1
            val account = MobileAccount(identity.id, next.scopes, generation, identity.displayName)
            this.account = account
            try {
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                quietly { invalidate() }
                throw SignInException(SignInFailure.Storage)
            }
            GuestUpgrade.SignedIn(account)
        }
    }

    /** Drops the guest credential, e.g. after [MapRouletteClient.deleteGuest]. Unclaimed answers can
     * no longer be reached from this phone. A signed-in account stays. Store failures propagate. */
    suspend fun forgetGuest() {
        lock.withLock { forgetGuestLocked() }
    }

    private suspend fun forgetGuestLocked() {
        guestCredential = null
        guestToken = null
        guest = null
        if (grant == null) invalidate() else persist()
    }

    /** A short-lived guest token (`guest` grant). */
    private suspend fun fetchGuestToken(credential: GuestCredential, expected: Int) {
        val response = post(
            configuration.tokenUrl,
            mapOf(
                "grant_type" to GUEST_GRANT, "client_id" to configuration.clientId,
                "guest_id" to credential.id, "guest_secret" to credential.secret,
            ),
        )
        lock.withLock { receivedGuestToken(response, expected) }
    }

    private suspend fun receivedGuestToken(response: HttpResponse, expected: Int) {
        checkGeneration(expected)
        val error = oauthError(response.body)
        if (response.status == 400 && error == "guest_claimed") {
            // Answers were claimed: run upgradeGuest().
            throw MapRouletteException(ErrorKind.CONFLICT, 400, reason = "guest_claimed")
        }
        if (response.status != 200) {
            // Unknown, deleted or expired guest: the credential is useless.
            if (response.status == 400 || response.status == 401) quietly { forgetGuestLocked() }
            throw MapRouletteException(ErrorKind.AUTHENTICATION, response.status, reason = error)
        }
        guestToken = try {
            val o = Json.parseToJsonElement(response.body).jsonObject
            require(o.string("token_type").lowercase() == "bearer" && o.string("scope") == "guest")
            val access = o.string("access_token")
            val expiresIn = o.getValue("expires_in").jsonPrimitive.also { require(!it.isString) }.double
            require(isToken(access) && expiresIn > 0)
            GuestToken(access, now().plusMillis((expiresIn * 1000).toLong()))
        } catch (_: Exception) {
            throw MapRouletteException(ErrorKind.PROTOCOL, response.status)
        }
    }

    // Clients

    /** A client bound to the current generation: after a sign-out or account switch its requests fail
     * with [CancellationException] before sending. A 401 triggers one refresh for the next request
     * (never a resend). Wrap [transport] to add your own checks. */
    fun client(
        environment: MapRouletteEnvironment? = null,
        transport: Transport? = null,
        allowElementDeletion: Boolean = false,
    ): MapRouletteClient {
        val expected = generation
        val bound = BoundTransport(this, expected, transport ?: this.transport)
        return MapRouletteClient(
            environment ?: configuration.environment, bound, allowElementDeletion,
            accessToken = { accessToken(expected) },
        )
    }

    /** Binds requests to one sign-in generation and renews the token after a 401. */
    private class BoundTransport(val signIn: MobileSignIn, val generation: Int, val inner: Transport) : Transport {
        override suspend fun execute(request: HttpRequest): HttpResponse {
            signIn.checkGeneration(generation)
            val response = inner.execute(request)
            signIn.checkGeneration(generation)
            // osm_reauth_required concerns the backend's OSM token, not this grant: no refresh.
            val header = request.headers["Authorization"]
            if (response.status == 401 && !isOsmReauth(response.body) && header != null) {
                signIn.renewAfterRejection(generation, header)
            }
            return response
        }
    }

    // Helpers

    /** Form POST to an OAuth endpoint at the configured origin. */
    private suspend fun post(url: String, form: Map<String, String>): HttpResponse {
        val body = formEncode(form.toSortedMap())
        return try {
            transport.execute(
                HttpRequest(
                    url,
                    mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                    HttpMethod.POST, body,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MapRouletteException) {
            throw e
        } catch (_: Exception) {
            throw MapRouletteException(ErrorKind.NETWORK)
        }
    }

    // Runs a store operation whose failure the caller tolerates; reports whether it succeeded.
    private suspend fun quietly(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val GUEST_GRANT = "urn:maproulette:grant-type:guest"
        private const val GUEST_CLAIM_GRANT = "urn:maproulette:grant-type:guest_claim"

        /** A new grant: `tasks:read` plus a subset of what was requested; `osm:tagfix` only with
         * `tasks:write`. */
        fun acceptableGrant(granted: Set<String>, requested: Set<String>): Boolean =
            MobileSignInConfiguration.READ_SCOPE in granted && requested.containsAll(granted) &&
                (MobileSignInConfiguration.TAGFIX_SCOPE !in granted || MobileSignInConfiguration.WRITE_SCOPE in granted)

        /** The S256 code challenge (RFC 7636) for [verifier]. */
        fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))

        internal fun randomToken(): String = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

        private fun isToken(value: String) = value.matches(Regex("[A-Za-z0-9_-]{20,200}"))

        internal fun isOsmReauth(body: String): Boolean = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content == "osm_reauth_required"
        }.getOrDefault(false)

        /** The OAuth `error` code, only when it is a plain code. */
        private fun oauthError(body: String): String? = runCatching {
            (Json.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()?.takeIf { plainCode.matches(it) }

        private fun oauthFailure(response: HttpResponse): MapRouletteException {
            val kind = when (response.status) {
                400, 401 -> ErrorKind.AUTHENTICATION
                403 -> ErrorKind.PERMISSION
                404 -> ErrorKind.NOT_FOUND
                409 -> ErrorKind.CONFLICT
                429 -> ErrorKind.RATE_LIMIT
                in 500..599 -> ErrorKind.SERVER
                else -> ErrorKind.HTTP
            }
            val retryAfter = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value
            return MapRouletteException(kind, response.status, retryAfter, reason = oauthError(response.body))
        }

        // Unreserved characters stay; everything else is percent-encoded as UTF-8.
        private fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                if (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' || c.toChar() in "-._~") {
                    append(c.toChar())
                } else {
                    append('%').append("%02X".format(c))
                }
            }
        }

        private fun formEncode(form: Map<String, String>) =
            form.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

        private fun formDecode(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }
            .associate {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString)
    it.content
}

private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeUnless { it == JsonNull }?.jsonObject
