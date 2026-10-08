# MapRoulette mobile SDK

Native Kotlin and Swift clients for the [MapRoulette](https://maproulette.org) API,
for Android and iOS apps.

What it does:

- Finds challenges and tasks: search, task lists, bounding-box search and map markers.
- Reads tasks, their kind and instructions, and the current user.
- Completes multiple-choice tasks with late locking: the lock lives only for the
  seconds of a submission.

What it does not do:

- It does not write to OpenStreetMap itself. For choice tasks the server applies the edit.
- It has no map UI, no sign-in UI and no offline support. Those belong to your app.

## Environments and backends

**Reads** work against any MapRoulette deployment, including production
(`https://maproulette.org`), and without signing in. The SDK reads every task kind.

**Completing tasks** works only for multiple-choice tasks. Regular tasks
(standard, tag fix, change file) can be listed, shown and mapped, but this
version cannot complete them. Check `task.mobileSupport()` before you show task
actions; see the [choice-tasks guide](docs/guide/choice-tasks.md).

Completion needs a running, configured mobile-enabled backend: the
[fork backend](https://github.com/mvexel/maproulette-mobile-backend/tree/feat/mobile-oauth),
with mobile OAuth enabled and your app registered as a client. Production
maproulette.org does not have these routes.

- [Mobile OAuth](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-oauth.md): configuration and client registration.
- [Deployment](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-staging-deploy.md): running the fork.
- [Fork changes](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/fork-changes.md): what the fork changes compared to upstream.

To evaluate the SDK, use the public staging deployment `https://mr-api.osm.lol`
with the example client IDs; see the
[staging checklist](docs/guide/authentication.md#staging-checklist-bearer-sign-in).

**Write policy.** In SDK 0.x the SDK sends task writes (skip, choice submission,
locks and status writes) only to staging, to a loopback server, or to a custom
environment you created with `allowWrites = true`. Everywhere else it refuses
the write before sending anything. Production maproulette.org is always
refused: opting in for `maproulette.org` or a subdomain fails when you create
the environment.

| Environment      | Kotlin                                                            | Swift                                                            | Writes        |
| ---------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------- | ------------- |
| Production       | `MapRouletteEnvironment.PRODUCTION`                               | `.production`                                                    | Refused       |
| Staging          | `MapRouletteEnvironment.STAGING`                                  | `.staging`                                                       | Allowed       |
| Custom           | `MapRouletteEnvironment("https://…/api/v2/")`                     | `try MapRouletteEnvironment(serviceURL:)`                        | Loopback only |
| Your own backend | `MapRouletteEnvironment("https://…/api/v2/", allowWrites = true)` | `try MapRouletteEnvironment(serviceURL: url, allowWrites: true)` | Allowed       |

## Requirements

|                | Minimum                                                                                       |
| -------------- | --------------------------------------------------------------------------------------------- |
| JDK (to build) | 17. The library is JDK 17 bytecode.                                                           |
| Android        | Test your own minimum. The demo uses `minSdk 26`. Needs AGP 8+ and the `INTERNET` permission. |
| iOS / macOS    | iOS 15 / macOS 12                                                                             |
| Swift / Xcode  | Swift 6 tools, Xcode 16+                                                                      |

## Install

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
    implementation("com.github.mvexel:maproulette-mobile-sdk:0.1.0")
}
```

The version in the coordinate is the git release tag. The package is
`org.maproulette.sdk`. It brings in OkHttp 4.12, kotlinx-coroutines 1.10 and
kotlinx-serialization-json 1.7.3 (an API dependency: `Task.geometry` is a `JsonObject`).

### Swift Package Manager

```swift
.package(url: "https://github.com/mvexel/maproulette-mobile-sdk", from: "0.1.0")
```

Then add the `MapRoulette` product to your target. In Xcode: File > Add Package
Dependencies, paste the URL, and pick the `MapRoulette` library.

## Quick start

An anonymous read against production. Kotlin:

```kotlin
import kotlinx.coroutines.runBlocking
import org.maproulette.sdk.*

fun main() = runBlocking {
    OkHttpTransport().use { transport ->
        val client = MapRouletteClient(transport = transport)
        val page = client.searchChallenges(ChallengeFilter(text = "bench"), pageSize = 5)
        page.items.forEach { println("${it.id.value}: ${it.name}") }
        val tasks = client.listTasks(ChallengeId(16441), pageSize = 2)
        tasks.items.forEach { println("Task ${it.id.value}: ${it.status?.knownName}") }
    }
}
```

Swift:

```swift
import MapRoulette

let client = MapRouletteClient()
let page = try await client.searchChallenges(filter: ChallengeFilter(text: "bench"), pageSize: 5)
for challenge in page.items { print("\(challenge.id): \(challenge.name)") }
let tasks = try await client.listTasks(try ChallengeID(16441), pageSize: 2)
for task in tasks.items { print("Task \(task.id): \(task.status?.knownName ?? "?")") }
```

In an app, keep one transport for the app's lifetime, and close it when done.

## Documentation

- [Getting started](docs/guide/getting-started.md): environments, sign-in,
  discovery, choice tasks, errors and testing.
- Examples: the [Android demo](android-example/README.md), the
  [iOS demo](ios-example/README.md), and the command-line examples
  (`./kotlin/gradlew -p kotlin runExample --args='16441'`, `swift run Example 16441`).
- [Changelog](CHANGELOG.md). In SDK 0.x, minor versions may break the API; patch
  versions do not.
- [Contributing](CONTRIBUTING.md).
- Design records: [docs/design/](docs/design/). They explain why the SDK works
  the way it does, with backend evidence.

## License

Copyright 2026 Martijn van Exel. Apache-2.0; see [LICENSE](LICENSE) and
[NOTICE](NOTICE). MapRoulette task payloads can contain OSM and other source data
with their own attribution and licensing requirements.
