# Android example

The default build browses the deployed MapRoulette API anonymously. **The Sign
in button requires the [patched MapRoulette backend](https://github.com/mvexel/maproulette-mobile-backend/tree/feat/mobile-oauth)
with its mobile OAuth provider enabled.** A normal MapRoulette API deployment
or an OSM OAuth application by itself cannot serve the `/oauth/mobile/*`
endpoints used by this app. Sign-in is disabled until a debug build specifies
an approved client ID and that backend's origin. The example uses AppAuth's
browser authorization-code flow with S256 PKCE; it does not embed a personal
API key or client secret.

## Build and run

Open this directory in Android Studio, or build and install on a connected device:

```sh
mise exec -- ./gradlew :app:assembleDebug
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n org.maproulette.example/.MainActivity
```

Configure the Android SDK in Android Studio or through `ANDROID_HOME` first. The
app compiles against SDK 36 and runs on Android 8.0 (API 26) or later. It lists a
challenge's tasks, shows nearby tasks on a MapLibre map with the
[OpenFreeMap Liberty style](https://openfreemap.org/quick_start/), and opens task
details. Map rendering and location belong to the app, not the SDK.

## Configure a debug sign-in build

Deploy the mobile backend patch and follow its
[mobile OAuth configuration guide](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-oauth.md).
Enable `mobileOAuth`, register `maproulette-android-example` as a public client,
and configure the backend's OSM OAuth client credentials and callback. The
Android app's client ID is a public identifier; the OSM client secret stays on
the backend. The tested staging backend is `https://mr-api.osm.lol` and uses
development OSM accounts with a separate MapRoulette database. To use it, you
need a development OSM account and a client ID on that backend; see the
[staging checklist](../docs/guide/authentication.md#staging-checklist-bearer-sign-in).

Register this exact native callback with the backend's mobile OAuth configuration:

```text
org.maproulette.example:/oauth2redirect
```

The app consumes the SDK from `../kotlin` through a Gradle composite build. Java
comes from mise (`mise install` at the repository root pins JDK 17); prefix
Gradle commands with `mise exec --` if mise is not activated in your shell.

Build against an HTTPS test backend:

```sh
./gradlew :app:assembleDebug \
  -PmaprouletteBaseUrl=https://backend.example \
  -PmaprouletteOAuthClientId=maproulette-android-example
```

For the tested staging deployment, replace `https://backend.example` with
`https://mr-api.osm.lol`. Install the resulting debug APK, tap **Sign in**, and
authorize with a development OSM account. The staging pilot is choice challenge
`3` (the older bench challenge `1` is not a choice challenge and is hidden on
mobile). Open the map around Salt Lake
City and tap **Search this area** to inspect nearby tasks. A plain debug build without
these Gradle properties uses anonymous `maproulette.org` reads and disables
sign-in.

## Task completion (multiple-choice tasks)

Mobile shows only multiple-choice tasks ([spec](../docs/design/mobile-choice-challenges.md)). The
map and the challenge list read with `cct=3&excludeStale=true`; the list also
drops every task that is not `IN_PLACE` on the client. A non-choice task opened
by id says **Not available on mobile**. The staging pilot is challenge `3`
(8 disposable benches on development OSM around Library Square, Salt Lake City).

A sign-in build requests `tasks:read tasks:write osm:tagfix`; the backend may
grant less. A session whose grant lacks `tasks:write` or `osm:tagfix` (for
example one from before choice support) shows **Sign in again to enable
editing**. Opening a task never locks it. For a write-enabled session the app
calls `checkChoice`. An ineligible task says **This one no longer needs
answering** and offers only **Next task** (the next one from the list or map)
and Back. A failed check says **Couldn't check this right now** with Retry and
Next task; the task is not actionable until a check succeeds. Otherwise each
question is a card: every option shows its exact tag change (`backrest=yes`)
under the label, and **Can't tell** leaves the question out. **Submit answers**
is enabled once one question is answered and confirms the exact tag changes,
noting that the staging build edits development OSM. Task-level outcomes (for
the pilot: Not a bench, Bench is gone, Too hard) and Skip are separate buttons.

**Allow deleting OSM elements** on the main screen is a demo setting, default
off. It sets the SDK client's `allowElementDeletion`; the app uses one client.
Off, "Bench is gone" is recorded as Not an issue. On, it deletes the node
when the check reports `deleteAllowed`; otherwise (the node is in a way or
relation) the app submits `outcome.withoutDeletion()`, recorded as Not an issue,
and the confirmation says so. If OSM still refuses the delete (`element_in_use`),
the app offers the same outcome without deletion after a confirmation.

Submission runs the SDK's late-locking `submitChoice` off the main thread; the
write finishes even if the screen closes and Back is blocked meanwhile. On
success the app re-reads the task and shows its status, who completed it and the
changeset, with a link to it on development OSM. An unknown outcome, a pending
submission or a pending status shows **Check again**, which only re-reads; an
edit is never resent automatically. OSM re-consent or a missing scope asks to
sign in again; OSM being unavailable keeps the answers. Lock conflicts ask to
pick another task. The map and list refresh after a submission.

Writes are possible only against an allowlisted disposable backend: exactly
`https://mr-api.osm.lol`, or loopback in a debug build with
`-PmaprouletteAllowLoopback=true` (`AppSession.WRITE_ORIGINS`). The SDK enforces
the same rule itself before 1.0: it refuses every task write on other
environments before sending anything. For any other
origin, including `maproulette.org`, the app requests only `tasks:read` and
shows no actions. The session transport also refuses every lifecycle write URL
(`task/{id}/start|refreshLock|release|skip|choice|{status}`) before sending
unless the origin is allowlisted and the current grant includes `tasks:write`,
so a UI bug cannot send one. The default build shows no task actions.

**Choosing an OpenStreetMap account.** OSM keeps you signed in in the browser,
so a normal browser tab silently reuses that OSM account. Sign-in therefore
uses an ephemeral Custom Tab (Chrome 136+), which shares no cookies, so each
sign-in shows the OSM login. Other browsers fall back to a normal tab; the app
then explains how to sign out of OSM in the browser. (The backend does not
forward `prompt=login` to OSM, and OSM honors it only for OpenID Connect
requests.)

The base URL must be an origin, without a path, query, fragment or credentials. The SDK's API URL and all authentication endpoints use that origin. Configuration properties are public identifiers and URLs, not credentials.

For local testing with an explicitly selected device and `adb reverse`, the debug build alone can enable cleartext traffic to exact loopback hosts:

```sh
./gradlew :app:assembleDebug \
  -PmaprouletteBaseUrl=http://127.0.0.1:9000 \
  -PmaprouletteOAuthClientId=maproulette-android-example \
  -PmaprouletteAllowLoopback=true
```

The generated debug network policy permits only `127.0.0.1` and `localhost`. The application additionally restricts authentication connections to the configured origin and does not follow token/revocation redirects. Release builds keep the production anonymous defaults and do not include the loopback exception.

The backend must separately register its own callback with OSM. A local synthetic OSM provider can test mechanics, but does not establish that real OSM sign-in works.

## Session behavior

MainActivity and the nearby map share an app-owned session. Successful sign-in reads the credential-free identity endpoint before publishing the session. AuthState is encrypted using an Android Keystore AES/GCM key, with storage separated by backend origin and client ID. It survives app restart when decryption succeeds.

Refresh is serialized. A persisted uncertainty marker is written before a refresh request; the rotated state is committed before its access token is returned. If refresh fails or the process dies during rotation, the app requires sign-in again instead of retrying a possibly consumed refresh token.

Sign-out clears local credentials and closes old SDK clients before attempting grant revocation. If the network is unavailable, the UI reports that server revocation is unconfirmed. Requests and browser callbacks from an earlier session cannot install credentials into a newer session. A 401 on a bearer-authenticated SDK request invalidates the local session.

This is an app-owned first integration, not a reusable authentication package. It has no account list or background synchronization. Signing out or switching accounts discards the task screen's state; a result that arrives for the previous account is never shown.

## Checks

```sh
mise exec -- ./gradlew assembleDebug testDebugUnitTest lintDebug
```

CI runs the same command. The unit tests cover task-point mapping, origin, loopback and callback validation, scope selection, the confirmation text, and the choice task screen's state machine including the deletion setting (fake SDK operations; no network). Browser callbacks, Keystore persistence, refresh and logout additionally require device testing with a configured backend.
