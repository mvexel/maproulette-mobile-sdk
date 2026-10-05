# MapRoulette mobile SDK

Inspectable, native Kotlin and Swift clients for the deployed MapRoulette API.
The first slice is **read-only**: discover challenges, retrieve tasks and task
locations, and validate a user API key. No Rust, FFI, OSM database, or area
initialization is required.

This is an unpublished development SDK. APIs may change. Kotlin/JVM supports
integration into Android applications; Swift Package Manager targets iOS 15+
and macOS 12+. Examples run on the host to exercise the same library clients.

## Read API

| Operation | Behavior |
| --- | --- |
| Search challenges | ANY challenge-tag matching, optional name text, local-survey and archive filters |
| Get challenge / challenge tags | Full supported metadata; tags are MapRoulette labels, not OSM key/value tags |
| List challenge tasks | Explicit pages, including completed tasks |
| Find tasks in bounds | Selected challenge IDs and task-location bounds, explicit status selection |
| Get task | Full task geometry/properties and cooperative-work JSON |
| Get current user | Minimal identity; raw identity credentials are discarded |

Task and challenge IDs are distinct from OSM IDs. Tasks can contain multiple
features. The SDK does not infer OSM element types from task names or geometry.

## Kotlin

Requires JDK 17 for building. If Java is managed by mise or another version
manager, activate it first or set `JAVA_HOME` to the JDK installation.

```kotlin
val transport = OkHttpTransport() // Own and close this at application/service lifetime.
val client = MapRouletteClient(
    transport = transport,
    apiKey = { credentialStore.mapRouletteApiKey() }, // Optional; public reads work without it.
)
val filter = ChallengeFilter(tags = listOf("your-campaign-tag"))
val first = client.searchChallenges(filter, pageSize = 25)
val second = first.next?.let {
    client.searchChallenges(filter, pageSize = 25, after = it)
}
val tasks = client.listTasks(ChallengeId(16441), pageSize = 25)
```

Import `org.maproulette.sdk.*`. Public methods are suspending. HTTP uses OkHttp;
cancelling the calling coroutine cancels the request. The JVM artifact needs no
Android SDK to build. Android applications need the `INTERNET` permission and a
modern Android Gradle Plugin capable of consuming Java 17 bytecode. No Android
device validation is claimed for this first host-tested slice.

For local Android development, add `includeBuild("/path/to/maproulette-mobile-sdk/kotlin")`
to your application's Gradle settings, then depend on
`org.maproulette:maproulette-mobile-sdk:0.1.0-SNAPSHOT`. This is a local composite
build, not a published Maven coordinate.

Build the library JAR and run the example:

```sh
cd kotlin
./gradlew test jar
./gradlew run --args='16441'
```

## Swift

The package is in `swift/`. Add it as a local Swift package dependency and use
the `MapRoulette` product. See [the Swift example](swift/Sources/Example) for a
complete runnable call sequence.

```swift
import MapRoulette

let client = try MapRouletteClient()
let filter = ChallengeFilter(tags: ["your-campaign-tag"])
let first = try await client.searchChallenges(filter: filter, pageSize: 25)
if let continuation = first.next {
    let second = try await client.searchChallenges(
        filter: filter, pageSize: 25, after: continuation
    )
    print(second.items.count)
}
```

Inject credentials with `MapRouletteClient(apiKey: { ... })` when needed.

```sh
cd swift
swift test
swift run maproulette-example 16441
```

Both examples optionally read `MAPROULETTE_API_KEY` from their environment.
The libraries themselves do not read `.env` files or acquire credentials.
Inject a user key obtained from MapRoulette and store it using your platform's
credential store. This is separate from OSM OAuth authentication. No key is
included in this repository.

## Per-user authentication

Each client obtains credentials from its own provider. There is no global key
or bundled application credential. The CLI environment variable is a developer
convenience for running a single-user example, not an end-user sign-in design.
The shared tests verify two users, anonymous access, key rotation and logout
without leaking credentials between clients.

**Browser sign-in and credential acquisition are not implemented yet.** This
read client is not a complete end-user authentication SDK. MapRoulette's
existing web OAuth callback uses a server-side client secret; applications
must not embed that secret or assume an OSM access token is a MapRoulette key.
Backend-supported mobile sign-in is the agreed next direction: a registered
public client, browser authorization with PKCE, and credentials bound to the
user and application. That backend contract and native auth component are not
yet implemented; the current client supports injected personal API keys only.

For an application that already has a user's MapRoulette key, supply it through
that user's provider, validate it with `getCurrentUser()`, and store it securely
under the service origin and user identity. On account switch, cancel requests
from the old account and discard its client/continuations before creating a new
client. Future pending work must remain bound to the user who created it.
Clearing local credentials is logout; it does not revoke a server-side key.

## Behavioral contract

- Local-survey challenges are included by default; archived challenges are
  excluded by default. A matching challenge label is not proof a task is
  executable by your application.
- Pagination is explicit, capped at 100 items per request, and represented by
  an opaque continuation bound to the client, operation, filter, and page size.
  Never reuse a continuation after changing a filter. Tokens are in-memory,
  not durable resume checkpoints.
- The client hides the deployed API's offset/page mismatch: challenge search
  advances by rows, task pages advance by page number. A full page means
  another request may be necessary. The server provides no snapshot guarantee;
  concurrent changes can produce skips or duplicates during enumeration.
- Spatial results are summaries selected by **task location**, not exact
  feature-geometry intersections. Fetch full task details explicitly.
- Missing task status is distinct from Created. Unknown numeric statuses are
  preserved; applications should not assume they are actionable.
- No automatic retries: callers choose when to retry a failed read. Errors
  distinguish authentication, permission, missing objects, conflict, rate
  limits, server/HTTP/network failures, and invalid server responses. Rate-limit
  errors preserve the `Retry-After` value. Native cancellation propagates.
- Redirects are disabled in the supplied transports. Request headers and raw
  response bodies are not included in their descriptions or SDK error text.
  Custom transports must preserve those protections and cancellation behavior.
- Transport failures are distinct from malformed response data. Invalid caller
  arguments are rejected before requests; credential-provider errors propagate
  to the caller instead of being mislabeled as server protocol failures.

## Validation and scope

Shared synthetic JSON fixtures in `fixtures/` define decoding, request and
pagination expectations. Both platform test suites consume them. Fake
transports exercise errors without production mutations. Run both suites with
`scripts/check.sh` (JDK 17+ and Swift 6 required).

There are no locks, task completion writes, comments, offline task packs, edit
queues, OSM uploads, map UI or campaign question definitions yet. Some deployed
MapRoulette GET routes mutate state; the client deliberately exposes only the
verified read routes. Writes require a separately designed lifecycle and
controlled integration tests.

The [read-only probe record](docs/api-probes.json) records API quirks verified
against the backend source and deployed service. The design discussion is
local and deliberately excluded from Git. This project originated in the
Cantino experiment. Native source here is new;
Cantino supplied design experience and the Gradle wrapper bootstrap. Plans and
work log are kept in `~/obsidian/agent/maproulette-mobile-sdk/TASKS.md`.

## License

Apache-2.0; see [LICENSE](LICENSE). MapRoulette task payloads can contain OSM
and other source data with their own attribution and licensing requirements.
