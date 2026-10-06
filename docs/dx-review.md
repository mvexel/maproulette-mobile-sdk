# Developer-experience review

Date: 2026-10-06. Scope: `kotlin/`, `swift/`, `android-example/`, `docs/`,
`fixtures/`, `scripts/`, root `README.md`. Snapshot: `main` at `8841f76`,
plus the uncommitted edits to `.gitignore` and `docs/mobile-choice-challenges.md`.
`ios-example/` did not exist yet when this review was written, so it is not
covered.

This is a review only. No source, docs or build files were changed.

Priorities: **P0** blocks a first release. **P1** should be fixed before
release. **P2** is nice to have. Effort: **S** < 2 h, **M** ½–1 day,
**L** > 1 day.

## Summary

The core is solid. Both SDKs have a small surface, a careful error model, and
honest docs on lock and write semantics. The shared fixtures keep both
platforms in step. Most DX problems are packaging problems and doc drift:

- Neither SDK can be consumed the normal way. There is no Maven publication,
  and SwiftPM cannot use a package whose `Package.swift` is in a subdirectory.
- The choice/deletion opt-in is spread over three call sites. The Android demo
  needs two clients to submit "gone" without deleting.
- Several docs contradict the code: the handoff, the status lines of the
  design docs, and the root README's task-write row.
- The README mixes consumer docs, validation logs and internal history.

## Findings

### P0: blocks release

#### P0-1. SwiftPM cannot consume the package by URL

- **Where:** `swift/Package.swift:1`. README `README.md:225` says "Add it as
  a local Swift package dependency".
- **Problem:** SwiftPM resolves a remote dependency only from a
  `Package.swift` at the repository root. With the manifest in `swift/`,
  `.package(url: "https://github.com/…/maproulette-mobile-sdk", …)` fails.
  The only option is a local path or a git submodule.
- **Change:** Pick one:
  1. Move `Package.swift` to the repository root, with
     `path: "swift/Sources/MapRoulette"` per target. The test target and
     fixtures paths need the same treatment.
  2. Publish the Swift package from a separate mirror repository.

  Option 1 is simpler. Then document the URL and a `from: "0.1.0"` version
  rule in the README.
- **Effort:** S–M. Tests use `#filePath` to find `fixtures/`, so check those
  paths after the move.

#### P0-2. The Kotlin SDK has no publication and no settled coordinates

