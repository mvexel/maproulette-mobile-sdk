# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

Before 1.0: a minor version (0.x.0) may break the API; a patch version (0.x.y)
does not. `ErrorKind`, `WriteProblem` and `ChoiceProblem` may gain cases in any
minor version, so add a default branch when you switch on them.

## [Unreleased]

### Changed

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
