package org.maproulette.sdk

import java.net.URI

/** The MapRoulette deployment a client talks to.
 *
 * Before 1.0, task writes (lock, release, skip, status and choice submission) go only to the
 * disposable staging deployment or to a loopback server; see [allowsWrites]. Reads work against
 * any environment.
 *
 * @param serviceUrl the API base, e.g. `https://maproulette.org/api/v2/`. It must be https (or http
 *   on a loopback host) with a host and no credentials, query or fragment. */
public class MapRouletteEnvironment(serviceUrl: String) {
    /** The API base, always ending in `/`. */
    public val serviceUrl: String = serviceUrl.let { if (it.endsWith("/")) it else "$it/" }

    init {
        val uri = runCatching { URI(this.serviceUrl) }.getOrNull()
        require(
            uri != null && !uri.host.isNullOrEmpty() && uri.port <= 65535 && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && isLoopback(uri.host))),
        ) { "serviceUrl must be https (or http on loopback) with a host and no credentials, query or fragment" }
    }

    /** Whether this SDK version sends task writes here: only to the staging origin
     * (`https://mr-api.osm.lol`) and to loopback hosts (local servers and tests). */
    public val allowsWrites: Boolean
        get() {
            val uri = URI(serviceUrl)
            if (isLoopback(uri.host)) return true
            return uri.scheme == "https" && uri.host.equals("mr-api.osm.lol", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443)
        }

    override fun equals(other: Any?): Boolean = other is MapRouletteEnvironment && other.serviceUrl == serviceUrl
    override fun hashCode(): Int = serviceUrl.hashCode()
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
