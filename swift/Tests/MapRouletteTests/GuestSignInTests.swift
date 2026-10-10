import Foundation
import Testing
import MapRoulette  // Not @testable: guests are used through the public API only.

private let redirect = URL(string: "app.test:/oauth2redirect")!
private let all = MobileSignInConfiguration.scopes(writes: true)
private let secret = String(repeating: "s", count: 43)

private func json(_ value: Any) -> Data { try! JSONSerialization.data(withJSONObject: value) }
private func oauthError(_ code: String, status: Int = 400) -> HTTPResponse {
  HTTPResponse(status: status, body: json(["error": code, "error_description": "server text"]))
}
private let registered = HTTPResponse(
  status: 201, body: json(["guestId": "6f1c2a9e-0000-4000-8000-000000000001", "guestSecret": secret,
                           "expiresAt": "2026-11-09T18:00:00Z"]))
private func guestToken(_ access: String, expiresIn: Int = 900) -> HTTPResponse {
  HTTPResponse(status: 200, body: json(["token_type": "Bearer", "access_token": access, "expires_in": expiresIn, "scope": "guest"]))
}
private func grant(_ access: String, _ refresh: String) -> HTTPResponse {
  HTTPResponse(
    status: 200,
    body: json(["token_type": "Bearer", "access_token": access, "refresh_token": refresh, "expires_in": 3600,
                "scope": all.sorted().joined(separator: " ")]))
}
private let me = HTTPResponse(
  status: 200, body: json(["id": 900, "osmId": 12345, "displayName": "Example", "scope": all.sorted().joined(separator: " ")]))

private actor Backend: Transport {
  var guests: [HTTPResponse]
  var tokens: [HTTPResponse]
  var requests: [HTTPRequest] = []
  init(guests: [HTTPResponse] = [registered], tokens: [HTTPResponse] = []) {
    self.guests = guests
    self.tokens = tokens
  }
  func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
    requests.append(request)
    switch request.url.path {
    case "/oauth/mobile/guest": return guests.removeFirst()
    case "/oauth/mobile/token":
      guard !tokens.isEmpty else { return oauthError("invalid_grant") }
      return tokens.removeFirst()
    case "/oauth/mobile/me": return me
    case "/oauth/mobile/revoke": return HTTPResponse(status: 200, body: Data())
    default: return HTTPResponse(status: 404, body: Data())
    }
  }
  func forms(_ path: String) -> [[String: String]] {
    requests.filter { $0.url.path == path }.map { request in
      let pairs = String(decoding: request.body ?? Data(), as: UTF8.self).split(separator: "&")
      return Dictionary(uniqueKeysWithValues: pairs.map {
        let kv = $0.split(separator: "=", maxSplits: 1).map(String.init)
        return (kv[0], kv.count > 1 ? kv[1].removingPercentEncoding ?? "" : "")
      })
    }
  }
}

private final class Clock: @unchecked Sendable {
  var now = Date(timeIntervalSince1970: 1_800_000_000)
}

private func session(_ backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore(), clock: Clock = Clock())
  throws -> MobileSignIn
{
  MobileSignIn(
    configuration: try MobileSignInConfiguration(environment: .staging, clientID: "test-client", redirectURI: redirect),
    store: store, transport: backend, now: { clock.now })
}

/// Approves the browser step with a code, echoing state.
private func approve(_ url: URL) -> URL {
  let state = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "state" }!.value!
  var c = URLComponents(url: redirect, resolvingAgainstBaseURL: false)!
  c.queryItems = [URLQueryItem(name: "state", value: state), URLQueryItem(name: "code", value: "the-code")]
  return c.url!
}

@Test func guestRegistersStoresAndRestores() async throws {
  let backend = Backend(tokens: [guestToken("guest-access-1")])
  let store = InMemoryCredentialStore()
  let s = try session(backend, store: store)
  let guest = try await s.startGuest()
  #expect(guest.guestID == "6f1c2a9e-0000-4000-8000-000000000001")
  #expect(try await s.startGuest() == guest)  // Idempotent: no second registration.
  #expect(await backend.forms("/oauth/mobile/guest") == [["client_id": "test-client"]])
  #expect(await s.account == nil)

  let restored = try session(Backend(), store: store)
  guard case .guest(let again) = await restored.restore() else { Issue.record("not a guest"); return }
  #expect(again.guestID == guest.guestID)
  #expect(!String(decoding: try #require(try store.read()), as: UTF8.self).isEmpty)
}