- **Where:** `kotlin/build.gradle.kts:1-17`; `README.md:168-171` ("not a
  published Maven coordinate").
- **Problem:** There is no `maven-publish` or signing setup, and no POM
  metadata (license, SCM, developers). Consumers must use `includeBuild` with
  an absolute path. The group `org.maproulette` implies ownership of the
  `maproulette.org` namespace. Publishing under it to Maven Central needs
  domain verification by the MapRoulette project.
- **Change:** Decide the coordinates first: `org.maproulette` with the
  project's agreement, or `io.github.mvexel`. Then add `maven-publish` with
  `withSourcesJar()` and `withJavadocJar()` (Dokka), plus `signing` and a POM.
  Until a Central release exists, publish to GitHub Packages or JitPack and
  document the coordinates.
- **Effort:** M. The coordinates question is a user decision.

#### P0-3. The example `main` ships inside the library JAR

- **Where:** `kotlin/src/main/kotlin/org/maproulette/sdk/Example.kt:6`;
  `kotlin/build.gradle.kts:3,16` (`application` plugin).
- **Problem:** A public top-level `fun main` in package `org.maproulette.sdk`
  compiles into `ExampleKt` and becomes part of the published API and JAR.
  Swift has the same problem in a milder form: `Package.swift:9` exports
  `maproulette-example` as a product. Every consumer sees it in Xcode's
  product picker.
- **Change:** Kotlin: move `Example.kt` to a separate `examples` source set or
  Gradle subproject, and keep `application` there. Swift: drop the
  `.executable` product but keep the `executableTarget`. `swift run Example`
  still works.
- **Effort:** S.

#### P0-4. No version, changelog or stability statement

- **Where:** `kotlin/build.gradle.kts:7` (`0.1.0-SNAPSHOT`); the User-Agent is
  hard-coded as `MapRoulette-Mobile-SDK/0.1` in
  `kotlin/.../MapRouletteClient.kt:585` and
  `swift/Sources/MapRoulette/MapRouletteClient.swift:603`. There are no git
  tags and no `CHANGELOG.md`.
- **Problem:**
  - Consumers cannot pin a version.
  - The two platforms have no shared version source.
  - The User-Agent will go stale.
  - `README.md:9` says "APIs may change" but defines no 0.x policy.
- **Change:**
  - Add `CHANGELOG.md` (Keep a Changelog format).
  - Tag `v0.1.0` for both platforms together.
  - Generate the User-Agent version from one constant per platform: a Gradle
    `version` written into a generated source, and a Swift `static let`
    checked by a test.
  - State the policy: "0.x: minor versions may break the API; patch versions
    do not".
- **Effort:** S.

### P1: should fix

#### P1-1. The deletion opt-in is spread over three call sites and forces two clients

- **Where:**
  - Kotlin: `MapRouletteClient.kt:19`, `TaskKinds.kt:88`
    (`work(allowElementDeletion)`), `Choice.kt:64`
    (`choiceOutcomes(allowElementDeletion)`), `Choice.kt:193` (validation
    rejects a mismatch).
  - Swift: the same pattern at `MapRouletteClient.swift:29`,
    `TaskKinds.swift:103` and `Choice.swift:88`.
  - The workaround in the demo: `android-example/.../task/TaskWorkController.kt:54-62,444-456`
    and `auth/AppSession.kt:373`.
- **Problem:**
  - Each call site defaults to `false`. A UI that calls `task.choiceOutcomes()`
    while the client has `allowElementDeletion = true` fails validation at
    submit time.
  - A per-task decision cannot be expressed. Examples: `deleteAllowed == false`
    from `checkChoice`, or a retry after `ElementInUse`. The demo works around
    this by building a second client with deletion off and re-deriving the
    outcome from `choiceOutcomes(false)`.
  - This is the riskiest opt-in in the SDK, and its correct use is the hardest
    to discover.
- **Change:**
  - Keep the client flag as a cap ("deletion may ever be sent").
  - Add client-scoped helpers: `client.choiceOutcomes(task)` and
    `client.work(task)`, or a `ChoiceForm` value returned by the client, so
    the setting is never passed by hand.
  - Add `ChoiceOutcome.withoutDeletion(): ChoiceOutcome?` on both platforms,
    and let `submitChoice` accept that outcome under a deletion-enabled
    client.
  - Optionally let `submitChoice` take the latest `ChoiceEligibility` and
    refuse `deletesElement` when `deleteAllowed` is false.
  - Remove the `allowElementDeletion` parameter from the public `work()` and
    `choiceOutcomes()` extensions, or make it required with no default.
- **Effort:** M. Needs fixture rows in `fixtures/choice.json`.

#### P1-2. Low-level lock and status writes are as easy to call as the safe paths

- **Where:** Kotlin `MapRouletteClient.kt:159-196`; Swift
  `MapRouletteClient.swift:175-209`.
- **Problem:**
  - `startTask`, `refreshTaskLock`, `releaseTask`, `resolveTask` and
    `commitResolution` sit next to `submitChoice` with equal prominence.
  - The design is late locking, and mobile supports only choice tasks:
    `mobileSupport()` is `UNSUPPORTED` for everything else and
    `allowedResolutions()` is always empty. Yet any app can still call
    `commitResolution(id, FIXED)` on a standard task with no OSM edit, or
    lock a task on view with `startTask`.
  - None of these methods checks `mobileSupport()`.
- **Change:**
  - Kotlin: mark them with a `@RequiresOptIn` annotation, e.g.
    `@LowLevelTaskLifecycle`.
  - Swift: move them behind `client.lifecycle.*`, or put them under
    `@_spi(Lifecycle)`, or at minimum add DocC "Warning" callouts.
  - Alternatively, make `commitResolution` reject tasks that are not `IN_PLACE`.
  - Document `submitChoice` and `skipTask` as the supported mobile path.
- **Effort:** S–M.

#### P1-3. Compatibility shims and dead API ship before 1.0

- **Where:**
  - `TaskKinds.kt:185` and `TaskKinds.swift:199`: `allowedResolutions()` always
    returns empty, "kept for source compatibility".
  - `MapRouletteClient.kt:21-26`: a secondary constructor that "retains the
    original positional API".
  - `MapRouletteClient.swift:15-23`: an `apiKey`-only convenience init
    "retained so unlabeled trailing closures keep their meaning".
- **Problem:** Nothing has been released, so nothing needs compatibility.
  These shims enlarge the surface and confuse discovery. With two
  constructors, Kotlin overload resolution behaves differently for trailing
  lambdas. In Swift, the `apiKey` init cannot set `allowElementDeletion`, and
  the designated init requires an `accessToken:` argument even for anonymous
  clients.
- **Change:**
  - Delete `allowedResolutions()`.
  - Make each platform use one primary initializer, with every provider
    optional:
    `MapRouletteClient(serviceURL:, transport:, credentials:, allowElementDeletion:)`.
  - Consider a `Credentials` enum or sealed type
    (`.none | .apiKey(provider) | .accessToken(provider)`). It removes the
    "supplying both fails at request time" check (`MapRouletteClient.kt:490`,
    `.swift:534`) by construction.
- **Effort:** S (deletion) to M (credentials type).

#### P1-4. Production is the default service URL while writes are development-only

- **Where:** `MapRouletteClient.kt:15`, `MapRouletteClient.swift:17,27`;
  `README.md:139-141`; `AGENTS.md`.
- **Problem:**
  - The project rule says writes may target only disposable staging.
  - The SDK defaults to `https://maproulette.org/api/v2/` and has write
    methods enabled. Only the Android demo enforces an allowlist
    (`AppSession.WRITE_ORIGINS`).
  - A new developer copying the README snippet and adding `submitChoice` gets
    production.
  - Bearer sign-in also does not work on stock production
    (`/oauth/mobile/*`), so the default origin cannot serve the main
    authenticated flow anyway.
- **Change:**
  - Add named environments, e.g. `MapRouletteEnvironment.production` and
    `.staging` (`https://mr-api.osm.lol/api/v2/`).
  - While the SDK is pre-release, either require `serviceURL` explicitly, or
    reject write calls to non-allowlisted origins unless the developer passes
    `allowProductionWrites = true`.
  - Put a "Staging vs production" section at the top of the README.
- **Effort:** S–M.

#### P1-5. Swift models cannot be constructed or compared outside the module

- **Where:** `swift/Sources/MapRoulette/Models.swift:121-166,172-174,213-215`:
  `Challenge`, `ChallengeTag`, `MapRouletteTask`, `TaskSummary`,
  `UserIdentity`, `TaskLock` and `Page`. Also `Choice.swift:42,53`
  (`ChoiceResult`, `ChoiceEligibility`) and `TaskKinds.swift:35`
  (`OSMElementRef`).
- **Problem:**
  - Memberwise initializers are internal. App developers cannot build a
    `MapRouletteTask` for SwiftUI previews, unit tests or fakes of their own
    service layer. Kotlin data classes can all be constructed, so the
    platforms differ in practice.
  - `Challenge`, `MapRouletteTask`, `TaskSummary`, `UserIdentity` and
    `MapRouletteError` are not `Equatable`. `TaskID` and `ChallengeID` are not
    `Codable` or `Comparable`, so they are awkward to persist or to use in
    `NavigationPath`.
  - Nothing is `Identifiable`, which SwiftUI `List` and `ForEach` want.
- **Change:**
  - Add public memberwise inits, or a documented `MapRouletteTask.fixture(...)`
    factory in a `MapRouletteTesting` product.
  - Conform the models to `Equatable`/`Hashable` and `Identifiable`.
  - Make the IDs `Codable`, `Comparable` and `CustomStringConvertible`.
  - Change the mutable `public var` model fields (`Models.swift:145-151`) to
    `let`.
- **Effort:** M.

#### P1-6. Validation failures carry no message

- **Where:** Kotlin uses bare `require(...)`, e.g. `MapRouletteClient.kt:30-34`
  (URL), `:48`, `:105`, `:132`, `:505`, and `Models.kt:7,48-52`. Swift uses
  `MapRouletteError(.validation)` with no detail, e.g. `Models.swift:6,93` and
  `MapRouletteClient.swift:38,52,116,145,491,498,534`.
- **Problem:**
  - A wrong base URL gives `IllegalArgumentException: Failed requirement.` on
    Kotlin and `MapRoulette validation` on Swift.
  - So does a page size of 200, a tag containing a comma, a reused
    continuation, or `Bounds` with west > east.
  - The choice validation is the exception: it does have messages
    (`Choice.kt:179-194`), but Swift drops them (`Choice.swift:104-118`).
- **Change:**
  - Add short, credential-free messages to every `require`, e.g.
    `"pageSize must be 1..100"` or
    `"serviceUrl must be https (or http on loopback) with no query"`.
  - Swift: add `public let reason: String?` to `MapRouletteError`, used only
    for `.validation`, and include it in `description`.
- **Effort:** S.

#### P1-7. Kotlin `Continuation` clashes with `kotlin.coroutines.Continuation`

- **Where:** `kotlin/.../Models.kt:146`.
- **Problem:** The README tells users to `import org.maproulette.sdk.*`.
  Coroutine code that also uses `kotlin.coroutines.Continuation`, for example
  `suspendCoroutine` helpers, gets an ambiguous name. Inside the SDK it is
  also a confusing name for a page cursor.
- **Change:** Rename it on both platforms to `PageCursor` or `PageToken`.
- **Effort:** S.

#### P1-8. kotlinx-serialization types are part of the public model

- **Where:** `Models.kt:112-114,130` (`geometry`, `location`,
  `cooperativeWork`, `point` are `JsonObject`); `TaskKinds.kt:37`;
  `build.gradle.kts:11` (`api(...serialization-json:1.7.3)`).
- **Problem:**
  - Consumers inherit a fixed kotlinx-serialization version.
  - Every app must hand-parse `point` (`{lat,lng}`). The demo has
    `TaskPoint.kt` for exactly this.
  - Swift exposes its own `JSONValue`, so the platforms do not match here.
  - 1.7.3 is also behind Kotlin 2.2.
- **Change:**
  - Add typed accessors: `TaskSummary.latitude/longitude` (or a `LatLng`) and
    `Task.location` as a point.
  - Keep the raw JSON for geometry, but consider an SDK-owned `JsonValue`
    wrapper, or at least document the version coupling.
  - Upgrade serialization.
- **Effort:** M.

#### P1-9. Read methods and core types have no API docs

- **Where:**
  - Kotlin methods: `MapRouletteClient.kt:43` (`searchChallenges`), `:62`,
    `:69`, `:78` (`listTasks`), `:93`, `:100` (`findTasksInBounds`), `:463`
    (`getCurrentUser`).
  - Kotlin types: `Challenge`, `Task`, `TaskSummary`, `Page`, `ErrorKind` and
    `MapRouletteException` (`Models.kt:70-131,152-168,264`).
  - Swift: the same methods at `MapRouletteClient.swift:45,63,68,74,85,90,111`,
    and `ErrorKind` and `MapRouletteError`.
  - No Dokka setup and no DocC catalog.
- **Problem:** The write paths are documented thoroughly. The read paths,
  which every developer calls first, are not. Pagination semantics, the
  continuation rules and the error kinds are explained only in the README.
- **Change:**
  - Write KDoc/DocC for every public read method. Cover parameters, the
    page-size cap, continuation binding, which `ErrorKind` values to expect,
    and whether the call needs credentials.
  - Add Dokka (`org.jetbrains.dokka`) and a `Documentation.docc` catalog with
    a "Getting started" article.
  - Publish both to GitHub Pages from CI.
- **Effort:** M.

#### P1-10. The docs contradict each other and the code

- **Where and what is stale:**
  - `README.md:20`: "Task writes … Android demo: Not yet". In fact
    `android-example/README.md:45-90` documents task completion.
  - `README.md:343-345`: "In progress … Remaining: the Android completion
    action". That action is done.
  - `docs/handoff.md:7-9,20-38`: says there is "no … task-completion action"
    and the next step is to "define the lifecycle … before adding writes".
    Both SDKs already have writes. The file is entirely stale.
  - `docs/task-completion.md:3`: "No SDK or backend code has changed yet."
  - `docs/challenge-types.md:3`: "not implemented".
  - `docs/task-completion.md:383-460`: the "Proposed public API" uses the type
    name `LockProblem`. The code uses `WriteProblem`, which is noted only
    further down.
- **Change:**
  - Update the status lines.
  - Delete `docs/handoff.md` or move it out of the repository (the Obsidian
    TASKS.md is the stated source of truth).
  - Add a "Status: implemented in vX; this is a design record" banner to each
    design doc.
  - Add a CI link check.
- **Effort:** S.

#### P1-11. Consumer docs are mixed with internal design records and local paths

- **Where:**
  - `README.md` (357 lines) holds:
    - validation logs ("Validated on a Pixel 8…", `:210-221`);
    - project history (Cantino, `:349-352`);
    - a pointer to a private Obsidian path (`:352`);
    - a note that "The design discussion is local and deliberately excluded
      from Git" (`:348`).
  - The design docs cite local checkouts (`~/dev/maproulette-mobile-backend-public`,
    `~/dev/maproulette3`) and private-repo commits
    (`docs/task-completion.md:10-15`, `docs/mobile-choice-challenges.md:8-10`).
  - `docs/api-probes.json` sits among the consumer docs.
- **Problem:** A new developer cannot tell what they need to read. The
  README's first screen gives no install path and no five-line example.
- **Change:**
  - Split the docs into:
    - `README.md`: what the SDK is, install, quick start, links;
    - `docs/guide/`: authentication, pagination, task completion, choice
      tasks, errors;
    - `docs/design/`: the current design docs and `api-probes.json`;
    - `CONTRIBUTING.md`: setup, tests, the shared-fixture rule.
  - Move the device validation notes to the changelog or the release notes.
  - Replace `~/dev/...` paths with repository URLs.
- **Effort:** M. The onboarding outline is at the end of this review.

#### P1-12. There is no CI

- **Where:** There is no `.github/workflows` or other CI configuration.
  `scripts/check.sh` is the only gate.
- **Problem:** Nothing enforces fixture parity between the platforms, the
  Android build or lint. `scripts/check.sh:4-5` also assumes `java` is on
  `PATH`.
- **Change:**
  - Add a GitHub Actions workflow on `macos-latest` that runs `setup-java@17`
    then `scripts/check.sh`.
  - Add an Ubuntu job for `android-example` running
    `assembleDebug testDebugUnitTest lintDebug`.
  - Once P1-13 exists, run the API-dump check in the same workflow.
- **Effort:** S–M.

#### P1-13. Nothing guards the public API before the first release

- **Where:** `kotlin/build.gradle.kts`. There is no `explicitApi()` and no
  binary-compatibility validator. Swift has no API baseline.
- **Problem:** Accidental public symbols already exist: the example `main`
  (P0-3), and transport types with generic names (`HttpRequest`,
  `HttpResponse`, `HttpMethod`, `Transport`) in the root package.
  - Public Kotlin `data class`es with default arguments (`Task`, `Challenge`,
    `TaskFilter`) break binary compatibility whenever a field is added.
  - On Swift, adding an enum case to `ErrorKind`, `WriteProblem` or
    `ChoiceProblem` breaks client `switch` statements.
- **Change:**
  - Turn on `kotlin { explicitApi() }`.
  - Add `org.jetbrains.kotlinx.binary-compatibility-validator` and commit
    `api/*.api`.
  - Swift: run `swift package diagnose-api-breaking-changes v0.1.0` in CI.
  - Document that `ErrorKind`, `WriteProblem` and `ChoiceProblem` may gain
    cases, so clients should add a default branch.
  - Consider moving the transport seam to `org.maproulette.sdk.transport`.
- **Effort:** S–M.

#### P1-14. Onboarding: `scripts/check.sh` fails without a JDK on `PATH`, and the toolchain is not pinned

- **Where:** `scripts/check.sh:4`; `README.md:145-146,334`. The repository has
  no `.mise.toml` or `.tool-versions`.
- **What happened when I tried it:** On a machine where mise has JDK 17
  installed but not activated, `scripts/check.sh` failed with the macOS
  `/usr/bin/java` stub message "Unable to locate a Java Runtime" (exit 1).
  The message does not mention JDK 17 or mise. I had to work out which JDK to
  activate from the README's mise hint.
- **Change:**
  - Add `.mise.toml` with `java = "temurin-17"` (Swift comes from Xcode).
  - Have `check.sh` check for `java` and `swift` up front and print a clear
    message when either is missing.
  - Document the Xcode/Swift minimum (Swift tools 6.0).
- **Effort:** S.

#### P1-15. No guidance on getting an OAuth client ID or a staging account

- **Where:** `README.md:19-33,257-291`; `android-example/README.md:12-20`.
- **Problem:** The docs explain that bearer sign-in needs the patched backend.
  They do not tell an app developer:
  - how to get a client ID registered on `mr-api.osm.lol`, or whom to ask;
  - how to get a dev-OSM account (`master.apis.dev.openstreetmap.org`);
  - which scopes to request (`tasks:read tasks:write osm:tagfix`, buried at
    `android-example/README.md:53`);
  - that Swift has no sign-in helper and the app must implement PKCE itself.
- **Change:** Add an "Authentication setup" guide that covers:
  - the API key vs bearer decision table;
  - a staging checklist: dev-OSM account, client ID request, redirect URI,
    scopes;
  - a minimal AppAuth-iOS snippet for Swift.

  Link it from both READMEs.
- **Effort:** S–M.

#### P1-16. The "Too hard" label is hard-coded in English

- **Where:** `Choice.kt:66`; `Choice.swift:93`.
- **Problem:** The built-in outcome has `label = "Too hard"`, so apps cannot
  localize it without special-casing the internal id `too-hard`. That id is
  `internal`/module-private (`Choice.kt:60`).
- **Change:** Expose the id as a public constant (`ChoiceOutcome.TOO_HARD_ID`)
  or an `isBuiltInTooHard` property, and document that the label is a
  fallback.
- **Effort:** S.

### P2: nice to have

| # | Where | Finding | Suggested change | Effort |
| --- | --- | --- | --- | --- |
| P2-1 | `Transport.kt:35-43`; `Transport.swift:41-49` | Transports cannot be configured. Apps cannot reuse their own `OkHttpClient` or `URLSession`, add interceptors or pinning, or set timeouts. Timeouts differ: Kotlin uses a 30 s call timeout, Swift 30 s per request and 60 s per resource. | Add `OkHttpTransport(client: OkHttpClient)` and `URLSessionTransport(configuration:)`, enforcing no-redirect and no-retry on what is passed in. Align the timeouts or document the difference. | S |
| P2-2 | `MapRouletteClient.kt:585`; `.swift:603` | Apps cannot add their own name to the User-Agent. MapRoulette operators cannot tell apps apart. | Add a `userAgentSuffix` parameter, e.g. `"MyApp/1.2"`. | S |
| P2-3 | `Models.kt:65`, `Models.swift:107` | `TaskFilter.statuses` is `List<Int>` while the models use `TaskStatus`. `UserIdentity.id`, `verifyResolution(me:)`, `lockedBy` and `completedBy` are raw `Long`/`Int64`. | Use `TaskStatus` in the filter, with named constants (`TaskStatus.CREATED`, …). Add a `UserId` value type. | S–M |
| P2-4 | `Models.kt:267`, `Models.swift:222` | `retryAfter` is a raw string. | Add a parsed `retryAfterSeconds`/`Duration` alongside it. | S |
| P2-5 | `Models.swift:5,12,19` | ID initializers `throw`, so every literal needs `try` (`try ChallengeID(16441)`). | Add a failable `init?` or `ExpressibleByIntegerLiteral` with a precondition, and keep the throwing init. | S |
| P2-6 | `TaskKinds.kt:6,44` vs `TaskKinds.swift:4-6,41` | Small conceptual differences: Swift `CooperativeType.unknown(Int)` and `ElementTagEdit.Kind.unknown(String?)` carry the raw value, Kotlin `UNKNOWN` does not. Swift nests `OSMElementRef.ElementType`, Kotlin has a top-level `OsmType`. | Carry the raw value on Kotlin too (a sealed class or a separate property). | S |
| P2-7 | `Task.work()`, `mobileSupport()`, `canSkip()`, etc. | These are top-level extension functions. IDE discovery via `task.` works, but they are not grouped in the API docs, and `work` is a generic name. | Group them in docs under "Task kinds". Consider `task.kind` (a property) and `task.mobileSupport` (a property). | S |
| P2-8 | `README.md:162-166` | No minimum Android API level is stated for the SDK itself, only for the demo (`minSdk 26`). The SDK uses OkHttp 4.12 (API 21+) and Java 17 bytecode, which needs AGP 8+ and desugaring considerations. | Add a "Requirements" table: JDK 17 to build, Android API 21+ (or whatever is tested), AGP 8.x, iOS 15 / macOS 12, Swift 6 / Xcode 16+. | S |
| P2-9 | Repository root | No `CONTRIBUTING.md`, issue templates or `SECURITY.md`. `AGENTS.md` holds the real contributor rules: the shared-fixture rule, the no-writes rule and the staging-only rule. | Move the human-relevant rules into `CONTRIBUTING.md` and `SECURITY.md` (credential handling, how to report a leak). | S |
| P2-10 | `fixtures/README.md:39-41` | The README is accurate, but says nothing on how to add a case (for example "add a row, then add a test on both platforms"). | Add a three-step "adding a fixture case" section. | S |
| P2-11 | `Package.swift` | No `swift-docc-plugin` and no `swiftLanguageModes`/`StrictConcurrency` statement. The tests use `nonisolated(unsafe)` globals (`ChoiceTests.swift:8`). | Add the DocC plugin when doing P1-9. | S |
| P2-12 | `docs/mobile-choice-challenges.md:738-750` (uncommitted) | The partial-answer limitation matters to integrators, because it closes the task, but it is buried in a 750-line spec. | Repeat it in the consumer guide and in the `ChoiceSubmission.Answers` KDoc/DocC. | S |

## What works well (keep it)

- The write semantics are documented exactly where the methods are declared:
  `OutcomeUnknown`, never resending blindly, release behavior. That is rare
  and valuable.
- One error type with a `kind` plus a typed `problem`, and no credentials or
  bodies in descriptions.
- Credential providers are evaluated per request, so token rotation needs no
  client rebuild.
- Shared JSON fixtures are loaded directly by both test suites.
- Explicit pagination with tokens bound to their query; callers cannot misuse
  them silently.
- `TaskFilter.choiceOnly` and `mobileSupport()` give a clear double gate.

## Onboarding trial log

| Step | Result |
| --- | --- |
| Find install instructions | None for remote consumption. Kotlin uses a composite build with an absolute path. Swift says "local package". (P0-1, P0-2) |
| Prerequisites | JDK 17 is mentioned. The Swift/Xcode version appears only in passing ("Swift 6", `README.md:334`). The Android SDK is described in the demo section. No pinned toolchain. (P1-14) |
| `scripts/check.sh` with no JDK on `PATH` | Failed (exit 1) with the macOS "Unable to locate a Java Runtime" stub message, which gives no hint about JDK 17. (P1-14) |
| `scripts/check.sh` with JDK 17 from mise | **Not completed.** My sandbox could not write `~/.gradle` or the SwiftPM caches, so neither suite ran in this session. This was an environment limit, not a repository defect. The work log reports Kotlin 45, Swift 41 and app 49 tests passing on 2026-10-06. Someone should re-run `scripts/check.sh` before acting on this review. |
| Run the examples | Not attempted. They make live reads against production `maproulette.org`. Reads are allowed, but the sandbox blocks the needed caches. |
| Find an OAuth client ID | No path documented. (P1-15) |
| Decide between staging and production | Spread across `README.md:29-33,139-141` and the Android README. No single section. (P1-4) |
| Contributing | No `CONTRIBUTING.md`. The rules live in `AGENTS.md`. (P2-9) |

## Release-readiness checklist (DX angle)

| Item | State |
| --- | --- |
| License | Apache-2.0 `LICENSE` present. The README notes the OSM data licensing caveat. Good. Add `NOTICE` only if required. |
| Versioning | Missing (P0-4). |
| Changelog | Missing (P0-4). |
| Distribution | Missing on both platforms (P0-1, P0-2). |
| Minimum platforms | Swift: iOS 15 / macOS 12 (`Package.swift:6`). Kotlin/Android: not stated (P2-8). |
| API docs generation | No Dokka and no DocC (P1-9). |
| API stability tooling | None (P1-13). |
| CI | None (P1-12). |
| Example apps | Android present. iOS in progress elsewhere. The JVM and Swift CLI examples ship inside the library (P0-3). |

## Proposed onboarding doc outline

A new `docs/guide/getting-started.md`, linked from the top of `README.md`:

1. **What this SDK does and does not do.** Five bullets: reads, choice-task
   completion, no OSM writes by the SDK itself, no map UI, no offline support.
2. **Requirements.** A table per platform: JDK 17, Android API level, AGP,
   Swift 6 / Xcode, iOS 15 / macOS 12.
3. **Install.**
   - Gradle: `implementation("<group>:maproulette-mobile-sdk:<version>")`, with
     the repository block.
   - SwiftPM: `.package(url: "…", from: "0.1.0")` and the Xcode steps.
4. **Pick an environment.** Production (reads only during development) vs
   staging `mr-api.osm.lol` (dev OSM, separate database). Why writes must
   target staging. Named environment constants.
5. **First call in five minutes.** An anonymous `searchChallenges` and
   `getTask` in Kotlin (coroutine scope, closing the transport) and Swift
   (`Task {}`), with expected output.
6. **Authentication.**
   - Decision table: anonymous, personal API key, bearer (patched backend
     only).
   - Getting a staging client ID and dev-OSM account: who to ask, the redirect
     URI, the scopes.
   - Android with AppAuth (link the demo). iOS with AppAuth-iOS or
     `ASWebAuthenticationSession` (snippet).
   - Credential providers, rotation, logout, account switching.
7. **Discovering tasks.** Pagination and cursors, bounds search vs markers,
   `choiceOnly`, `mobileSupport()`.
8. **Completing a choice task.**
   - The flow: `checkChoice` on open, render questions and outcomes, Can't
     tell, `submitChoice`, re-read.
   - The deletion opt-in and the "gone without deletion" fallback.
   - The partial-answer limitation.
9. **Handling errors.**
   - An `ErrorKind` table.
   - A `WriteProblem`/`ChoiceProblem` table with the user-facing message and
     the app action for each.
   - `OutcomeUnknown` recovery and `verifyResolution`.
   - Cancellation.
10. **Testing your app.** Custom `Transport` fakes, constructing models in
    tests and previews, the shared fixtures as examples.
11. **Running the examples.** The Android demo (Gradle properties for staging),
    the iOS demo, the CLI examples.
12. **Contributing.** `.mise.toml`, `scripts/check.sh`, the shared-fixture
    rule, CI, the no-production-writes rule, and where design docs live.
13. **Reference.** Links to Dokka and DocC, `docs/design/*`, the changelog.
