# Android example

The default build browses the deployed MapRoulette API anonymously. Sign-in is disabled until a debug build specifies an approved client and an OAuth-enabled backend. The example uses AppAuth's browser authorization-code flow with S256 PKCE; it does not embed a personal API key or client secret.

## Configure a debug sign-in build

Register this exact native callback with the backend's mobile OAuth configuration:

```text
org.maproulette.example:/oauth2redirect
```

Build against an HTTPS test backend:

```sh
./gradlew :app:assembleDebug \
  -PmaprouletteBaseUrl=https://backend.example \
  -PmaprouletteOAuthClientId=maproulette-android-example
```

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

This is an app-owned first integration, not a reusable authentication package. It has no account list, background synchronization or task-editing controls.

## Checks

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

The unit tests cover task-point mapping and origin, loopback and callback validation. Browser callbacks, Keystore persistence, refresh and logout additionally require device testing with a configured backend.

On 2026-10-05, a physical Pixel 8 completed browser authorization and consent against a local backend with a **synthetic OSM provider**, displayed the authenticated user, restored the encrypted session after force-stop/relaunch, and signed out with server revocation confirmed. This verifies that integration path; it is not a real OSM login result.

Pixel 8 acceptance also verified browser cancellation and refresh with a local
30-second access lifetime. A second refresh succeeded after process restart,
confirming that the rotated refresh token was saved; logout then confirmed
server revocation. The isolated database contains no public challenges, so the
authenticated read correctly returned a missing-challenge response.
