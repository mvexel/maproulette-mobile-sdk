# Getting started

This guide takes you from install to a completed task. It covers SDK version
0.2.0. Kotlin and Swift mirror each other; names differ only where each
language has its own conventions.

Longer topics have their own pages:

- [Authentication](authentication.md)
- [Completing a choice task](choice-tasks.md)
- [Handling errors](errors.md)

## 1. What this SDK does and does not do

- It reads challenges, tasks, task locations and the current user.
- It completes multiple-choice tasks, and skips tasks, with late locking.
- It never writes to OpenStreetMap itself. For a choice task, the server applies
  the OSM edit.
- It has no map UI and no sign-in UI. Your app owns both.
- It has no offline support, no edit queue and no automatic retries.

The SDK makes no request on its own. Every call maps to one documented route,
except `submitChoice`, which locks and then submits.

## 2. Requirements

| | Minimum |
| --- | --- |
| JDK (to build) | 17. The library is JDK 17 bytecode. |
| Android | Test your own minimum. The demo uses `minSdk 26`. AGP 8+, `INTERNET` permission. |
| iOS / macOS | iOS 15 / macOS 12 |
| Swift / Xcode | Swift 6 tools, Xcode 16+ |

Kotlin dependencies: OkHttp 4.12, kotlinx-coroutines 1.10 and
kotlinx-serialization-json 1.7.3. The last one is an API dependency, because
`Task.geometry`, `Task.location`, `Task.cooperativeWork` and `TaskSummary.point`
are `JsonObject`s. The Swift package has no dependencies.

## 3. Install

### Gradle (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.mvexel:maproulette-mobile-sdk:0.2.0")
}
```

The version in the coordinate is the git release tag. JitPack builds a tag the
first time someone requests it, so the first build can take a few minutes.
Import `org.maproulette.sdk.*`.

### Swift Package Manager

In `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/mvexel/maproulette-mobile-sdk", from: "0.2.0"),
],
targets: [
    .target(name: "MyApp", dependencies: [
        .product(name: "MapRoulette", package: "maproulette-mobile-sdk"),
    ]),
]
```

In Xcode: File > Add Package Dependencies, paste the URL, choose "Up to Next
Minor Version" from 0.2.0, and add the `MapRoulette` library to your app target.

### Versions

In SDK 0.x, a minor version (0.2.0) may break the API. A patch version (0.1.1)
does not. In SwiftPM, `from: "0.1.0"` also accepts 0.2.0 and later. To get
patch releases only, use `.upToNextMinor(from: "0.1.0")`. In Gradle, the
version is exact.

`ErrorKind`, `WriteProblem` and `ChoiceProblem` may gain cases in a minor
release. Always add an `else` / `default` branch when you switch on them.

The SDK sends `User-Agent: MapRoulette-Mobile-SDK/<version>`. The version is
`MapRouletteSdk.VERSION` in Kotlin and `MapRouletteSDK.version` in Swift.

## 4. Pick an environment

| Environment | Kotlin | Swift | Writes |
| --- | --- | --- | --- |
| Production, `https://maproulette.org/api/v2/` | `MapRouletteEnvironment.PRODUCTION` | `.production` | Refused |
| Staging, `https://mr-api.osm.lol/api/v2/` | `MapRouletteEnvironment.STAGING` | `.staging` | Allowed |
| Custom | `MapRouletteEnvironment(serviceUrl)` | `try MapRouletteEnvironment(serviceURL:)` | Loopback only |
| Your own backend | `MapRouletteEnvironment(serviceUrl, allowWrites = true)` | `try MapRouletteEnvironment(serviceURL:allowWrites:)` | Allowed |

Production is the default. A custom URL must be https, or http on a loopback
host, with no credentials, query or fragment.

