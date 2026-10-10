package org.maproulette.sdk

import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class GuestSignInTest {
    private val guestId = "6f1c2a9e-0000-4000-8000-000000000001"
    private val secret = "s".repeat(43)

    private fun oauthError(code: String, status: Int = 400) =
        HttpResponse(status, body = json("error" to code, "error_description" to "server text"))
    private val registered = HttpResponse(201, body = json("guestId" to guestId, "guestSecret" to secret, "expiresAt" to "2026-11-09T18:00:00Z"))
    private fun guestToken(access: String, expiresIn: Int = 900) =
        HttpResponse(200, body = json("token_type" to "Bearer", "access_token" to access, "expires_in" to expiresIn, "scope" to "guest"))
    private fun grant(access: String, refresh: String) = HttpResponse(
        200,
        body = json(
            "token_type" to "Bearer", "access_token" to access, "refresh_token" to refresh, "expires_in" to 3600,
            "scope" to ALL_SCOPES.sorted().joinToString(" "),
        ),
    )
    private val me = HttpResponse(
        200, body = json("id" to 900, "osmId" to 12345, "displayName" to "Example", "scope" to ALL_SCOPES.sorted().joinToString(" ")),
    )

    private inner class Backend(guests: List<HttpResponse> = listOf(registered), tokens: List<HttpResponse> = emptyList()) : Transport {
        val guests = ArrayDeque(guests)
        val tokens = ArrayDeque(tokens)
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            yield()
            return when (URI(request.url).path) {
                "/oauth/mobile/guest" -> guests.removeFirst()
                "/oauth/mobile/token" -> tokens.removeFirstOrNull() ?: oauthError("invalid_grant")
                "/oauth/mobile/me" -> me
                "/oauth/mobile/revoke" -> HttpResponse(200)
                else -> HttpResponse(404)
            }
        }
        fun forms(path: String) = requests.filter { URI(it.url).path == path }.map(::formOf)
    }

    private fun session(backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore(), clock: Clock = Clock()) =
        MobileSignIn(configuration(), store, backend, now = { clock.now })

    /** Approves the browser step with a code, echoing state. */
    private fun approve(url: String) = "$REDIRECT?state=${url.toHttpUrl().queryParameter("state")}&code=the-code"

    @Test fun guestRegistersStoresAndRestores() = runBlocking<Unit> {
        val backend = Backend(tokens = listOf(guestToken("guest-access-token-0001")))
        val store = InMemoryCredentialStore()
        val s = session(backend, store)
        val guest = s.startGuest()
        assertEquals(guestId, guest.guestId)
        assertEquals(guest, s.startGuest())  // Idempotent: no second registration.
        assertEquals(listOf(mapOf("client_id" to "test-client")), backend.forms("/oauth/mobile/guest"))
        assertNull(s.account)

        val restored = session(Backend(), store)
        val again = assertIs<RestoreResult.Guest>(restored.restore()).guest
        assertEquals(guest.guestId, again.guestId)
        assertFalse(store.read().isNullOrEmpty())
    }

    @Test fun guestTokenIsFetchedCachedAndRenewed() = runBlocking<Unit> {
        val backend = Backend(tokens = listOf(guestToken("guest-access-token-0001"), guestToken("guest-access-token-0002")))
        val clock = Clock()
        val s = session(backend, clock = clock)
        val guest = s.startGuest()
        assertEquals("guest-access-token-0001", s.accessToken(guest.generation))
        assertEquals("guest-access-token-0001", s.accessToken(guest.generation))
        clock.now = clock.now.plusSeconds(850)  // Within a minute of expiry.
        assertEquals("guest-access-token-0002", s.accessToken(guest.generation))
        val forms = backend.forms("/oauth/mobile/token")
        assertEquals(2, forms.size)
        assertEquals(
            mapOf(
                "grant_type" to "urn:maproulette:grant-type:guest", "client_id" to "test-client",
                "guest_id" to guest.guestId, "guest_secret" to secret,
            ),
            forms[0],
        )
        // A client from the session sends the guest token.
        val client = s.client()
        runCatching { client.getGuestStatus() }
        val sent = backend.requests.last { URI(it.url).path.endsWith("mobile-guest/me") }
        assertEquals("Bearer guest-access-token-0002", sent.headers["Authorization"])
    }

    @Test fun claimedGuestReportsConflictThenUpgrades() = runBlocking<Unit> {
        val backend = Backend(tokens = listOf(oauthError("guest_claimed"), oauthError("claim_pending"), grant("access-1", "refresh-1")))
        val store = InMemoryCredentialStore()
        val s = session(backend, store)
        val guest = s.startGuest()
        val error = assertFailsWith<MapRouletteException> { s.accessToken(guest.generation) }
        assertEquals(ErrorKind.CONFLICT, error.kind); assertEquals("guest_claimed", error.reason)
        assertEquals(GuestUpgrade.NotYet, s.upgradeGuest())
        val account = assertIs<GuestUpgrade.SignedIn>(s.upgradeGuest()).account
        assertEquals(900, account.userId); assertTrue(account.canEditOsm)
        assertNull(s.guest)
        val forms = backend.forms("/oauth/mobile/token")
        assertEquals("urn:maproulette:grant-type:guest_claim", forms.last()["grant_type"])
        assertEquals(secret, forms.last()["guest_secret"])
        // The stored state is now an ordinary sign-in without the guest.
        val reopened = session(Backend(), store)
        assertIs<RestoreResult.SignedIn>(reopened.restore())
        assertNull(reopened.guest)
        assertFailsWith<IllegalStateException> { s.upgradeGuest() }
        assertFailsWith<IllegalStateException> { s.startGuest() }
    }

    @Test fun signInAndSignOutKeepTheGuestUntilForgotten() = runBlocking<Unit> {
        val backend = Backend(tokens = listOf(grant("access-1", "refresh-1")))
        val store = InMemoryCredentialStore()
        val s = session(backend, store)
        val guest = s.startGuest()
        val account = s.signIn(ALL_SCOPES) { approve(it) }
        assertEquals(guest.guestId, s.guest?.guestId)
        assertEquals("access-1", s.accessToken(account.generation))
        s.signOut()
        assertNull(s.account)
        assertEquals(guest.guestId, s.guest?.guestId)
        val reopened = session(Backend(), store)
        assertIs<RestoreResult.Guest>(reopened.restore())
        s.forgetGuest()
        assertNull(s.guest)
        assertNull(store.read())
        assertNull(s.accessToken(s.generation))
    }

    @Test fun guestSurvivesAnInterruptedRefresh() = runBlocking<Unit> {
        val clock = Clock()
        val store = InMemoryCredentialStore()
        val s = session(Backend(tokens = listOf(grant("access-1", "refresh-1"))), store, clock)
        s.startGuest()
        val account = s.signIn(ALL_SCOPES) { approve(it) }
        // The app dies while the refresh is in flight: keep what the store held at that moment.
        var atDeath: String? = null
        val dying = MobileSignIn(configuration(), store, Transport {
            atDeath = store.read()
            throw IllegalStateException("app died")
        }, now = { clock.now })
        assertIs<RestoreResult.SignedIn>(dying.restore())
        clock.now = clock.now.plusSeconds(3600)
        assertFailsWith<MapRouletteException> { dying.accessToken(dying.generation) }
        val next = session(Backend(), InMemoryCredentialStore(atDeath))
        assertEquals(guestId, assertIs<RestoreResult.Guest>(next.restore()).guest.guestId)
        assertNull(next.account)
        assertEquals(900, account.userId)
    }

    @Test fun unusableGuestCredentialIsForgotten() = runBlocking<Unit> {
        val s = session(Backend(tokens = listOf(oauthError("invalid_grant"))))
        val guest = s.startGuest()
        val error = assertFailsWith<MapRouletteException> { s.accessToken(guest.generation) }
        assertEquals(ErrorKind.AUTHENTICATION, error.kind); assertEquals("invalid_grant", error.reason)
        assertNull(s.guest)
    }

    @Test fun registrationFailuresMapToErrorKinds() = runBlocking<Unit> {
        val limited = HttpResponse(429, mapOf("Retry-After" to "120"), json("error" to "rate_limited"))
        val s = session(Backend(guests = listOf(limited, HttpResponse(404), oauthError("invalid_client", status = 401))))
        for ((kind, retry) in listOf(ErrorKind.RATE_LIMIT to "120", ErrorKind.NOT_FOUND to null, ErrorKind.AUTHENTICATION to null)) {
            val error = assertFailsWith<MapRouletteException> { s.startGuest() }
            assertEquals(kind, error.kind); assertEquals(retry, error.retryAfter)
            assertFalse(error.message!!.contains("server text"))
        }
        assertNull(s.guest)
    }

    @Test fun malformedRegistrationIsAProtocolError() = runBlocking<Unit> {
        val short = HttpResponse(201, body = json("guestId" to guestId, "guestSecret" to "short"))
        val s = session(Backend(guests = listOf(short)))
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { s.startGuest() }.kind)
        assertNull(s.guest)
    }
}
