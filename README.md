# MapRoulette mobile SDK

Inspectable, native Kotlin and Swift clients for the deployed MapRoulette API.
The first slice is **read-only**: discover challenges, retrieve tasks and task
locations, and retrieve the current user identity. No Rust, FFI, OSM database, or area
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
| Find tasks in bounds | All or selected challenges within task-location bounds, explicit status selection |
| Find task markers | Capped, unordered map markers within bounds; no totals or pagination |
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
modern Android Gradle Plugin capable of consuming Java 17 bytecode. The Android
example has been smoke-tested on a physical Pixel 8.

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

## Android test app

`android-example/` is a separate, minimal Android application consuming the
Kotlin SDK through a Gradle composite build. Open that directory in Android
Studio, or build and install it on a connected device:

```sh
cd android-example
./gradlew :app:assembleDebug
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n org.maproulette.example/.MainActivity
```

Configure your Android SDK in Android Studio or through `ANDROID_HOME` first.
The example requires SDK 36 and runs on Android 8.0 (API 26) or later.
The app accepts a challenge ID, lists tasks and opens task details. Its nearby
task map uses MapLibre Native with the
[OpenFreeMap Liberty style](https://openfreemap.org/quick_start/). Pan/zoom and
choose **Search this area** to load up to 100 task locations across challenges,
or use **My location** to center the map. Location permission is optional. Tap a
task marker to read its details and challenge instructions. Map rendering and
location handling belong to this example, not the SDK. The map uses the SDK’s
default statuses (Created, Skipped and TooHard), excluding archived challenges
and requiring enabled projects/challenges;
these statuses do not guarantee a task can be completed in a mobile app. It uses anonymous access by default. An opt-in browser sign-in prototype can
connect to a backend with the mobile OAuth endpoints enabled; see the
[Android setup](android-example/README.md). No credentials are bundled.

Validated on a Pixel 8: live challenge 16441, its first 20 tasks, full task
details, missing-challenge error, retry and recovery. `:app:assembleDebug` and
`:app:lintDebug` pass; lint retains advisory warnings for localization, app icon,
backup configuration and available dependency/SDK updates. Nearby markers and
a marker’s task/challenge instructions were verified on the Pixel 8; location
centering worked, and permission-denied fallback was checked on the emulator.
Map conversion tests and both SDK contract suites pass. The opt-in Android
sign-in flow was verified against a local synthetic OSM provider on the Pixel:
browser consent, session restoration, refresh rotation and confirmed logout.
Real OSM sign-in still requires development OAuth registration.

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

Both SDKs accept either `apiKey: { ... }` or `accessToken: { ... }` providers.
Bearer credentials use `Authorization` and the same configured service origin;
`getCurrentUser()` uses `/oauth/mobile/me` for bearer identity. Supplying both
credential types fails before HTTP, and a rejected bearer never falls back to
an API key. Providers are evaluated per request so hosts can rotate credentials.

The Android example owns a browser/PKCE sign-in prototype using AppAuth,
Android Keystore storage, serialized refresh and local-first logout. It requires
the opt-in backend implementation in the companion local backend checkout;
the deployed MapRoulette service is not assumed to support these endpoints.
The native SDKs remain read clients: browser integration and credential storage
belong to the host app, and Swift has no sign-in UI. OSM access tokens and
server-side client secrets must never be supplied as MapRoulette credentials.

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
- Paginated reads are explicit, capped at 100 items per request, and represented by
  an opaque continuation bound to the client, operation, filter, and page size.
  Never reuse a continuation after changing a filter. Tokens are in-memory,
  not durable resume checkpoints.
- The client hides the deployed API's offset/page mismatch: challenge search
  advances by rows, task pages advance by page number. A full page means
  another request may be necessary. The server provides no snapshot guarantee;
  concurrent changes can produce skips or duplicates during enumeration.
- Spatial results are summaries selected by **task location**, not exact
  feature-geometry intersections. Empty/default challenge IDs search across
  challenges. Summary points use `{lat, lng}`; task detail geometry is GeoJSON.
  Fetch full task details explicitly. Map marker reads use a separate capped,
  unordered response; reaching the limit means more may exist. They exclude
  archived challenges by default and, when searching across challenges, require
  enabled challenges and projects. Markers can include locked tasks; visibility
  does not imply a task is available to edit. Paginated task reads remain available for enumeration.
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

The SDK does not provide locks, task completion writes, comments, offline task
packs, edit queues, OSM uploads, map UI or campaign question definitions. The
Android example supplies its own map UI. Some deployed
MapRoulette GET routes mutate state; the client deliberately exposes only the
verified read routes (including the marker-search PUT, which retrieves data).
Writes require a separately designed lifecycle and
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
