import Foundation
import Testing
import MapRoulette  // Not @testable: sign-in is used through the public API only.

private let redirect = URL(string: "app.test:/oauth2redirect")!
private let all = MobileSignInConfiguration.scopes(writes: true)

private func json(_ value: Any) -> Data { try! JSONSerialization.data(withJSONObject: value) }
private func token(_ access: String, _ refresh: String, scope: Set<String> = all, expiresIn: Int = 3600) -> HTTPResponse {
  HTTPResponse(
    status: 200,
    body: json([
      "token_type": "Bearer", "access_token": access, "refresh_token": refresh, "expires_in": expiresIn,
      "scope": scope.sorted().joined(separator: " "),
    ]))
}
private func me(scope: Set<String> = all) -> HTTPResponse {
  HTTPResponse(
    status: 200,
    body: json(["id": 900, "osmId": 12345, "displayName": "Example", "scope": scope.sorted().joined(separator: " ")]))
}
private let unauthorized = HTTPResponse(status: 401, body: json(["message": "Unauthorized"]))

/// The fork backend's OAuth routes, answered from queues. Records every request.
private actor Backend: Transport {
  var tokens: [HTTPResponse]
  var identities: [HTTPResponse]
  var requests: [HTTPRequest] = []
  init(tokens: [HTTPResponse], identities: [HTTPResponse] = [me()]) {
    self.tokens = tokens
    self.identities = identities
  }
  func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
    requests.append(request)
    switch request.url.path {
    case "/oauth/mobile/token":
      guard !tokens.isEmpty else { return HTTPResponse(status: 400, body: json(["error": "invalid_grant"])) }
      return tokens.removeFirst()
    case "/oauth/mobile/me": return identities.count > 1 ? identities.removeFirst() : identities[0]
    case "/oauth/mobile/revoke": return HTTPResponse(status: 200, body: Data())
    default: return HTTPResponse(status: 404, body: Data())
    }
  }
  func calls(_ path: String) -> [HTTPRequest] { requests.filter { $0.url.path == path } }
  func form(_ request: HTTPRequest) -> [String: String] {
    let pairs = String(decoding: request.body ?? Data(), as: UTF8.self).split(separator: "&")
    return Dictionary(uniqueKeysWithValues: pairs.map {
      let kv = $0.split(separator: "=", maxSplits: 1).map(String.init)
      return (kv[0], kv.count > 1 ? kv[1].removingPercentEncoding ?? "" : "")
    })
  }
}

private final class Clock: @unchecked Sendable {
  var now = Date(timeIntervalSince1970: 1_800_000_000)
}

/// Plays the browser: approves with `code`, or answers with `error`, echoing `state` unless told otherwise.
private final class Browser: @unchecked Sendable {
  var opened: URL?
  var error: String?
  var state: String?
  func callback(_ url: URL) throws -> URL {
    opened = url
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
    let echoed = state ?? items.first { $0.name == "state" }!.value!
    var c = URLComponents(url: redirect, resolvingAgainstBaseURL: false)!
    c.queryItems = [URLQueryItem(name: "state", value: echoed)]
      + [error.map { URLQueryItem(name: "error", value: $0) } ?? URLQueryItem(name: "code", value: "the-code")]
    return c.url!
  }
}

private func configuration() throws -> MobileSignInConfiguration {
  try MobileSignInConfiguration(environment: .staging, clientID: "test-client", redirectURI: redirect)
}

private func signIn(
  _ backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore(), clock: Clock = Clock(),
  browser: Browser = Browser()
) async throws -> (MobileSignIn, MobileAccount) {
  let session = MobileSignIn(
    configuration: try configuration(), store: store, transport: backend, now: { clock.now })
  let account = try await session.signIn(scopes: all) { try browser.callback($0) }
  return (session, account)
}

private func failure(_ operation: () async throws -> Void) async -> SignInFailure? {
  do { try await operation(); Issue.record("Expected failure"); return nil }
  catch let error as SignInFailure { return error }
  catch { Issue.record("Unexpected \(error)"); return nil }
}

/// base64url(SHA-256(verifier)) without padding (RFC 7636 S256); expected value computed independently.
@Test func challengeIsUnpaddedBase64URLOfSHA256() {
  #expect(
    MobileSignIn.challenge("dBjftJeZ4CVP-mJ92K1QD8a7jj6c3D9vrxwYGrqXDW8")
      == "nSDMx7jJh8uGbliKbSstDRcTcJmLbdPcZm3IpF_Q1T0")
}

