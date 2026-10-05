package org.maproulette.example.auth

import org.junit.Assert.*
import org.junit.Test

class AuthEndpointsTest {
    @Test fun allEndpointsAndStorageBelongToConfiguredOriginAndClient() {
        val endpoint = AuthEndpoints("https://staging.example:8443/", "approved-app", false)
        assertEquals("https://staging.example:8443/api/v2/", endpoint.api)
        assertEquals("https://staging.example:8443/oauth/mobile/token", endpoint.token)
        assertTrue(endpoint.permitsConnection(endpoint.token))
        assertFalse(endpoint.permitsConnection("https://evil.example/oauth/mobile/token"))
        assertFalse(endpoint.permitsConnection("https://staging.example/oauth/mobile/token"))
        assertNotEquals(endpoint.storageBinding, AuthEndpoints("https://staging.example:8443", "other", false).storageBinding)
    }

    @Test fun plaintextRequiresExplicitLoopbackAndRejectsLookalikes() {
        assertTrue(AuthEndpoints("http://127.0.0.1:9000", "local", true).enabled)
        assertTrue(AuthEndpoints("http://localhost:9000", "local", true).enabled)
        listOf("http://127.0.0.1:9000", "http://localhost:9000").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuthEndpoints(it, "local", false) }
        }
        listOf("http://localhost.evil:9000", "http://10.0.2.2:9000", "http://192.168.1.1:9000").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuthEndpoints(it, "local", true) }
        }
    }

    @Test fun originCannotCarryCredentialsPathOrQuery() {
        listOf("https://user:secret@example.org", "https://example.org/base", "https://example.org/?secret=x", "https://example.org/#x")
            .forEach { assertThrows(IllegalArgumentException::class.java) { AuthEndpoints(it, "app", false) } }
        assertFalse(AuthEndpoints("https://maproulette.org", "", false).enabled)
    }

    @Test fun browserCallbackMatchesExactSchemeAndPathWithoutFragment() {
        val endpoint = AuthEndpoints("https://example.org", "app", false)
        assertTrue(endpoint.acceptsCallback("org.maproulette.example:/oauth2redirect?code=example&state=random"))
        assertFalse(endpoint.acceptsCallback("org.maproulette.example:/wrong?code=example"))
        assertFalse(endpoint.acceptsCallback("org.maproulette.example://evil/oauth2redirect?code=example"))
        assertFalse(endpoint.acceptsCallback("org.maproulette.example:/oauth2redirect#code=example"))
    }
}
