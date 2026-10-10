# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

In SDK 0.x: a minor version (0.x.0) may break the API; a patch version (0.x.y)
does not. `ErrorKind`, `WriteProblem` and `ChoiceProblem` may gain cases in any
minor version, so add a default branch when you switch on them.

## [Unreleased]

### Added

- Swift: guest calls for deferred sign-up (backend `mobileOAuth.guests`):
  `submitPendingChoice`, `withdrawPendingChoice`, `listPendingChoices`,
  `getGuestStatus`, `setGuestEmail` and `deleteGuest`, with `GuestStatus`,
  `PendingChoice` and related types. Pending answers never edit OSM; a "gone"
  outcome is stored without deletion. Guest errors carry the server's code in
  `MapRouletteError.reason`. Kotlin follows.
- Swift: `TaskFilter.excludePending` (default true) leaves out tasks held by a
  guest's pending answer in choice-only searches.
- Kotlin: the same guest calls and types, with errors carrying the server's code
  in `MapRouletteException.reason`, and `TaskFilter.excludePending`.
- Kotlin: `MobileSignIn`, the counterpart of the Swift helper: PKCE sign-in,
  serialized refresh, revocation, restore, and guests (`startGuest`,
  `upgradeGuest`, `forgetGuest`). It stores through a `CredentialStore`;
  `InMemoryCredentialStore` is included, and Android apps supply an encrypted
  store. A client from an older sign-in fails with `CancellationException`.

### Changed

- Swift: `HTTPMethod` gains `.delete`. Exhaustive switches over it need the
  new case.
- Kotlin: `HttpMethod` gains `DELETE`, and `MapRouletteException` gains
  `reason`. Exhaustive `when` over `HttpMethod` needs the new case.

- Swift: `MobileSignIn`, an optional browser sign-in helper for mobile-enabled
  backends. It runs authorization code with S256 PKCE, checks the callback and
  the grant, stores it through a `CredentialStore` (`KeychainCredentialStore`,
  `InMemoryCredentialStore`), refreshes one token at a time, signs out safely
  after an interrupted or failed refresh, and revokes on sign-out. It is UI-free:
  the app passes in the web authentication session. `client()` returns a client
  bound to the signed-in account. See the
  [authentication guide](docs/guide/authentication.md#ios-mobilesignin).

- Opt-in writes for your own backend: `MapRouletteEnvironment(serviceUrl,
  allowWrites = true)` in Kotlin, `MapRouletteEnvironment(serviceURL:allowWrites:)`
  in Swift. The default stays `false`, so existing calls compile unchanged.
  `allowsWrites` is now true for staging, loopback hosts, and opted-in hosts.
  Opting in for `maproulette.org` or a subdomain fails at construction; production
  stays read-only.

### Changed

- Documentation: the README's "Environments and backends" section and the
  getting-started guide say what works where. Reads work against any deployment,
  anonymously too; only choice tasks can be completed, and only against a
  mobile-enabled fork backend. The authentication guide has a "Your own backend"
  section.

- Partial answers closing the task is now the intended behavior (decision D7),
  no longer a known limitation. A submission that leaves some questions as
  "Can't tell" sets Fixed, as in upstream MapRoulette. Challenge maintainers
  re-check live OSM and create new tasks for keys that are still missing. See
  the [choice-tasks guide](docs/guide/choice-tasks.md#partial-answers-close-the-task).
- Documentation: the staging checklist lists the example client IDs for
  development, and the iOS example uses its own staging client,
  `maproulette-ios-example`.

## [0.1.0] - 2026-10-06

First release of the Kotlin and Swift SDKs.

### Added

- Kotlin and Swift clients for the MapRoulette API reads: challenge search,
  challenges and their tags, task lists, tasks, bounding-box search with
  totals, map markers and the current user. Explicit pages with opaque
  `PageCursor`s, page size 1 to 100.
- Per-user credentials through providers that run per request: a personal API
  key, or a bearer token from the fork backend's mobile sign-in.
- Skipping tasks with `skipTask`.
- Multiple-choice task completion with late locking: `checkChoice` on open,
  `submitChoice` for answers or an outcome, a built-in "Too hard" outcome, and
  element deletion behind the `allowElementDeletion` cap with a per-task
  `withoutDeletion()` fallback.
- Task kinds, mobile support (`mobileSupport()`, `canSkip()`), instructions and
  template properties, and `verifyResolution` for recovering from unknown
  outcomes.
- Named environments (`PRODUCTION`/`.production`, `STAGING`/`.staging`, custom)
  and a write allowlist: before 1.0 the SDK sends task writes only to staging and
  loopback hosts.
- Typed errors: an `ErrorKind` plus a `WriteProblem` or `ChoiceProblem` on write
  failures, and short validation messages.
- Low-level lifecycle writes (`startTask`, `refreshTaskLock`, `releaseTask`,
  `resolveTask`, `commitResolution`) behind an opt-in:
  `@OptIn(LowLevelTaskLifecycle::class)` in Kotlin,
  `@_spi(LowLevelTaskLifecycle) import MapRoulette` in Swift.
- Public initializers, `Hashable` and `Identifiable` conformances on the Swift
  models, for previews and tests.
- Shared JSON fixtures that both test suites load.
- Android and iOS example apps with map discovery, browser sign-in and the
  choice task screen.
- Distribution: Kotlin through JitPack
  (`com.github.mvexel:maproulette-mobile-sdk:0.1.0`), Swift through SwiftPM
  (product `MapRoulette`). One version source in `VERSION`.
- CI for both SDKs, the Android demo and the iOS demo. Dokka HTML API docs for
  Kotlin.

### Known limitations

- Partial answers close the task: answering some questions and leaving others
  as "Can't tell" sets Fixed, and the unanswered questions are lost.
- Bearer sign-in (`/oauth/mobile/*`) and the choice routes need the fork backend,
  which runs only on staging.
- Before 1.0, the SDK writes only to staging (`https://mr-api.osm.lol`) and
  loopback hosts.
- No DocC catalog for the Swift SDK yet.

[Unreleased]: https://github.com/mvexel/maproulette-mobile-sdk/compare/0.1.0...HEAD
[0.1.0]: https://github.com/mvexel/maproulette-mobile-sdk/releases/tag/0.1.0