@Test func configurationValidatesAndUsesTheBackendOrigin() throws {
  #expect(throws: MapRouletteError.self) {
    _ = try MobileSignInConfiguration(environment: .staging, clientID: "bad id", redirectURI: redirect)
  }
  #expect(throws: MapRouletteError.self) {
    _ = try MobileSignInConfiguration(
      environment: .staging, clientID: "c", redirectURI: URL(string: "app.test:/cb#fragment")!)
  }
  let c = try configuration()
  #expect(c.callbackScheme == "app.test")
  #expect(c.storageBinding == "https://mr-api.osm.lol|test-client")
}

@Test func signInUsesPKCEAndStoresTheGrant() async throws {
  let backend = Backend(tokens: [token("access-1", "refresh-1")])
  let browser = Browser()
  let store = InMemoryCredentialStore()
  let (session, account) = try await signIn(backend, store: store, browser: browser)
  #expect(account.userID == 900 && account.canWriteTasks && account.canEditOsm)
  #expect(await session.account == account)

  let opened = try #require(browser.opened)
  #expect(opened.absoluteString.hasPrefix("https://mr-api.osm.lol/oauth/mobile/authorize?"))
  let items = Dictionary(
    uniqueKeysWithValues: URLComponents(url: opened, resolvingAgainstBaseURL: false)!.queryItems!.map {
      ($0.name, $0.value ?? "")
    })
  #expect(items["response_type"] == "code" && items["client_id"] == "test-client")
  #expect(items["redirect_uri"] == "app.test:/oauth2redirect" && items["code_challenge_method"] == "S256")
  #expect(items["scope"] == "osm:tagfix tasks:read tasks:write")
  let exchange = try #require(await backend.calls("/oauth/mobile/token").first)
  let form = await backend.form(exchange)
  #expect(form["grant_type"] == "authorization_code" && form["code"] == "the-code")
  #expect(MobileSignIn.challenge(try #require(form["code_verifier"])) == items["code_challenge"])
  let lookup = try #require(await backend.calls("/oauth/mobile/me").first)
  #expect(lookup.headers["Authorization"] == "Bearer access-1")

  // A new instance (next launch) restores the account and its token.
  let next = MobileSignIn(configuration: try configuration(), store: store, transport: backend)
  guard case .signedIn(let restored) = await next.restore() else { Issue.record("not restored"); return }
  #expect(restored.userID == 900 && restored.scopes == all)
  #expect(try await next.accessToken(generation: restored.generation) == "access-1")
}

@Test func callbackMustMatchStateAndRedirect() async throws {
  let browser = Browser()
  browser.state = "forged"
  let backend = Backend(tokens: [token("a", "r")])
  #expect(await failure { _ = try await signIn(backend, browser: browser) } == .callbackMismatch)
  #expect(await backend.calls("/oauth/mobile/token").isEmpty, "no code is exchanged")
}

@Test func providerErrorsAreReported() async throws {
  let browser = Browser()
  browser.error = "access_denied"
  #expect(await failure { _ = try await signIn(Backend(tokens: []), browser: browser) } == .denied)
  browser.error = "server_error"
  #expect(
    await failure { _ = try await signIn(Backend(tokens: []), browser: browser) } == .providerError("server_error"))
}

@Test func canceledBrowserSessionIsCanceled() async throws {
  let session = MobileSignIn(
    configuration: try configuration(), store: InMemoryCredentialStore(), transport: Backend(tokens: []))
  #expect(await failure { _ = try await session.signIn(scopes: all) { _ in throw URLError(.cancelled) } } == .canceled)
  #expect(await session.account == nil)
}

@Test func grantWiderThanRequestedIsRefused() async throws {
  let read: Set<String> = [MobileSignInConfiguration.readScope]
  #expect(MobileSignIn.acceptableGrant(read, requested: all))
  #expect(!MobileSignIn.acceptableGrant(all, requested: read))
  #expect(!MobileSignIn.acceptableGrant([MobileSignInConfiguration.readScope, "osm:tagfix"], requested: all))
  let session = MobileSignIn(
    configuration: try configuration(), store: InMemoryCredentialStore(), transport: Backend(tokens: [token("a", "r")]))
  let browser = Browser()
  #expect(await failure { _ = try await session.signIn(scopes: read) { try browser.callback($0) } } == .tokenExchange)
}

