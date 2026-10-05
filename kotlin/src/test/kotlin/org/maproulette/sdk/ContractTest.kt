package org.maproulette.sdk

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class ContractTest {
    private val fixtures = Json.parseToJsonElement(File(System.getProperty("fixtures"), "contract.json").readText()).jsonObject
    private fun body(name: String) = fixtures.getValue(name).toString()
    private class Fake(var response: HttpResponse) : Transport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return response }
    }
    private fun fake(name: String) = Fake(HttpResponse(200, body = body(name)))

    @Test fun challengeShapesOptionalFieldsAndTags() = runBlocking<Unit> {
        val wire = fake("challenge_direct"); val client = MapRouletteClient(transport = wire)
        val direct = client.getChallenge(ChallengeId(42))
        assertEquals(ProjectId(7), direct.projectId); assertNull(direct.description); assertNull(direct.tags)
        wire.response = HttpResponse(200, body = body("challenges_search"))
        val results = client.searchChallenges(pageSize = 2)
        assertEquals(direct, results.items.first()); assertNull(results.items[1].enabled)
        assertEquals(listOf("mobile", "cycling"), results.items[1].tags)
        wire.response = HttpResponse(200, body = body("tags"))
        assertEquals(listOf(ChallengeTag(8, "cycling")), client.getChallengeTags(ChallengeId(42)))
    }

    @Test fun challengeOffsetsEncodingAndContinuationBinding() = runBlocking<Unit> {
        val wire = fake("challenges_search"); val client = MapRouletteClient(transport = wire)
        val filter = ChallengeFilter(tags = listOf("bike & repair", "café"), text = "backrest?")
        val first = client.searchChallenges(filter, pageSize = 2)
        client.searchChallenges(filter, pageSize = 2, after = first.next)
        val url = wire.requests.last().url.toHttpUrl()
        assertEquals("2", url.queryParameter("page")); assertEquals("bike & repair,café", url.queryParameter("ct"))
        assertEquals("backrest?", url.queryParameter("cs")); assertEquals("1", url.queryParameter("cLocal"))
        assertEquals("false", url.queryParameter("ca")); assertEquals("true", url.queryParameter("ce"))
        assertFailsWith<IllegalArgumentException> { client.searchChallenges(filter, pageSize = 1, after = first.next) }
        assertFailsWith<IllegalArgumentException> { MapRouletteClient(transport = wire).searchChallenges(filter, 2, first.next) }
        wire.response = HttpResponse(200, body = "[]")
        assertNull(client.searchChallenges(filter, 2, first.next).next)
    }

    @Test fun taskGeometryUnknownStatusAndPageNumber() = runBlocking<Unit> {
        val wire = fake("task"); val client = MapRouletteClient(transport = wire)
        val task = client.getTask(TaskId(101))
        assertEquals(73, task.status?.code); assertNull(task.status?.knownName)
        assertEquals(fixtures.getValue("task").jsonObject["geometries"], task.geometry)
        assertEquals(fixtures.getValue("task").jsonObject["cooperativeWork"], task.cooperativeWork)
        assertEquals("", task.instruction)
        wire.response = HttpResponse(200, body = body("tasks"))
        val first = client.listTasks(ChallengeId(42), 2)
        assertEquals("Fixed", first.items[0].status?.knownName); assertNull(first.items[1].status)
        client.listTasks(ChallengeId(42), 2, first.next)
        assertEquals("1", wire.requests.last().url.toHttpUrl().queryParameter("page"))
    }

    @Test fun spatialEnvelopeFiltersAndChallengeIsolation() = runBlocking<Unit> {
        val wire = fake("task_summaries"); val client = MapRouletteClient(transport = wire)
        val filter = TaskFilter(listOf(ChallengeId(42)), Bounds(4.0, 52.0, 5.0, 53.0), statuses = null)
        val first = client.findTasksInBounds(filter, 2)
        assertEquals(12L, first.total); assertNull(first.items[1].status); assertNull(first.items[1].point)
        val point = assertNotNull(first.items[0].point)
        assertEquals(fixtures.getValue("task_summaries").jsonObject.getValue("tasks").jsonArray[0].jsonObject["point"], point)
        assertEquals(52.3, point.getValue("lat").jsonPrimitive.double)
        assertEquals(4.9, point.getValue("lng").jsonPrimitive.double)
        assertFalse("coordinates" in point)
        client.findTasksInBounds(filter, 2, first.next)
        val url = wire.requests.last().url.toHttpUrl()
        assertEquals("42", url.queryParameter("cid")); assertNull(url.queryParameter("cId"))
        assertEquals("-1", url.queryParameter("tStatus")); assertEquals("1", url.queryParameter("page"))
        val error = assertFailsWith<MapRouletteException> { client.findTasksInBounds(filter.copy(challengeIds = listOf(ChallengeId(99))), 2) }
        assertEquals(ErrorKind.PROTOCOL, error.kind)
    }

    @Test fun spatialSearchAcrossAllChallengesOmitsChallengeFilter() = runBlocking<Unit> {
        val wire = fake("task_summaries_multiple_challenges")
        val client = MapRouletteClient(transport = wire)
        val filter = TaskFilter(bounds = Bounds(4.0, 52.0, 5.0, 53.0))
        val first = client.findTasksInBounds(filter, 2)
        assertEquals(listOf(ChallengeId(42), ChallengeId(99)), first.items.map { it.challengeId })
        assertEquals(2L, first.total)
        client.findTasksInBounds(filter, 2, first.next)
        val url = wire.requests.last().url.toHttpUrl()
        assertFalse("cid" in url.queryParameterNames)
        assertEquals("0,3,6", url.queryParameter("tStatus"))
        assertEquals("false", url.queryParameter("ca"))
        assertEquals("1", url.queryParameter("cLocal"))
        assertEquals("1", url.queryParameter("page"))
        assertFailsWith<IllegalArgumentException> {
            client.findTasksInBounds(filter.copy(challengeIds = listOf(ChallengeId(42))), 2, first.next)
        }
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> {
            client.findTasksInBounds(filter.copy(challengeIds = listOf(ChallengeId(42))), 2)
        }.kind)
    }

    @Test fun boundedMarkersUseReadOnlyPutWithoutPagination() = runBlocking<Unit> {
        val wire = fake("markers"); val client = MapRouletteClient(transport = wire)
        val filter = TaskFilter(bounds = Bounds(4.0, 52.0, 5.0, 53.0))
        assertEquals(listOf(42L, 99L), client.findTaskMarkers(filter, 2).map { it.challengeId.value })
        val request = wire.requests.last(); val url = request.url.toHttpUrl()
        assertEquals(HttpMethod.PUT, request.method); assertEquals("{}", request.body)
        assertEquals("application/json", request.headers["Content-Type"])
        assertTrue(url.encodedPath.contains("/markers/box/"))
        for (absent in listOf("cid", "sort", "page", "includeTotal")) assertNull(url.queryParameter(absent))
        for (enabled in listOf("ce", "pe", "excludeLocked")) assertEquals("true", url.queryParameter(enabled))
        assertEquals("1", url.queryParameter("cLocal")); assertEquals("0,3,6", url.queryParameter("tStatus"))
        val selected = filter.copy(challengeIds = listOf(ChallengeId(42), ChallengeId(99)), statuses = null)
        client.findTaskMarkers(selected)
        val selectedUrl = wire.requests.last().url.toHttpUrl()
        assertEquals("42,99", selectedUrl.queryParameter("cid")); assertEquals("-1", selectedUrl.queryParameter("tStatus"))
        assertNull(selectedUrl.queryParameter("ce")); assertNull(selectedUrl.queryParameter("pe"))
        for (invalid in listOf(0, 1001)) assertFailsWith<IllegalArgumentException> { client.findTaskMarkers(filter, invalid) }
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.findTaskMarkers(filter, 1) }.kind)
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> {
            client.findTaskMarkers(filter.copy(challengeIds = listOf(ChallengeId(42))))
        }.kind)
    }

    @Test fun markerPutReachesRealTransportWithJsonBody() = runBlocking<Unit> {
        MockWebServer().use { server -> OkHttpTransport().use { transport ->
            server.start(); server.enqueue(MockResponse().setBody(body("markers")))
            val client = MapRouletteClient(serviceUrl = server.url("/api/v2/").toString(), transport = transport)
            client.findTaskMarkers(TaskFilter(bounds = Bounds(4.0, 52.0, 5.0, 53.0)))
            val received = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PUT", received.method); assertEquals("{}", received.body.readUtf8())
            assertEquals("application/json", received.getHeader("Content-Type"))
        } }
    }

    @Test fun errorsAndCredentialRedaction() = runBlocking<Unit> {
        val wire = fake("identity"); val client = MapRouletteClient(transport = wire, apiKey = { "fixture-secret-do-not-expose" })
        val user = client.getCurrentUser()
        assertEquals(UserIdentity(900, false), user)
        assertFalse(user.toString().contains("secret")); assertFalse(wire.requests.last().toString().contains("secret"))
        assertEquals("fixture-secret-do-not-expose", wire.requests.last().headers["apiKey"])
        for ((status, kind) in listOf(401 to ErrorKind.AUTHENTICATION, 403 to ErrorKind.PERMISSION, 404 to ErrorKind.NOT_FOUND, 409 to ErrorKind.CONFLICT, 429 to ErrorKind.RATE_LIMIT, 503 to ErrorKind.SERVER, 302 to ErrorKind.HTTP)) {
            wire.response = HttpResponse(status, mapOf("retry-after" to "17"), if (status == 404) "" else "fixture-secret-do-not-expose")
            val error = assertFailsWith<MapRouletteException> { client.getTask(TaskId(101)) }
            assertEquals(kind, error.kind); assertEquals(status, error.status); assertEquals("17", error.retryAfter)
            assertFalse(error.toString().contains("secret"))
        }
        for (invalid in listOf("not json", body("malformed_challenge"))) {
            wire.response = HttpResponse(200, body = invalid)
            assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.getChallenge(ChallengeId(42)) }.kind)
        }
    }

    @Test fun credentialsBelongToEachUserAndAnonymousRequestsStayAnonymous() = runBlocking<Unit> {
        val wire = fake("challenge_direct")
        var firstKey: String? = "synthetic-first-user-key"
        val first = MapRouletteClient(transport = wire, apiKey = { firstKey })
        val second = MapRouletteClient(transport = wire, apiKey = { "synthetic-second-user-key" })
        val anonymous = MapRouletteClient(transport = wire)
        first.getChallenge(ChallengeId(42))
        second.getChallenge(ChallengeId(42))
        anonymous.getChallenge(ChallengeId(42))
        firstKey = "synthetic-rotated-first-user-key"
        first.getChallenge(ChallengeId(42))
        firstKey = null
        first.getChallenge(ChallengeId(42))
        second.getChallenge(ChallengeId(42))
        assertEquals(listOf("synthetic-first-user-key", "synthetic-second-user-key", null,
            "synthetic-rotated-first-user-key", null, "synthetic-second-user-key"),
            wire.requests.map { it.headers["apiKey"] })
        assertFalse(wire.requests[2].headers.containsKey("apiKey"))
        assertFalse(wire.requests[4].headers.containsKey("apiKey"))
    }

    @Test fun cancellationIsNotNetworkFailure() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val client = MapRouletteClient(transport = Transport {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        })
        val request = async { client.getTask(TaskId(101)) }
        started.await(); request.cancel()
        assertFailsWith<CancellationException> { request.await() }
        withTimeout(2000) { stopped.await() }
    }

    @Test fun realTransportAllowsResponseBeyondOkHttpDefaultReadTimeout() = runBlocking<Unit> {
        MockWebServer().use { server -> OkHttpTransport().use { transport ->
            server.start()
            // Reproduces slow spatial-query headers exceeding OkHttp's default ten seconds.
            server.enqueue(MockResponse().setHeadersDelay(11, TimeUnit.SECONDS).setBody("[]"))
            val response = transport.execute(HttpRequest(server.url("/slow-headers").toString(), emptyMap()))
            assertEquals(200, response.status)
            assertEquals("[]", response.body)
        } }
    }

    @Test fun realTransportDoesNotFollowRedirectAndCancels() = runBlocking<Unit> {
        MockWebServer().use { source -> MockWebServer().use { destination -> OkHttpTransport().use { transport ->
            source.start(); destination.start()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/secret")))
            val response = transport.execute(HttpRequest(source.url("/redirect").toString(), mapOf("apiKey" to "secret")))
            assertEquals(302, response.status); assertNull(destination.takeRequest(150, TimeUnit.MILLISECONDS))
            source.takeRequest(2, TimeUnit.SECONDS)
            source.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async { transport.execute(HttpRequest(source.url("/slow").toString(), emptyMap())) }
            withContext(Dispatchers.IO) { assertNotNull(source.takeRequest(2, TimeUnit.SECONDS)) }
            request.cancel()
            withTimeout(2000) { assertFailsWith<CancellationException> { request.await() } }
        } } }
    }
    @Test fun bearerIdentityUsesOneCredentialSnapshotAndOriginRoot() = runBlocking<Unit> {
        val wire = fake("identity_mobile")
        var keyCalls = 0; var tokenCalls = 0
        val client = MapRouletteClient(serviceUrl = "https://example.invalid:9443/prefix/api/v2/",
            transport = wire, apiKey = { keyCalls++; null }, accessToken = { tokenCalls++; "synthetic-access-token" })
        assertEquals(UserIdentity(900, false), client.getCurrentUser())
        assertEquals(1, keyCalls); assertEquals(1, tokenCalls)
        val request = wire.requests.single()
        assertEquals("https://example.invalid:9443/oauth/mobile/me", request.url)
        assertEquals("Bearer synthetic-access-token", request.headers["Authorization"])
        assertFalse(request.headers.containsKey("apiKey"))
        assertFalse(request.toString().contains("synthetic-access-token"))
        assertEquals(HttpMethod.GET, request.method); assertNull(request.body)
    }

    @Test fun credentialsCanSwitchPerUserWithoutSharingStateOrRetryFallback() = runBlocking<Unit> {
        val wire = fake("identity_mobile")
        var key: String? = null; var token: String? = "first-user-token"
        val client = MapRouletteClient(transport = wire, apiKey = { key }, accessToken = { token })
        val second = MapRouletteClient(transport = wire, accessToken = { "second-user-token" })
        client.getCurrentUser(); second.getCurrentUser()
        assertEquals(listOf("Bearer first-user-token", "Bearer second-user-token"), wire.requests.map { it.headers["Authorization"] })
        token = null; key = "legacy-user-key"
        wire.response = HttpResponse(200, body = body("identity"))
        client.getCurrentUser()
        assertTrue(wire.requests.last().url.endsWith("/api/v2/user/whoami"))
        assertEquals("legacy-user-key", wire.requests.last().headers["apiKey"])
        assertNull(wire.requests.last().headers["Authorization"])
        key = null
        client.getCurrentUser()
        assertTrue(wire.requests.last().url.endsWith("/api/v2/user/whoami"))
        assertNull(wire.requests.last().headers["apiKey"]); assertNull(wire.requests.last().headers["Authorization"])
        token = "expired-bearer"
        for (status in listOf(401, 403)) {
            wire.response = HttpResponse(status)
            val before = wire.requests.size
            assertFailsWith<MapRouletteException> { client.getCurrentUser() }
            assertEquals(before + 1, wire.requests.size)
            assertTrue(wire.requests.last().url.endsWith("/oauth/mobile/me"))
        }
    }

    @Test fun conflictingOrMalformedCredentialsFailBeforeHttpAndLegacyLambdasKeepMeaning() = runBlocking<Unit> {
        val wire = fake("challenge_direct")
        val conflict = MapRouletteClient(transport = wire, apiKey = { "legacy" }, accessToken = { "bearer" })
        assertFailsWith<IllegalArgumentException> { conflict.getCurrentUser() }
        assertTrue(wire.requests.isEmpty())
        for (invalid in listOf("", " ", "bad token", "token\r\n", "token?")) {
            val client = MapRouletteClient(transport = wire, accessToken = { invalid })
            assertFailsWith<IllegalArgumentException> { client.getChallenge(ChallengeId(42)) }
        }
        assertTrue(wire.requests.isEmpty())
        MapRouletteClient(transport = wire) { "trailing-legacy-key" }.getChallenge(ChallengeId(42))
        assertEquals("trailing-legacy-key", wire.requests.last().headers["apiKey"])
        assertNull(wire.requests.last().headers["Authorization"])
        MapRouletteClient("https://maproulette.org/api/v2/", wire, { "positional-legacy-key" }).getChallenge(ChallengeId(42))
        assertEquals("positional-legacy-key", wire.requests.last().headers["apiKey"])
    }

    @Test fun bearerAndMeRouteReachWireAndRedirectsCannotLeakCredentials() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { destination -> OkHttpTransport().use { transport ->
            server.start(); destination.start()
            val client = MapRouletteClient(serviceUrl = server.url("/api/v2/").toString(), transport = transport,
                accessToken = { "wire-bearer" })
            server.enqueue(MockResponse().setBody(body("identity_mobile")))
            assertEquals(UserIdentity(900, false), client.getCurrentUser())
            val identity = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/oauth/mobile/me", identity.path)
            assertEquals("Bearer wire-bearer", identity.getHeader("Authorization"))
            assertNull(identity.getHeader("apiKey"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/capture")))
            assertEquals(ErrorKind.HTTP, assertFailsWith<MapRouletteException> { client.getCurrentUser() }.kind)
            assertNull(destination.takeRequest(150, TimeUnit.MILLISECONDS))
        } } }
    }

    @Test fun malformedIdentitiesAndCredentialValidation() = runBlocking<Unit> {
        val wire = fake("challenge_direct"); val client = MapRouletteClient(transport = wire)
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.getChallenge(ChallengeId(99)) }.kind)
        wire.response = HttpResponse(200, body = body("task"))
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.getTask(TaskId(99)) }.kind)
        wire.response = HttpResponse(200, body = body("tasks"))
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.listTasks(ChallengeId(99), 2) }.kind)
        wire.response = HttpResponse(200, body = """{"id":900,"guest":"false"}""")
        assertEquals(ErrorKind.PROTOCOL, assertFailsWith<MapRouletteException> { client.getCurrentUser() }.kind)
        val invalid = MapRouletteClient(transport = wire, apiKey = { "\nsecret" })
        assertFailsWith<IllegalArgumentException> { invalid.getTask(TaskId(101)) }
    }

}
