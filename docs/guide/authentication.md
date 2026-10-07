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

## Android: AppAuth

The Android demo uses [AppAuth for Android](https://github.com/openid/AppAuth-Android)
with the browser authorization-code flow and PKCE. It stores the grant
encrypted with an Android Keystore key, serializes refreshes, and signs out
locally first. Copy from it:
[android-example](../../android-example/README.md), under
`android-example/app/src/main/java/org/maproulette/example/auth/`.

## iOS: ASWebAuthenticationSession and PKCE

The Swift SDK has no sign-in helper. Your app runs the browser flow and gives
the SDK an access-token provider. The iOS demo does this with no third-party
code; see `ios-example/MapRouletteExample/Auth/` and the
[iOS README](../../ios-example/README.md). The core of it:

```swift
import AuthenticationServices

// verifier: a random URL-safe string. challenge: base64url(SHA-256(verifier)), no padding.
var authorize = URLComponents(string: "https://mr-api.osm.lol/oauth/mobile/authorize")!
authorize.queryItems = [
    URLQueryItem(name: "response_type", value: "code"),
    URLQueryItem(name: "client_id", value: clientID),
    URLQueryItem(name: "redirect_uri", value: "org.maproulette.example:/oauth2redirect"),
    URLQueryItem(name: "scope", value: "tasks:read tasks:write osm:tagfix"),
    URLQueryItem(name: "state", value: state),
    URLQueryItem(name: "code_challenge", value: challenge),
    URLQueryItem(name: "code_challenge_method", value: "S256"),
]
let session = ASWebAuthenticationSession(
    url: authorize.url!, callbackURLScheme: "org.maproulette.example"
) { callback, error in
    // Check `state`, read `code`, then POST a form to /oauth/mobile/token with
    // grant_type=authorization_code, code, redirect_uri, client_id and code_verifier.
}
session.prefersEphemeralWebBrowserSession = true  // each sign-in shows the OSM login
session.presentationContextProvider = presenter
session.start()
```

Store the tokens in the Keychain. Refresh with `grant_type=refresh_token`
before the access token expires, one refresh at a time.

An ephemeral session shares no browser cookies, so the user can pick a
different OSM account each time. Without it, the browser silently reuses the
OSM account it is signed in to.
