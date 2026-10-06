# iOS example

A SwiftUI app that uses the Swift SDK as a local package (the repository root, `..`, where
`Package.swift` lives) and matches the
[Android example](../android-example/README.md) in behavior: the same screens, states,
wording and write rules. It has no third-party dependencies (MapKit for the map,
`ASWebAuthenticationSession` for sign-in, the Keychain for tokens).

The default build browses `maproulette.org` anonymously. **Sign-in needs the
[patched MapRoulette backend](https://github.com/mvexel/maproulette-mobile-backend/tree/feat/mobile-oauth)
with its mobile OAuth provider**, just like on Android.

## Build and run

Requires Xcode 26 or later (the project uses folder-synchronized groups, objectVersion 77)
and an iOS 17+ simulator. No project generator is needed: the checked-in
`MapRouletteExample.xcodeproj` is complete, and new Swift files in `MapRouletteExample/` are
picked up automatically.

The quickest check, from the repository root, builds the app and runs its unit tests on the
first available iPhone simulator (CI runs the same script):

```sh
scripts/ios-test.sh
```

By hand:

```sh
cd ios-example
# Unit tests (controller states, wording, scope/origin/callback rules; fake ops, no network)
xcodebuild -project MapRouletteExample.xcodeproj -scheme MapRouletteExample \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
# Build, install and launch on a booted simulator
xcodebuild -project MapRouletteExample.xcodeproj -scheme MapRouletteExample \
  -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath ../tmp/ios-dd build
xcrun simctl install booted ../tmp/ios-dd/Build/Products/Debug-iphonesimulator/MapRouletteExample.app
xcrun simctl launch booted org.maproulette.example
```

Or open the project in Xcode and run the `MapRouletteExample` scheme.

### Configure a staging sign-in build

Backend settings are build settings (`Config/Example.xcconfig`), read only by Debug builds.
Release builds always use anonymous `maproulette.org` reads, like the Android release build.
They are public identifiers and URLs, not credentials:

```sh
xcodebuild ... build \
  MAPROULETTE_BASE_URL=https://mr-api.osm.lol \
  MAPROULETTE_OAUTH_CLIENT_ID=maproulette-ios-example
```

The backend must list the client with this exact native callback (the same one as Android):

```text
org.maproulette.example:/oauth2redirect
```

The staging backend (`conf/mobile-staging.conf`) currently registers only
`maproulette-android-example`. Until an iOS client is added there, a debug build can use
`MAPROULETTE_OAUTH_CLIENT_ID=maproulette-android-example`: the callback is identical.

To get a client ID and a development OSM account, see the
[staging checklist](../docs/guide/authentication.md#staging-checklist-bearer-sign-in).

`MAPROULETTE_ALLOW_LOOPBACK=YES` allows `http://127.0.0.1` / `http://localhost` origins for
local backends (Debug only).

## Behavior

Everything in the Android README's *Task completion* and *Session behavior* sections applies.
In short:

- **Discovery**: the map reads with `cct=3&excludeStale=true`; the challenge list keeps only
  `inPlace` tasks and says how many were hidden. A non-choice task opened by id says
  **Not available on mobile**.
- **Task screen**: opening never locks. A write-enabled session calls `checkChoice`.
  Ineligible: **This one no longer needs answering** with **Next task**. Failed check:
  **Couldn't check this right now** with Retry and Next task. Otherwise each question is a
  section whose options show their exact tag change (`backrest=yes`) in monospace, plus
  **Can't tell** (the default). **Submit answers** needs one answer and confirms the exact tag
  changes, noting that staging edits development OSM. Task-level outcomes and Skip are
  separate buttons with explanations.
- **Allow deleting OSM elements** (Home, writable builds only, default off) sets the one SDK
  client's `allowElementDeletion`. Off, "gone" is Not an issue; on, it deletes when the check
  reports `deleteAllowed`, otherwise the app submits `outcome.withoutDeletion()`; after
  `element_in_use` the app offers the same outcome without deletion.
- **Submission** runs the SDK's late-locking `submitChoice` in a detached task, so it finishes
  even if the screen closes; the back button is hidden meanwhile. Done re-reads the task and
  links the changeset on development OSM. Uncertain results show **Check again** (read only);
  the identical submission is resent only after an explicit confirmation. Lists and the map
  reload after a task changed.
- **Write guard**: requests go through a transport that refuses every lifecycle write URL
  (`task/{id}/start|refreshLock|release|skip|choice|{status}`) unless the origin is exactly
  `https://mr-api.osm.lol` (or allowed loopback) and the grant has `tasks:write`. The SDK
  enforces the same origin rule itself before 1.0 (`MapRouletteEnvironment.allowsWrites`).
- **Sign-in**: authorization code + S256 PKCE in an *ephemeral* web authentication session (no
  shared Safari cookies, so each sign-in shows the OSM login and can pick an account). Scope
  `tasks:read tasks:write osm:tagfix` only for the staging origin, otherwise `tasks:read`.
  Tokens are stored in the Keychain (`AfterFirstUnlockThisDeviceOnly`), one item per origin and
  client ID. Refresh is serialized and persists a refresh-pending marker first; an
  interrupted or failed refresh signs out. Sign-out clears locally, then revokes. A 401 triggers
  one forced refresh unless it is `osm_reauth_required`. Account switches discard the task
  screen's state and say when a result was in flight. Logs carry error types only.

## Demo mode and screenshots

Debug builds accept `-demo`: an in-process mock backend (`Demo/DemoBackend.swift`) signed in
as user 7, with challenge 3 and tasks 184–189 covering each state (eligible, node in a way,
stale, failed check, locked by someone else with `element_in_use`, not a choice task).
Nothing leaves the device. `-demoTask <id>` and `-demoMap` open a screen directly.

`scripts/screenshots.sh` runs the `Screenshots` scheme (an XCUITest that drives these flows)
and saves the PNGs to `tmp/shots/ios/`. With `LIVE=1` and a staging build it also captures
anonymous read-only shots of challenge 3.

## Not verified here

Browser sign-in, Keychain restore, refresh and revocation need a real backend client
registration and a development OSM account; they are covered by code review and the rule
tests, not by a device run.
