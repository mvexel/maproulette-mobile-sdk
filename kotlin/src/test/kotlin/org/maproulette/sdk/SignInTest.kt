package org.maproulette.sdk

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

internal const val REDIRECT = "app.test:/oauth2redirect"
internal val ALL_SCOPES = MobileSignInConfiguration.scopes(writes = true)

internal fun json(vararg pairs: Pair<String, Any?>): String = JsonObject(pairs.associate { (k, v) ->
    k to when (v) {
        null -> JsonNull
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}).toString()

internal fun formOf(request: HttpRequest): Map<String, String> = (request.body ?: "").split('&').filter { it.isNotEmpty() }
    .associate { val kv = it.split('=', limit = 2); kv[0] to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8") }

internal fun configuration(clientId: String = "test-client") =
    MobileSignInConfiguration(MapRouletteEnvironment.STAGING, clientId, REDIRECT)

internal class Clock {
    var now: Instant = Instant.ofEpochSecond(1_800_000_000)
}

class SignInTest {
    private fun token(access: String, refresh: String, scope: Set<String> = ALL_SCOPES, expiresIn: Int = 3600) = HttpResponse(
        200,
        body = json(
            "token_type" to "Bearer", "access_token" to access, "refresh_token" to refresh, "expires_in" to expiresIn,
            "scope" to scope.sorted().joinToString(" "),
        ),
    )
    private fun me(scope: Set<String> = ALL_SCOPES) = HttpResponse(
        200, body = json("id" to 900, "osmId" to 12345, "displayName" to "mapper_demo", "scope" to scope.sorted().joinToString(" ")),
    )
    private val unauthorized = HttpResponse(401, body = json("message" to "Unauthorized"))

    /** The fork backend's OAuth routes, answered from queues. Records every request. */
    private inner class Backend(tokens: List<HttpResponse>, identities: List<HttpResponse> = listOf(me())) : Transport {
        val tokens = ArrayDeque(tokens)
        val identities = ArrayDeque(identities)
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            yield()  // Let concurrent callers interleave.
            return when (URI(request.url).path) {
                "/oauth/mobile/token" -> tokens.removeFirstOrNull() ?: HttpResponse(400, body = json("error" to "invalid_grant"))
                "/oauth/mobile/me" -> if (identities.size > 1) identities.removeFirst() else identities.first()
                "/oauth/mobile/revoke" -> HttpResponse(200)
                else -> HttpResponse(404)
            }
        }
        fun calls(path: String) = requests.filter { URI(it.url).path == path }
    }

    /** Plays the browser: approves with a code, or answers with [error], echoing state unless told otherwise. */
    private class Browser {
        var opened: String? = null
        var error: String? = null
        var state: String? = null
        fun callback(url: String): String {
            opened = url
            val echoed = state ?: url.toHttpUrl().queryParameter("state")!!
            return "$REDIRECT?state=$echoed&" + (error?.let { "error=$it" } ?: "code=the-code")
        }
    }

    private suspend fun signIn(
        backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore(), clock: Clock = Clock(),
        browser: Browser = Browser(),
    ): Pair<MobileSignIn, MobileAccount> {
        val session = MobileSignIn(configuration(), store, backend, now = { clock.now })
        return session to session.signIn(ALL_SCOPES) { browser.callback(it) }
    }

    private suspend fun failure(block: suspend () -> Unit): SignInFailure =
        assertFailsWith<SignInException> { block() }.failure

    /** base64url(SHA-256(verifier)) without padding (RFC 7636 S256); expected value computed independently. */
    @Test fun challengeIsUnpaddedBase64UrlOfSha256() {
        assertEquals(
            "nSDMx7jJh8uGbliKbSstDRcTcJmLbdPcZm3IpF_Q1T0",
            MobileSignIn.challenge("dBjftJeZ4CVP-mJ92K1QD8a7jj6c3D9vrxwYGrqXDW8"),
        )
    }

    @Test fun configurationValidatesAndUsesTheBackendOrigin() {
        assertFailsWith<IllegalArgumentException> { MobileSignInConfiguration(MapRouletteEnvironment.STAGING, "bad id", REDIRECT) }
        assertFailsWith<IllegalArgumentException> { MobileSignInConfiguration(MapRouletteEnvironment.STAGING, "c", "app.test:/cb#fragment") }
        assertFailsWith<IllegalArgumentException> { MobileSignInConfiguration(MapRouletteEnvironment.STAGING, "c", "app.test:/cb?x=1") }
        assertFailsWith<IllegalArgumentException> { MobileSignInConfiguration(MapRouletteEnvironment.STAGING, "c", "/no-scheme") }
        val c = configuration()
        assertEquals("app.test", c.callbackScheme)
        assertEquals("https://mr-api.osm.lol|test-client", c.storageBinding)
    }

    @Test fun signInUsesPkceAndStoresTheGrant() = runBlocking<Unit> {
        val backend = Backend(listOf(token("access-1", "refresh-1")))
        val browser = Browser()
        val store = InMemoryCredentialStore()
        val (session, account) = signIn(backend, store, browser = browser)
        assertEquals(900, account.userId); assertTrue(account.canWriteTasks && account.canEditOsm)
        assertEquals("mapper_demo", account.displayName)
        assertEquals(account, session.account)

        val opened = assertNotNull(browser.opened)
        assertTrue(opened.startsWith("https://mr-api.osm.lol/oauth/mobile/authorize?"))
        val url = opened.toHttpUrl()
        assertEquals("code", url.queryParameter("response_type")); assertEquals("test-client", url.queryParameter("client_id"))
        assertEquals(REDIRECT, url.queryParameter("redirect_uri")); assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("osm:tagfix tasks:read tasks:write", url.queryParameter("scope"))
        val form = formOf(backend.calls("/oauth/mobile/token").first())
        assertEquals("authorization_code", form["grant_type"]); assertEquals("the-code", form["code"])
        assertEquals(REDIRECT, form["redirect_uri"]); assertEquals("test-client", form["client_id"])
        assertEquals(url.queryParameter("code_challenge"), MobileSignIn.challenge(form.getValue("code_verifier")))
        assertEquals("application/x-www-form-urlencoded", backend.calls("/oauth/mobile/token").first().headers["Content-Type"])
        assertEquals("Bearer access-1", backend.calls("/oauth/mobile/me").first().headers["Authorization"])

        // A new instance (next launch) restores the account and its token.
        val next = MobileSignIn(configuration(), store, backend)
        val restored = assertIs<RestoreResult.SignedIn>(next.restore()).account
        assertEquals(900, restored.userId); assertEquals(ALL_SCOPES, restored.scopes)
        assertEquals("mapper_demo", restored.displayName)
        assertEquals("access-1", next.accessToken(restored.generation))
    }

    @Test fun accountDisplayNameIsOptional() = runBlocking<Unit> {
        // The identity route may omit the name: the account has none, and none is stored.
        val unnamed = HttpResponse(200, body = json("id" to 900, "osmId" to 12345, "scope" to ALL_SCOPES.sorted().joinToString(" ")))
        val store = InMemoryCredentialStore()
        val (_, account) = signIn(Backend(listOf(token("access-1", "refresh-1")), listOf(unnamed)), store)
        assertNull(account.displayName)
        assertFalse(Json.parseToJsonElement(assertNotNull(store.read())).jsonObject.containsKey("displayName"))
        // Storage written before display names existed still restores, without a name.
        val restored = assertIs<RestoreResult.SignedIn>(MobileSignIn(configuration(), store, Backend(emptyList())).restore()).account
        assertEquals(900, restored.userId); assertNull(restored.displayName)
    }

    @Test fun callbackMustMatchStateAndRedirect() = runBlocking<Unit> {
        val browser = Browser().apply { state = "forged" }
        val backend = Backend(listOf(token("a", "r")))
        assertEquals(SignInFailure.CallbackMismatch, failure { signIn(backend, browser = browser) })
        assertTrue(backend.calls("/oauth/mobile/token").isEmpty(), "no code is exchanged")
        val session = MobileSignIn(configuration(), InMemoryCredentialStore(), backend)
        assertEquals(SignInFailure.CallbackMismatch, failure {
            session.signIn(ALL_SCOPES) { "other.app:/oauth2redirect?state=${it.toHttpUrl().queryParameter("state")}&code=c" }
        })
    }

    @Test fun providerErrorsAreReported() = runBlocking<Unit> {
        val browser = Browser().apply { error = "access_denied" }
        assertEquals(SignInFailure.Denied, failure { signIn(Backend(emptyList()), browser = browser) })
        browser.error = "server_error"
        assertEquals(SignInFailure.ProviderError("server_error"), failure { signIn(Backend(emptyList()), browser = browser) })
    }

    @Test fun canceledBrowserSessionIsCanceled() = runBlocking<Unit> {
        val session = MobileSignIn(configuration(), InMemoryCredentialStore(), Backend(emptyList()))
        assertEquals(SignInFailure.Canceled, failure { session.signIn(ALL_SCOPES) { throw IllegalStateException("closed") } })
        assertNull(session.account)
    }

    @Test fun signOutDuringTheBrowserStepSupersedesTheSignIn() = runBlocking<Unit> {
        val backend = Backend(listOf(token("a", "r")))
        val session = MobileSignIn(configuration(), InMemoryCredentialStore(), backend)
        val browser = Browser()
        assertEquals(SignInFailure.Superseded, failure {
            session.signIn(ALL_SCOPES) { session.signOut(); browser.callback(it) }
        })
        assertTrue(backend.calls("/oauth/mobile/token").isEmpty())
    }

    @Test fun grantWiderThanRequestedIsRefused() = runBlocking<Unit> {
        val read = setOf(MobileSignInConfiguration.READ_SCOPE)
        assertTrue(MobileSignIn.acceptableGrant(read, requested = ALL_SCOPES))
        assertFalse(MobileSignIn.acceptableGrant(ALL_SCOPES, requested = read))
        assertFalse(MobileSignIn.acceptableGrant(setOf(MobileSignInConfiguration.READ_SCOPE, "osm:tagfix"), requested = ALL_SCOPES))
        val session = MobileSignIn(configuration(), InMemoryCredentialStore(), Backend(listOf(token("a", "r"))))
        val browser = Browser()
        assertEquals(SignInFailure.TokenExchange, failure { session.signIn(read) { browser.callback(it) } })
    }

    @Test fun identityMustMatchTheGrant() = runBlocking<Unit> {
        val backend = Backend(listOf(token("a", "r")), listOf(me(setOf(MobileSignInConfiguration.READ_SCOPE))))
        assertEquals(SignInFailure.AccountLookup, failure { signIn(backend) })
    }

    @Test fun expiredTokenRefreshesOnceForConcurrentCallers() = runBlocking<Unit> {
        val clock = Clock()
        val backend = Backend(listOf(token("access-1", "refresh-1"), token("access-2", "refresh-2")))
        val (session, account) = signIn(backend, clock = clock)
        clock.now = clock.now.plusSeconds(3600)
        val tokens = List(2) { async { session.accessToken(account.generation) } }.awaitAll()
        assertEquals(listOf("access-2", "access-2"), tokens)
        val refreshes = backend.calls("/oauth/mobile/token").drop(1)
        assertEquals(1, refreshes.size)
        val form = formOf(refreshes.first())
        assertEquals("refresh_token", form["grant_type"]); assertEquals("refresh-1", form["refresh_token"])
    }

    @Test fun failedRefreshSignsOut() = runBlocking<Unit> {
        val clock = Clock()
        val store = InMemoryCredentialStore()
        val (session, account) = signIn(Backend(listOf(token("a", "r"))), store, clock)
        clock.now = clock.now.plusSeconds(3600)
        val error = assertFailsWith<MapRouletteException> { session.accessToken(account.generation) }
        assertEquals(ErrorKind.AUTHENTICATION, error.kind)
        assertNull(session.account)
        assertNull(store.read())
    }

    @Test fun refreshMustKeepTheScope() = runBlocking<Unit> {
        val clock = Clock()
        val narrower = token("a2", "r2", scope = setOf(MobileSignInConfiguration.READ_SCOPE))
        val (session, account) = signIn(Backend(listOf(token("a", "r"), narrower)), clock = clock)
        clock.now = clock.now.plusSeconds(3600)
        assertFailsWith<MapRouletteException> { session.accessToken(account.generation) }
        assertNull(session.account)
    }

    @Test fun interruptedRefreshSignsOutOnRestore() = runBlocking<Unit> {
        val c = configuration()
        val store = InMemoryCredentialStore(json("binding" to c.storageBinding, "userID" to 900, "refreshPending" to true))
        val session = MobileSignIn(c, store, Backend(emptyList()))
        assertEquals(RestoreResult.RefreshInterrupted, session.restore())
        assertNull(store.read())
    }

    @Test fun refreshIsMarkedPendingBeforeTheTokenLeaves() = runBlocking<Unit> {
        val clock = Clock()
        val store = InMemoryCredentialStore()
        val backend = Backend(listOf(token("a", "refresh-1"), token("a2", "refresh-2")))
        signIn(backend, store, clock)
        var atSend: String? = null
        val observed = MobileSignIn(configuration(), store, Transport { request ->
            if (formOf(request)["grant_type"] == "refresh_token") atSend = store.read()
            backend.execute(request)
        }, now = { clock.now })
        val account = assertIs<RestoreResult.SignedIn>(observed.restore()).account
        clock.now = clock.now.plusSeconds(3600)
        assertEquals("a2", observed.accessToken(account.generation))
        assertTrue(Json.parseToJsonElement(atSend!!).jsonObject.getValue("refreshPending").jsonPrimitive.boolean)
        assertFalse(Json.parseToJsonElement(store.read()!!).jsonObject.getValue("refreshPending").jsonPrimitive.boolean)
    }

    @Test fun storedGrantForAnotherClientIsNotUsed() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        signIn(Backend(listOf(token("a", "r"))), store)
        val session = MobileSignIn(configuration("other-client"), store, Backend(emptyList()))
        assertEquals(RestoreResult.Unreadable, session.restore())
        assertNull(session.account)
        assertNull(store.read())
        assertEquals(RestoreResult.Unreadable, MobileSignIn(configuration(), InMemoryCredentialStore("not json"), Backend(emptyList())).restore())
    }

    @Test fun signOutRevokesAndDisconnectsOldClients() = runBlocking<Unit> {
        val backend = Backend(listOf(token("a", "refresh-1")))
        val store = InMemoryCredentialStore()
        val (session, _) = signIn(backend, store)
        val client = session.client()
        client.getCurrentUser()
        val lookups = backend.calls("/oauth/mobile/me").size

        assertEquals(SignOutResult(localCleared = true, revocationConfirmed = true), session.signOut())
        assertEquals("refresh-1", formOf(backend.calls("/oauth/mobile/revoke").first())["token"])
        assertNull(store.read())
        assertNull(session.account)

        assertFailsWith<CancellationException> { client.getCurrentUser() }
        assertEquals(lookups, backend.calls("/oauth/mobile/me").size, "nothing sent for the old account")
    }

    @Test fun unauthorizedResponseRefreshesForTheNextRequest() = runBlocking<Unit> {
        val backend = Backend(
            listOf(token("access-1", "refresh-1"), token("access-2", "refresh-2")),
            listOf(me(), unauthorized, me()),
        )
        val (session, _) = signIn(backend)
        val client = session.client()
        assertFailsWith<MapRouletteException> { client.getCurrentUser() }
        assertEquals(2, backend.calls("/oauth/mobile/token").size, "one refresh after the 401")
        client.getCurrentUser()
        assertEquals("Bearer access-2", backend.calls("/oauth/mobile/me").last().headers["Authorization"])
    }

    @Test fun osmReauthDoesNotRefresh() = runBlocking<Unit> {
        val backend = Backend(
            listOf(token("access-1", "refresh-1")),
            listOf(me(), HttpResponse(401, body = json("error" to "osm_reauth_required")), me()),
        )
        val (session, _) = signIn(backend)
        assertFailsWith<MapRouletteException> { session.client().getCurrentUser() }
        assertEquals(1, backend.calls("/oauth/mobile/token").size)
    }

    @Test fun failuresAndStoredStateDoNotLeakCredentials() = runBlocking<Unit> {
        val failure = SignInException(SignInFailure.ProviderError("server_error"))
        assertFalse(failure.message!!.contains("server_error"))
        val (session, account) = signIn(Backend(listOf(token("secret-access-token", "secret-refresh-token"))))
        assertFalse(account.toString().contains("secret") || session.toString().contains("secret"))
    }
}
