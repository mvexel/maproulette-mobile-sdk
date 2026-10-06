# Contributing

Thanks for helping. This file covers setup, tests and the few rules that keep
the two SDKs in step and keep real data safe.

## Layout

| Path | What |
| --- | --- |
| `kotlin/` | Kotlin SDK (Gradle build, `org.maproulette.sdk`) and its CLI example |
| `swift/` | Swift SDK sources, tests and CLI example. `Package.swift` is at the repository root |
| `fixtures/` | Shared JSON fixtures, loaded by both test suites |
| `android-example/` | Android demo app |
| `ios-example/` | iOS demo app |
| `docs/guide/` | Consumer docs |
| `docs/design/` | Design records and API probe evidence |
| `scripts/` | Test scripts |

## Setup

- **Java:** [mise](https://mise.jdx.dev) pins JDK 17 (`temurin-17`) in
  `mise.toml`, matching the Gradle `jvmToolchain(17)`. Run `mise install` once in
  the repository. Without mise, set `JAVA_HOME` to a JDK 17, for example Android
  Studio's bundled JBR.
- **Swift:** Xcode 16 or newer (Swift 6 tools).
- **Android demo:** the Android SDK, through Android Studio or `ANDROID_HOME`.
- **iOS demo:** Xcode with an iPhone simulator.

Gradle runs through the checked-in wrappers. Nothing else needs installing.

## Tests

```sh
scripts/check.sh            # Kotlin and Swift SDK tests
scripts/ios-test.sh         # iOS demo: build and unit tests on the first available iPhone simulator
cd android-example && mise exec -- ./gradlew assembleDebug testDebugUnitTest lintDebug
```

`scripts/ios-test.sh` takes extra `xcodebuild` arguments; set `IOS_SIMULATOR_ID`
to pick a simulator.

Run the CLI examples (read-only, against production):

```sh
./kotlin/gradlew -p kotlin runExample --args='16441'
swift run Example 16441
```

Build the Kotlin API docs with
`./kotlin/gradlew -p kotlin dokkaGeneratePublicationHtml`.

## Keep both platforms aligned

Kotlin and Swift implement the same behavior. Public APIs may differ where each
language has its own idiom, but requests, decoding, validation and error
mapping must match.

`fixtures/` is the shared contract. Both suites load the same files directly;
there are no copies. To change behavior:

1. Add or change a case in the fixture file.
2. Add or update the test on **both** platforms.
3. Change both SDKs until `scripts/check.sh` passes.

See [fixtures/README.md](fixtures/README.md) for what each file holds.

## No writes to production

- During development, never write to production MapRoulette or to
  openstreetmap.org.
- Contract tests use mock transports. They never touch the network.
- Live writes go only to the disposable staging deployment
  (`https://mr-api.osm.lol`, development OSM). The SDK enforces this itself
  before 1.0.
- Some MapRoulette GET routes change state (`start`, `release`, `refreshLock`).
  Never call them, or skip, comments or status writes, as a "read" probe.

## Credentials

- Never commit or log credentials: API keys, access or refresh tokens, client
  secrets.
- Never log a raw `user/whoami` response. It contains the user's API key.
- Keep request headers and response bodies out of error messages and test
  output.

If you find a leaked credential, do not open a public issue with it. Contact
the maintainer privately.

## CI

`.github/workflows/ci.yml` runs on every push to `main` and on pull requests:

- SDK tests on macOS (`scripts/check.sh`) and a Kotlin `publishToMavenLocal`;
- the Android demo build, unit tests and lint on Ubuntu;
- the iOS demo build and unit tests on a macOS simulator.

`jitpack.yml` tells JitPack to build the `kotlin/` project with JDK 17.

## Releasing

1. Set the new version in `VERSION` and in `MapRouletteSDK.version`
   (`swift/Sources/MapRoulette/Environment.swift`). A Swift test checks that they
   match; Kotlin reads `VERSION` at build time.
2. Move the `Unreleased` notes in `CHANGELOG.md` under the new version and date.
3. Commit, then tag the commit with the bare version, e.g. `0.1.0`, and push the tag.
4. Create a GitHub release from the tag, with the changelog entry as notes.
5. JitPack builds the Kotlin artifact the first time someone requests the tag.
   Request it once yourself to check the build. SwiftPM uses the tag directly.

Before 1.0, a minor version may break the API; a patch version must not.

## Design docs

Design records live in [docs/design/](docs/design/). They hold the backend
evidence behind the SDK's behavior: task completion, task kinds, choice tasks,
API probes and the developer-experience review. Update them when a change
alters a documented decision. Consumer docs live in [docs/guide/](docs/guide/getting-started.md).