Reads work everywhere, anonymously too, and cover every task kind. Writes do
not. In SDK 0.x, the SDK sends task writes only where
`environment.allowsWrites` is true: the staging origin `https://mr-api.osm.lol`,
loopback hosts, and custom environments created with `allowWrites = true`.
Everywhere else, `skipTask`, `submitChoice` and the low-level lifecycle calls
fail before anything is sent. Kotlin throws `IllegalStateException`; Swift
throws `MapRouletteError` with kind `.validation` and a `reason`.

Production maproulette.org is always refused. Passing `allowWrites = true` for
`maproulette.org` or any `*.maproulette.org` host fails when you create the
environment: Kotlin throws `IllegalArgumentException`, Swift throws a
`.validation` error. Production does not have the mobile routes this SDK needs
for writing: bearer sign-in (`/oauth/mobile/*`) and the choice routes exist only
on the [fork backend](https://github.com/mvexel/maproulette-mobile-backend/tree/feat/mobile-oauth).

Completing tasks needs a running, configured fork with mobile OAuth enabled and
your app registered as a client. The fork's docs cover
[configuration and client registration](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-oauth.md),
[deployment](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-staging-deploy.md) and
[what it changes compared to upstream](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/fork-changes.md).
To run your own, see [Your own backend](authentication.md#your-own-backend).

To evaluate the SDK, use staging. It runs the fork against the development OSM
server (`master.apis.dev.openstreetmap.org`), with its own database, and accepts
the [example client IDs](authentication.md#staging-checklist-bearer-sign-in).
Staging users and challenges are separate from production ones.

A good setup: read from production while you build discovery screens, and
point a debug build at staging (or your own backend) when you work on task
completion.

## 5. First call in five minutes

Kotlin, in a coroutine. Close the transport when you are done; in an app, keep
one transport for the app's lifetime.

```kotlin
import kotlinx.coroutines.runBlocking
import org.maproulette.sdk.*

fun main() = runBlocking {
    OkHttpTransport().use { transport ->
        val client = MapRouletteClient(transport = transport)
        val challenges = client.searchChallenges(ChallengeFilter(text = "bench"), pageSize = 5)
        challenges.items.forEach { println("${it.id.value}: ${it.name}") }

        val tasks = client.listTasks(ChallengeId(16441), pageSize = 2)
        val task = client.getTask(tasks.items.first().id) // a fresh read, with lock and completion fields
        println("Task ${task.id.value}: ${task.name}, status ${task.status?.knownName}")
    }
}
```

Swift, inside a `Task {}` or another async context:

```swift
import MapRoulette

let client = MapRouletteClient()
let challenges = try await client.searchChallenges(
    filter: ChallengeFilter(text: "bench"), pageSize: 5)
for challenge in challenges.items { print("\(challenge.id): \(challenge.name)") }

let tasks = try await client.listTasks(try ChallengeID(16441), pageSize: 2)
let task = try await client.getTask(tasks.items[0].id)
print("Task \(task.id): \(task.name), status \(task.status?.knownName ?? "unknown")")
```

You should see a few challenge names, then one task with a status such as
`Created` or `Fixed`. Live data changes, so exact output varies.

All client methods are `suspend` (Kotlin) or `async throws` (Swift).
Cancelling the calling coroutine or task cancels the request.

## 6. Authentication

Anonymous reads need nothing. For the current user, skipping and completing
tasks you need a per-user credential: a personal API key, or a bearer token from
browser sign-in on the fork backend. Pass a provider by name:

```kotlin
val client = MapRouletteClient(
    environment = MapRouletteEnvironment.STAGING,
    transport = transport,
    accessToken = { session.currentAccessToken() },
)
```

```swift
let client = MapRouletteClient(
    environment: .staging,
    accessToken: { try await session.currentAccessToken() })
```

See [Authentication](authentication.md) for the decision table, the staging
checklist, sign-in on Android and iOS, rotation and sign-out.

## 7. Discovering tasks

### Pages and cursors

Paged reads return `Page`: `items`, `next` and sometimes `total`. Page size is
1 to 100. To get the next page, pass `next` back as `after`, with the same
filter and page size:

```kotlin
val filter = ChallengeFilter(tags = listOf("your-campaign-tag"))
val first = client.searchChallenges(filter, pageSize = 25)
val second = first.next?.let { client.searchChallenges(filter, pageSize = 25, after = it) }
```

```swift
let filter = ChallengeFilter(tags: ["your-campaign-tag"])
let first = try await client.searchChallenges(filter: filter, pageSize: 25)
if let cursor = first.next {
    let second = try await client.searchChallenges(filter: filter, pageSize: 25, after: cursor)
}
```

A `PageCursor` is opaque and lives in memory only. It is bound to one client,
one operation, one filter and one page size. Using it anywhere else is a
validation error. Do not store it. The server gives no snapshot guarantee, so
tasks can shift between pages while you enumerate.

Challenge tags are MapRoulette labels, not OSM tags. A tag filter matches ANY of
the given tags. Local-survey challenges are included and archived ones excluded
by default.

### Tasks on a map

Two reads take a `TaskFilter` with `Bounds(west, south, east, north)`:

- `findTaskMarkers(filter, limit)`: up to `limit` (1 to 1000, default 100)
  markers, unordered, with no total and no paging. Fast. Use it for a map. A
  full result may be truncated: ask the user to zoom in.
- `findTasksInBounds(filter, pageSize, after)`: sorted pages with a `total`.
  Slower. Use it for lists.

Both select by task location, not by exact geometry. An empty challenge list
means all visible challenges. The default statuses are Created, Skipped and
Too hard. Markers can include tasks locked by other users.

`TaskSummary.point` is `{"lat": …, "lng": …}`, not a GeoJSON Point. Read the
named fields. Full task geometry (`Task.geometry`) is a GeoJSON
FeatureCollection; fetch it with `getTask`.

```kotlin
val bounds = Bounds(west = -111.91, south = 40.75, east = -111.87, north = 40.78)
val markers = client.findTaskMarkers(TaskFilter(bounds = bounds, choiceOnly = true), limit = 100)
```

```swift
let bounds = try Bounds(west: -111.91, south: 40.75, east: -111.87, north: 40.78)
let markers = try await client.findTaskMarkers(
    filter: TaskFilter(bounds: bounds, choiceOnly: true), limit: 100)
```

### Which tasks can a mobile app complete?

Only multiple-choice tasks, and only against a mobile-enabled backend (see
[§4](#4-pick-an-environment)). The SDK reads every task kind, so regular tasks
(standard, tag fix, change file) can be listed, shown and mapped, but this
version cannot complete them. Use two gates:

1. `choiceOnly = true` in `TaskFilter` asks the server for choice challenges
   only, without stale tasks. Servers without this filter (production today)
   ignore it.
2. `task.mobileSupport()` is `IN_PLACE` (Swift `.inPlace`) only for a valid,
   unbundled choice task with status Created, Skipped or Too hard. Everything
   else is `UNSUPPORTED`: standard, tag-fix and change-file tasks included.
   Always check it before you show task actions.

`task.work()` decodes the task kind: standard, tag fix, change file, choice or
unknown. `task.resolvedInstruction(challenge)` picks the task or challenge
instruction, and `render(task.templateProperties())` fills in `{{property}}`
placeholders. Markdown rendering is up to your app.

## 8. Completing a choice task

The short version: on open, call `checkChoice`. Show the questions and
outcomes from `client.choiceOutcomes(task)`. Submit with `submitChoice`. Then
re-read the task.

```kotlin
val result = client.submitChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes")))
```

```swift
let result = try await client.submitChoice(task, .answers(["backrest": "yes"]))
```

Answering some questions and leaving others as "Can't tell" still closes the
task as Fixed. The unanswered questions are lost. See
[Completing a choice task](choice-tasks.md) for the full flow, element deletion,
skip and this limitation.

## 9. Handling errors

Kotlin throws `MapRouletteException` for HTTP and protocol failures, and
`IllegalArgumentException` for bad arguments. Swift throws `MapRouletteError`
for both. Each error has a `kind`, and write failures add a typed `problem`.
After `OutcomeUnknown`, re-read before you do anything. See
[Handling errors](errors.md) for the tables and recovery steps.

## 10. Testing your app

### Fake transports

The client takes any `Transport`. A fake returns canned responses and needs no
network:

```kotlin
val fake = Transport { request ->
    when {
        request.url.endsWith("/challenge/42") -> HttpResponse(200, body = challengeJson)
        else -> HttpResponse(404)
    }
}
val client = MapRouletteClient(transport = fake)
```

```swift
struct FakeTransport: Transport {
    let responses: [String: HTTPResponse]
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        responses[request.url.path] ?? HTTPResponse(status: 404, body: Data())
    }
}
let client = MapRouletteClient(transport: FakeTransport(responses: [...]))
```

A fake transport on a loopback URL also lets you test writes:
`MapRouletteEnvironment("http://127.0.0.1/api/v2/")` (Kotlin) or
`try MapRouletteEnvironment(serviceURL: URL(string: "http://127.0.0.1/api/v2/")!)`
(Swift) allows writes, and the fake answers them.

A real custom transport must honor cancellation, must not follow redirects with
credentials, and must not log headers or bodies.

### Building models

Often it is simpler to fake your own service layer and pass it models.

- Kotlin models are data classes. Build them directly. `Page(items, next = null, total)`.
- Swift models have public initializers and are `Hashable`. `Challenge`,
  `MapRouletteTask`, `TaskSummary`, `UserIdentity`, `ChallengeTag`,
  `ChoiceQuestion`, `ChoiceOption` and `ChoiceOutcome` are `Identifiable`, so
  they work in SwiftUI `List` and `ForEach`. `ChallengeID`, `TaskID` and
  `ProjectID` are `Codable` (a bare number), `Comparable` and print as the
  number. `Page(items:total:)` builds a page with no cursor.

```swift
let task = MapRouletteTask(
    id: try TaskID(101), challengeID: try ChallengeID(3), name: "Bench 101",
    status: TaskStatus(code: 0))
```

### Shared fixtures

The repository's [`fixtures/`](../../fixtures/README.md) folder holds synthetic
JSON for every response shape the SDK reads, every error body it maps, and
valid and invalid choice payloads. Both SDK test suites load these files. They
are good examples for your own fakes.

## 11. Running the examples

- **Android demo** ([README](../../android-example/README.md)): map, challenge
  list, task screen with the choice UI and sign-in. The default build reads
  production anonymously. Gradle properties point a debug build at staging.
- **iOS demo** ([README](../../ios-example/README.md)): the same app in SwiftUI.
  `scripts/ios-test.sh` builds it and runs its unit tests.
- **Command-line examples**: read-only, against production. An optional
  `MAPROULETTE_API_KEY` environment variable adds an identity read.

```sh
./kotlin/gradlew -p kotlin runExample --args='16441'
swift run Example 16441
```

## 12. Contributing

See [CONTRIBUTING.md](../../CONTRIBUTING.md): toolchain setup with mise and
Xcode, `scripts/check.sh`, the shared-fixture rule, CI, and the rule that
development writes go only to staging.

## 13. Reference

- API docs: KDoc in the Kotlin sources; build HTML with
  `./kotlin/gradlew -p kotlin dokkaGeneratePublicationHtml`. The JitPack javadoc
  JAR contains the same HTML. Swift DocC is not set up yet; read the doc
  comments in `swift/Sources/MapRoulette`.
- Design records with backend evidence: [docs/design/](../design/), in
  particular [task completion](../design/task-completion.md) and
  [multiple-choice challenges](../design/mobile-choice-challenges.md).
- [Changelog](../../CHANGELOG.md).