@Test func identityMustMatchTheGrant() async throws {
  let backend = Backend(tokens: [token("a", "r")], identities: [me(scope: [MobileSignInConfiguration.readScope])])
  #expect(await failure { _ = try await signIn(backend) } == .accountLookup)
}

@Test func expiredTokenRefreshesOnceForConcurrentCallers() async throws {
  let clock = Clock()
  let backend = Backend(tokens: [token("access-1", "refresh-1"), token("access-2", "refresh-2")])
  let (session, account) = try await signIn(backend, clock: clock)
  clock.now += 3600
  async let first = session.accessToken(generation: account.generation)
  async let second = session.accessToken(generation: account.generation)
  #expect(try await [first, second] == ["access-2", "access-2"])
  let refreshes = await backend.calls("/oauth/mobile/token").dropFirst()
  #expect(refreshes.count == 1)
  let form = await backend.form(try #require(refreshes.first))
  #expect(form["grant_type"] == "refresh_token" && form["refresh_token"] == "refresh-1")
}

@Test func failedRefreshSignsOut() async throws {
  let clock = Clock()
  let store = InMemoryCredentialStore()
  let (session, account) = try await signIn(Backend(tokens: [token("a", "r")]), store: store, clock: clock)
  clock.now += 3600
  await #expect(throws: MapRouletteError.self) { _ = try await session.accessToken(generation: account.generation) }
  #expect(await session.account == nil)
  #expect(try store.read() == nil)
}

@Test func refreshMustKeepTheScope() async throws {
  let clock = Clock()
  let narrower = token("a2", "r2", scope: [MobileSignInConfiguration.readScope])
  let (session, account) = try await signIn(Backend(tokens: [token("a", "r"), narrower]), clock: clock)
  clock.now += 3600
  await #expect(throws: MapRouletteError.self) { _ = try await session.accessToken(generation: account.generation) }
  #expect(await session.account == nil)
}

@Test func interruptedRefreshSignsOutOnRestore() async throws {
  let c = try configuration()
  let store = InMemoryCredentialStore(json(["binding": c.storageBinding, "userID": 900, "refreshPending": true]))
  let session = MobileSignIn(configuration: c, store: store, transport: Backend(tokens: []))
  #expect(await session.restore() == .refreshInterrupted)
  #expect(try store.read() == nil)
}

@Test func storedGrantForAnotherClientIsNotUsed() async throws {
  let store = InMemoryCredentialStore()
  _ = try await signIn(Backend(tokens: [token("a", "r")]), store: store)
  let other = try MobileSignInConfiguration(environment: .staging, clientID: "other-client", redirectURI: redirect)
  let session = MobileSignIn(configuration: other, store: store, transport: Backend(tokens: []))
  #expect(await session.restore() == .unreadable)
  #expect(await session.account == nil)
}

@Test func signOutRevokesAndDisconnectsOldClients() async throws {
  let backend = Backend(tokens: [token("a", "refresh-1")])
  let store = InMemoryCredentialStore()
  let (session, _) = try await signIn(backend, store: store)
  let client = await session.client()
  _ = try await client.getCurrentUser()
  let lookups = await backend.calls("/oauth/mobile/me").count

  let result = await session.signOut()
  #expect(result == SignOutResult(localCleared: true, revocationConfirmed: true))
  let revoke = try #require(await backend.calls("/oauth/mobile/revoke").first)
  #expect(await backend.form(revoke)["token"] == "refresh-1")
  #expect(try store.read() == nil)
  #expect(await session.account == nil)

  await #expect(throws: (any Error).self) { _ = try await client.getCurrentUser() }
  #expect(await backend.calls("/oauth/mobile/me").count == lookups, "nothing sent for the old account")
}

@Test func unauthorizedResponseRefreshesForTheNextRequest() async throws {
  let backend = Backend(
    tokens: [token("access-1", "refresh-1"), token("access-2", "refresh-2")],
    identities: [me(), unauthorized, me()])
  let (session, _) = try await signIn(backend)
  let client = await session.client()
  await #expect(throws: MapRouletteError.self) { _ = try await client.getCurrentUser() }
  #expect(await backend.calls("/oauth/mobile/token").count == 2, "one refresh after the 401")
  _ = try await client.getCurrentUser()
  #expect(await backend.calls("/oauth/mobile/me").last?.headers["Authorization"] == "Bearer access-2")
}
