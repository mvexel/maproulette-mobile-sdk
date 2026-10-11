package org.maproulette.sdk

import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

/** Claiming a guest's answers from the phone after an OSM sign-in (API plan §3.7, §3.10). */
class ClaimTest {
    private val fixtures = Json.parseToJsonElement(File(System.getProperty("fixtures"), "guest.json").readText()).jsonObject
    private val guestId = "6f1c2a9e-0000-4000-8000-000000000001"
    private val secret = "s".repeat(43)

    private fun body(value: JsonElement) = if (value is JsonPrimitive && value.isString) value.content else value.toString()

    /** Registers a guest, signs in with OSM, then answers `mobile-claim` with [claims] in turn. */
    private inner class Backend(claims: List<HttpResponse>) : Transport {
        val claims = ArrayDeque(claims)
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return when (URI(request.url).path) {
                "/oauth/mobile/guest" -> HttpResponse(201, body = json("guestId" to guestId, "guestSecret" to secret))
                "/oauth/mobile/token" -> HttpResponse(
                    200,
                    body = json(
                        "token_type" to "Bearer", "access_token" to "account-access-1", "refresh_token" to "account-refresh-1",
                        "expires_in" to 3600, "scope" to ALL_SCOPES.sorted().joinToString(" "),
                    ),
                )
                "/oauth/mobile/me" -> HttpResponse(
                    200, body = json("id" to 900, "osmId" to 123, "displayName" to "rosa_slc", "scope" to ALL_SCOPES.sorted().joinToString(" ")),
                )
                "/api/v2/mobile-claim" -> claims.removeFirst()
                else -> HttpResponse(404)
            }
        }
        fun claimRequests() = requests.filter { URI(it.url).path == "/api/v2/mobile-claim" }
    }

    private suspend fun signedInGuest(backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore()): MobileSignIn {
        val s = MobileSignIn(configuration(), store, backend)
        s.startGuest()
        s.signIn(ALL_SCOPES) { "$REDIRECT?state=${it.toHttpUrl().queryParameter("state")}&code=the-code" }
        return s
    }

    @Test fun claimSendsTheGuestCredentialWithTheAccountAndForgetsTheGuest() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        val backend = Backend(listOf(HttpResponse(202, body = fixtures.getValue("claim_accepted").toString())))
        val s = signedInGuest(backend, store)
        val claim = s.claimGuest()
        assertEquals(GuestClaim(9, ClaimState.QUEUED, 14, ClaimedAccount("rosa_slc", 123)), claim)
        val sent = backend.claimRequests().single()
        assertEquals(HttpMethod.POST, sent.method)
        assertEquals("Bearer account-access-1", sent.headers["Authorization"])
        assertEquals(
            buildJsonObject { put("guestId", guestId); put("guestSecret", secret) },
            Json.parseToJsonElement(sent.body!!),
        )
        assertNull(s.guest)
        assertNotNull(s.account)
        // Stored without the guest: a relaunch is an ordinary sign-in.
        val reopened = MobileSignIn(configuration(), store, backend)
        assertIs<RestoreResult.SignedIn>(reopened.restore())
        assertNull(reopened.guest)
        assertFailsWith<IllegalStateException> { s.claimGuest() }
    }

    @Test fun aClaimMadeWhileWritesAreOffWaits() = runBlocking<Unit> {
        val backend = Backend(listOf(HttpResponse(202, body = fixtures.getValue("claim_waiting").toString())))
        assertEquals(ClaimState.WAITING, signedInGuest(backend).claimGuest().state)
    }

    @Test fun claimErrorsMapAndForgetOnlyASpentGuest() = runBlocking<Unit> {
        for (row in fixtures.getValue("claim_errors").jsonArray.map { it.jsonObject }) {
            val status = row.getValue("status").jsonPrimitive.int
            val backend = Backend(listOf(HttpResponse(status, body = body(row.getValue("body")))))
            val s = signedInGuest(backend)
            val error = assertFailsWith<MapRouletteException>(row.toString()) { s.claimGuest() }
            val kind = row.getValue("kind").jsonPrimitive.content
            assertEquals(kind.replace(Regex("([A-Z])"), "_$1").uppercase(), error.kind.name, row.toString())
            assertEquals(row["reason"]?.jsonPrimitive?.contentOrNull, error.reason, row.toString())
            assertEquals(row.getValue("forget").jsonPrimitive.boolean, s.guest == null, row.toString())
            assertNotNull(s.account, row.toString())
        }
    }

    @Test fun claimNeedsAnAccountAndAGuest() = runBlocking<Unit> {
        val guestOnly = MobileSignIn(configuration(), InMemoryCredentialStore(), Backend(emptyList()))
        guestOnly.startGuest()
        assertFailsWith<IllegalStateException> { guestOnly.claimGuest() }
        assertFailsWith<IllegalStateException> { MobileSignIn(configuration(), InMemoryCredentialStore(), Backend(emptyList())).claimGuest() }
    }

    @Test fun claimStatusDecodes() = runBlocking<Unit> {
        val requests = mutableListOf<HttpRequest>()
        val client = MapRouletteClient(
            MapRouletteEnvironment.STAGING,
            Transport { requests += it; HttpResponse(200, body = fixtures.getValue("claim_status").toString()) },
            accessToken = { "synthetic-account-token" },
        )
        val status = client.getClaimStatus(9)
        assertEquals(ClaimState.RUNNING, status.state)
        assertEquals(listOf(3, 1, 1, 0, 0), listOf(status.total, status.published, status.skipped, status.superseded, status.failed))
        assertEquals(ClaimItem(TaskId(101), PendingState.PUBLISHED, 1234567, listOf("bench")), status.items[0])
        assertEquals(ClaimItem(TaskId(102), PendingState.SKIPPED_STALE), status.items[1])
        assertEquals(PendingState.PENDING, status.items[2].state)
        assertEquals("/api/v2/mobile-claim/9", URI(requests.single().url).path)
        // Another claim's body, or an unknown state, is a protocol error.
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.getClaimStatus(10) }.kind)
        val unknown = JsonObject(fixtures.getValue("claim_status").jsonObject + ("state" to JsonPrimitive("paused")))
        val odd = MapRouletteClient(MapRouletteEnvironment.STAGING, Transport { HttpResponse(200, body = unknown.toString()) }, accessToken = { "synthetic-account-token" })
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { odd.getClaimStatus(9) }.kind)
        assertFailsWith<IllegalArgumentException> { client.getClaimStatus(0) }
    }
}
