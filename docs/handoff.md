# Handoff: mobile task completion next

The native Kotlin and Swift SDKs support challenge/task discovery, task detail,
map markers and per-user authenticated reads. Both accept a user's personal
MapRoulette API key on an existing backend, or a bearer token from a backend
with the [mobile OAuth patch](https://github.com/mvexel/maproulette-mobile-backend/tree/feat/mobile-oauth).
The Android demo adds a MapLibre map and browser sign-in for the patched
backend. It has no API-key entry screen or task-completion action. Swift accepts
a token provider but has no browser sign-in UI.

The patched backend is deployed for testing at `https://mr-api.osm.lol`, with
development OSM accounts and a separate MapRoulette database. Its Salt Lake City
bench challenge is ID `1` and contains 168 tasks for benches missing a
`backrest` tag. On 2026-10-05, a Pixel 8 completed real development-OSM sign-in
and read nearby staging tasks. The deployed containers were built from backend
commit `e23263b`; the subsequently published, clean-history backend fork has
not been redeployed or acceptance-tested on that server. The demo's default
build points at `maproulette.org` and has sign-in disabled.

## Next implementation slice

**Enable task completion on mobile.** Start by checking actual backend routes
and source behavior for task start/lock, completion or other resolutions, and
release. The Swagger definitions are known to differ from deployed behavior;
some GET routes mutate task state. Define the lifecycle, ownership, conflict,
retry and cancellation behavior before adding writes to either SDK.

Extend the patched backend's per-user mobile grant beyond its current
`tasks:read` scope only for the operations needed. Preserve the old API-key and
web flows, and never use a shared app API key. Keep MapRoulette task resolution
distinct from editing OSM data; OSM upload needs its own design and permission.

Add matched Kotlin and Swift write contracts and tests, then an Android demo
action that shows the resulting state and handles expired login, lock conflicts,
network interruption and account switching. Test against disposable staging
tasks before using a real bench task. Accept the slice when a signed-in user can
complete a task on a physical Android device and a fresh read confirms the
correct user and status, with no change after a rejected or interrupted write.

Use `scripts/check.sh` for both SDK suites. For the Android build, tests and
lint, run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
from `android-example/` with a configured Android SDK. The canonical
append-only work log is
`~/obsidian/agent/maproulette-mobile-sdk/TASKS.md`; it records the backend,
infra and device checks from this session.