@Test func guestTokenIsFetchedCachedAndRenewed() async throws {
  let backend = Backend(tokens: [guestToken("guest-access-1"), guestToken("guest-access-2")])
  let clock = Clock()
  let s = try session(backend, clock: clock)
  let guest = try await s.startGuest()
  #expect(try await s.accessToken(generation: guest.generation) == "guest-access-1")
  #expect(try await s.accessToken(generation: guest.generation) == "guest-access-1")
  clock.now += 850  // Within a minute of expiry.
  #expect(try await s.accessToken(generation: guest.generation) == "guest-access-2")
  let forms = await backend.forms("/oauth/mobile/token")
  #expect(forms.count == 2)
  #expect(forms[0] == [
    "grant_type": "urn:maproulette:grant-type:guest", "client_id": "test-client",
    "guest_id": guest.guestID, "guest_secret": secret,
  ])
  // A client from the session sends the guest token.
  let client = await s.client()
  _ = try? await client.getGuestStatus()
  let sent = await backend.requests.last { $0.url.path.hasSuffix("mobile-guest/me") }
  #expect(sent?.headers["Authorization"] == "Bearer guest-access-2")
}

@Test func claimedGuestReportsConflictThenUpgrades() async throws {
  let backend = Backend(tokens: [oauthError("guest_claimed"), oauthError("claim_pending"), grant("access-1", "refresh-1")])
  let store = InMemoryCredentialStore()
  let s = try session(backend, store: store)
  let guest = try await s.startGuest()
  do {
    _ = try await s.accessToken(generation: guest.generation)
    Issue.record("expected guest_claimed")
  } catch let error as MapRouletteError {
    #expect(error.kind == .conflict && error.reason == "guest_claimed")
  }
  #expect(try await s.upgradeGuest() == .notYet)
  guard case .signedIn(let account) = try await s.upgradeGuest() else { Issue.record("not upgraded"); return }
  #expect(account.userID == 900 && account.canEditOsm)
  #expect(await s.guest == nil)
  let forms = await backend.forms("/oauth/mobile/token")
  #expect(forms.last?["grant_type"] == "urn:maproulette:grant-type:guest_claim")
  #expect(forms.last?["guest_secret"] == secret)
  // The stored state is now an ordinary sign-in without the guest.
  guard case .signedIn = await (try session(Backend(), store: store)).restore() else { Issue.record("not stored"); return }
}

@Test func signInAndSignOutKeepTheGuestUntilForgotten() async throws {
  let backend = Backend(tokens: [grant("access-1", "refresh-1")])
  let store = InMemoryCredentialStore()
  let s = try session(backend, store: store)
  let guest = try await s.startGuest()
  let account = try await s.signIn(scopes: all) { approve($0) }
  #expect(await s.guest?.guestID == guest.guestID)
  #expect(try await s.accessToken(generation: account.generation) == "access-1")
  await s.signOut()
  #expect(await s.account == nil && (await s.guest)?.guestID == guest.guestID)
  guard case .guest = await (try session(Backend(), store: store)).restore() else { Issue.record("guest lost"); return }
  try await s.forgetGuest()
  #expect(await s.guest == nil)
  #expect(try store.read() == nil)
  #expect(try await s.accessToken(generation: await s.generation) == nil)
}

@Test func unusableGuestCredentialIsForgotten() async throws {
  let backend = Backend(tokens: [oauthError("invalid_grant")])
  let s = try session(backend)
  let guest = try await s.startGuest()
  do {
    _ = try await s.accessToken(generation: guest.generation)
    Issue.record("expected failure")
  } catch let error as MapRouletteError {
    #expect(error.kind == .authentication && error.reason == "invalid_grant")
  }
  #expect(await s.guest == nil)
}

@Test func registrationFailuresMapToErrorKinds() async throws {
  let limited = HTTPResponse(status: 429, headers: ["Retry-After": "120"], body: json(["error": "rate_limited"]))
  let backend = Backend(guests: [limited, HTTPResponse(status: 404, body: Data()), oauthError("invalid_client", status: 401)])
  let s = try session(backend)
  for (kind, retry) in [(ErrorKind.rateLimit, "120"), (.notFound, nil), (.authentication, nil)] as [(ErrorKind, String?)] {
    do {
      _ = try await s.startGuest()
      Issue.record("expected \(kind)")
    } catch let error as MapRouletteError {
      #expect(error.kind == kind && error.retryAfter == retry)
      #expect(!error.description.contains("server text"))
    }
  }
  #expect(await s.guest == nil)
}
