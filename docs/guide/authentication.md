# Authentication

Back to [Getting started](getting-started.md).

## Which credential?

| | Anonymous | Personal API key | Bearer token (browser sign-in) |
| --- | --- | --- | --- |
| Backend | Any MapRoulette | Any MapRoulette | Fork backend only (staging) |
| How the user gets it | Nothing to do | Copies it from their MapRoulette profile | Signs in with OSM in the browser |
| Reads | Yes | Yes | Yes (`tasks:read`) |
| `getCurrentUser` | No | Yes (`user/whoami`) | Yes (`oauth/mobile/me`, with scopes) |
| Skip | No | Yes | With `tasks:write` |
| Choice answers and deletes | No | No (the route accepts only mobile bearer tokens) | With `tasks:write` and `osm:tagfix` |
| SDK parameter | none | `apiKey` | `accessToken` |

Never bundle a shared API key with an app. A key is personal and acts with the
user's full authority. Never pass an OSM access token or a client secret as a
MapRoulette credential.

For an app that completes choice tasks, use bearer sign-in on staging.

## Credential providers

The client takes a provider, not a value. It runs the provider before every
request, so a refreshed token or a sign-out takes effect on the next request.
No client rebuild is needed.

```kotlin
val client = MapRouletteClient(
    environment = MapRouletteEnvironment.STAGING,
    transport = transport,
    accessToken = { session.currentAccessToken() }, // null when signed out
)
```

```swift
let client = MapRouletteClient(
    environment: .staging,
    accessToken: { try await session.currentAccessToken() })  // nil when signed out
```

Rules:

- Pass providers by name (`apiKey =` / `accessToken:`).
- Supply one kind. If both providers return a value, the request fails with a
  validation error. A rejected bearer token never falls back to an API key.
- Errors thrown by your provider reach the caller unchanged.
- Return `null` / `nil` when signed out. The next request goes out anonymously.

Check a new credential with `getCurrentUser()`. A bearer identity reports
`scopes`; `canWriteTasks` tells you whether it has `tasks:write`, and
`canEditOsm` whether it can submit choice answers (`osm:tagfix`). API-key
identities have `scopes == null`, `canWriteTasks == true` (unless the user is
a guest) and `canEditOsm == false`. If a grant lacks a scope you need, ask the user to sign
in again.

Never log credentials, and never log the raw `user/whoami` response: it
contains the user's API key. The SDK keeps both out of its own error text.

## Sign-out and account switching

Sign-out is local: clear the stored credential, so the provider returns
nothing. Revoking a bearer grant on the server is a separate call your app
makes (`/oauth/mobile/revoke`). Clearing an API key does not revoke it.

On an account switch, cancel requests from the old account, drop its page
cursors and pending results, and do not show results that arrive late for the
old account. A simple way is one client per signed-in account.

## Staging checklist (bearer sign-in)

Bearer sign-in works only on the fork backend. The disposable staging
deployment is `https://mr-api.osm.lol`. It uses development OSM accounts and
its own MapRoulette database.

