package org.maproulette.sdk

import java.net.URI

/** The MapRoulette deployment a client talks to.
 *
 * Reads work against any environment. In SDK 0.x, task writes (lock, release, skip, status and
 * choice submission) go only to the disposable staging deployment, to a loopback server, or to a
 * custom environment created with `allowWrites = true`; see [allowsWrites]. Production
 * MapRoulette (`maproulette.org` and its subdomains) is always read-only.
 *
 * @param serviceUrl the API base, e.g. `https://maproulette.org/api/v2/`. It must be https (or http
 *   on a loopback host) with a host and no credentials, query or fragment.
 * @param allowWrites opt in to task writes for your own mobile-enabled backend. Passing `true` for
 *   a `maproulette.org` or `*.maproulette.org` host throws [IllegalArgumentException]. */
public class MapRouletteEnvironment @JvmOverloads constructor(
    serviceUrl: String,
    private val allowWrites: Boolean = false,
) {
    /** The API base, always ending in `/`. */
    public val serviceUrl: String = serviceUrl.let { if (it.endsWith("/")) it else "$it/" }

    init {
        val uri = runCatching { URI(this.serviceUrl) }.getOrNull()
        require(
            uri != null && !uri.host.isNullOrEmpty() && uri.port <= 65535 && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && isLoopback(uri.host))),
        ) { "serviceUrl must be https (or http on loopback) with a host and no credentials, query or fragment" }
        require(!allowWrites || !isProductionMapRoulette(uri.host)) {
            "allowWrites is not supported for production MapRoulette (maproulette.org): it has no mobile routes, " +
                "and this SDK never writes there"
        }
    }

    /** Whether this SDK version sends task writes here: to the staging origin
     * (`https://mr-api.osm.lol`), to loopback hosts (local servers and tests), and to a custom
     * environment created with `allowWrites = true`. Never to production MapRoulette. */
    public val allowsWrites: Boolean
        get() {
            val uri = URI(serviceUrl)
            if (isLoopback(uri.host)) return true
            if (allowWrites) return true
            return uri.scheme == "https" && uri.host.equals("mr-api.osm.lol", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443)
        }

    override fun equals(other: Any?): Boolean =
        other is MapRouletteEnvironment && other.serviceUrl == serviceUrl && other.allowWrites == allowWrites
    override fun hashCode(): Int = 31 * serviceUrl.hashCode() + allowWrites.hashCode()
    override fun toString(): String = serviceUrl

    public companion object {
        /** Public production MapRoulette. Reads only: this SDK version refuses writes here. */
        @JvmField
        public val PRODUCTION: MapRouletteEnvironment = MapRouletteEnvironment("https://maproulette.org/api/v2/")

        /** The disposable staging deployment (fork backend, dev OSM, separate database). Writes allowed. */
        @JvmField
        public val STAGING: MapRouletteEnvironment = MapRouletteEnvironment("https://mr-api.osm.lol/api/v2/")
    }
}

internal fun isLoopback(host: String?): Boolean =
    host?.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")

/** `maproulette.org` or any subdomain, ignoring case and a trailing dot. */
internal fun isProductionMapRoulette(host: String?): Boolean {
    val name = host?.lowercase()?.trimEnd('.') ?: return false
    return name == "maproulette.org" || name.endsWith(".maproulette.org")
}

/** Marks the low-level task-lifecycle writes (start, refresh, release, bare status writes).
 * Mobile apps complete tasks with [MapRouletteClient.submitChoice] and [MapRouletteClient.skipTask];
 * these exist for tooling and future flows. Opt in with `@OptIn(LowLevelTaskLifecycle::class)`. */
@RequiresOptIn(
    message = "Low-level task lifecycle write. Mobile apps use submitChoice and skipTask instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
public annotation class LowLevelTaskLifecycle
