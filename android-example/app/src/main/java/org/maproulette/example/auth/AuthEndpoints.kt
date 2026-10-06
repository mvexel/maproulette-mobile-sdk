package org.maproulette.example.auth

import java.net.URI

/** One backend origin owns both credentials and SDK data; no discovery or redirect guessing. */
class AuthEndpoints(baseUrl: String, val clientId: String, allowLoopback: Boolean) {
    val loopbackAllowed = allowLoopback
    private val base = URI(baseUrl)
    val origin: String
    val enabled: Boolean get() = clientId.isNotBlank()
    val api: String get() = "$origin/api/v2/"
    val authorize: String get() = "$origin/oauth/mobile/authorize"
    val token: String get() = "$origin/oauth/mobile/token"
    val revoke: String get() = "$origin/oauth/mobile/revoke"
    val redirect = "org.maproulette.example:/oauth2redirect"
    val storageBinding: String get() = "$origin|$clientId"

    init {
        require(base.host != null && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
        require(base.rawPath.isNullOrEmpty() || base.rawPath == "/")
        val loopback = base.host == "127.0.0.1" || base.host == "localhost"
        require(base.scheme == "https" || (allowLoopback && loopback && base.scheme == "http"))
        require(clientId.isEmpty() || clientId.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        origin = "${base.scheme}://${base.rawAuthority}"
    }

    fun permitsConnection(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port &&
            uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)

    fun acceptsCallback(url: String): Boolean = runCatching {
        val actual = URI(url)
        val expected = URI(redirect)
        actual.scheme == expected.scheme && actual.rawAuthority == expected.rawAuthority &&
            actual.rawPath == expected.rawPath && actual.rawFragment == null
    }.getOrDefault(false)
}