1. **Development OSM account.** Create one at
   [master.apis.dev.openstreetmap.org](https://master.apis.dev.openstreetmap.org).
   It is separate from your openstreetmap.org account. Edits there do not touch
   real OSM data.
2. **Client ID.** Staging accepts only registered clients. To evaluate the SDK,
   use one of the example clients, so you don't have to wait for a
   registration:

   | Client ID | Redirect URI |
   | --- | --- |
   | `maproulette-android-example` | `org.maproulette.example:/oauth2redirect` |
   | `maproulette-ios-example` | `org.maproulette.example:/oauth2redirect` |

   Your app must handle that exact redirect. The example clients are shared:
   anyone can use them, and the OSM and MapRoulette consent screens name the
   example app, not yours. Use them for development only.

   When your app is going to real users, ask the maintainer to register its own
   client by [opening a GitHub issue](https://github.com/mvexel/maproulette-mobile-sdk/issues).
   Include the app name and your redirect URI, with a custom scheme you own
   (for example `com.yourorg.app:/oauth2redirect`). A client ID is a public
   identifier, not a secret. There is no client secret for mobile apps.
3. **Redirect URI.** The redirect must match the registered one exactly,
   including the scheme and path.
4. **Scopes.** Request `tasks:read tasks:write osm:tagfix`. The server may grant
   less; check `getCurrentUser().scopes`.
5. **Environment.** Use `MapRouletteEnvironment.STAGING` / `.staging`, so the
   SDK and the sign-in endpoints share the origin `https://mr-api.osm.lol`.

The sign-in endpoints, relative to the origin:

| Endpoint | Use |
| --- | --- |
| `/oauth/mobile/authorize` | Browser authorization, authorization code with S256 PKCE |
| `/oauth/mobile/token` | Code exchange (`grant_type=authorization_code`) and refresh (`grant_type=refresh_token`) |
| `/oauth/mobile/revoke` | Revoke a grant on sign-out |

The fork backend's setup guide is in the
[backend repository](https://github.com/mvexel/maproulette-mobile-backend/tree/feat/mobile-oauth).

## Your own backend

To complete tasks against your own deployment instead of staging:

1. Deploy the fork backend; see its
   [deployment guide](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-staging-deploy.md).
2. Enable mobile OAuth and register your app as a client in its config, with
   your redirect URI; see [mobile OAuth](https://github.com/mvexel/maproulette-mobile-backend/blob/feat/mobile-oauth/docs/mobile-oauth.md).
3. Create the environment with writes enabled:
   `MapRouletteEnvironment("https://mr.example.org/api/v2/", allowWrites = true)` in Kotlin,
   `try MapRouletteEnvironment(serviceURL: url, allowWrites: true)` in Swift.
   Without the opt-in, sign-in and reads work but the SDK refuses every write.
   The opt-in is refused for `maproulette.org` and its subdomains.

## Android: AppAuth

The Android demo uses [AppAuth for Android](https://github.com/openid/AppAuth-Android)
with the browser authorization-code flow and PKCE. It stores the grant
encrypted with an Android Keystore key, serializes refreshes, and signs out
locally first. Copy from it:
[android-example](../../android-example/README.md), under
`android-example/app/src/main/java/org/maproulette/example/auth/`.

## iOS: MobileSignIn

`MobileSignIn` runs the browser sign-in for you: authorization code with S256
PKCE, `state` and redirect checks, token exchange, an identity check with
`getCurrentUser()`, Keychain storage, refresh and revocation. It has no UI. Your
app opens the authorization URL in `ASWebAuthenticationSession` (or SwiftUI's
`WebAuthenticationSession`) and hands back the callback URL.

```swift
import AuthenticationServices
import MapRoulette

let configuration = try MobileSignInConfiguration(
    environment: .staging, clientID: "maproulette-ios-example",
    redirectURI: URL(string: "org.maproulette.example:/oauth2redirect")!)
let signIn = MobileSignIn(
    configuration: configuration,
    store: KeychainCredentialStore(service: "com.example.app.auth", binding: configuration.storageBinding))

await signIn.restore()  // at launch: .signedIn, .signedOut, .refreshInterrupted or .unreadable

// SwiftUI: @Environment(\.webAuthenticationSession) private var webAuth
let account = try await signIn.signIn(scopes: MobileSignInConfiguration.scopes(writes: true)) { url in
    try await webAuth.authenticate(
        using: url, callbackURLScheme: configuration.callbackScheme,
        preferredBrowserSession: .ephemeral)  // each sign-in shows the OSM login
}
let client = await signIn.client()  // bound to this account
```

What it guarantees:

- **One client per account.** `client()` binds to the current account. After
  `signOut()` or another `signIn`, its requests fail with `CancellationError`
  before anything is sent. Make a new client after every sign-in.
- **Refresh at most once per token.** Tokens are refreshed when they expire
  within a minute, one refresh at a time. Before a refresh token leaves the
  device it is marked pending. If the app dies before the result is saved,
  `restore()` returns `.refreshInterrupted` and signs out rather than retry a
  token that may already be spent. A failed refresh also signs out.
- **401 handling.** A 401 from the backend triggers one refresh for the next
  request. The rejected request is never resent. `osm_reauth_required` is about
  the backend's OSM token, so it does not refresh.
- **Narrower grants.** The backend may grant less than you asked for. Check
  `account.canWriteTasks` and `account.canEditOsm`.
- **Sign-out is local first.** `signOut()` clears the grant, then asks the
  backend to revoke it; `SignOutResult` says whether the revocation was
  confirmed.

`SignInFailure` says why a sign-in did not finish (`.canceled`, `.denied`,
`.callbackMismatch`, `.tokenExchange`, …). It never carries server text or
tokens, so it is safe to log. Use `InMemoryCredentialStore` in tests and
previews, or implement `CredentialStore` yourself.

An ephemeral session shares no browser cookies, so the user can pick a
different OSM account each time. Without it, the browser silently reuses the
OSM account it is signed in to.
