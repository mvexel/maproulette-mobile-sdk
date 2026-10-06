import CryptoKit
import Foundation
import MapRoulette
import os

/// Public session state; contains no credentials or provider error descriptions.
struct SessionView: Equatable, Sendable {
  var generation = 0
  var signedIn = false
  var userID: Int64?
  var message: String
  /// The grant includes `tasks:write`. False for read-only grants and signed-out sessions.
  var canWriteTasks = false
  /// The grant includes `osm:tagfix` (with `tasks:write`): choice answers and deletes may edit OSM.
  var canEditOsm = false
}

struct SessionFailure: Error {}

/// The SDK client of one session generation, with the deletion setting at creation.
struct SessionClient: Sendable {
  let client: MapRouletteClient
}

/// App-owned sign-in, token storage and refresh. All mutable state is on the main actor.
@MainActor @Observable final class AppSession {
  nonisolated static let readScope = "tasks:read"
  nonisolated static let writeScope = "tasks:write"
  nonisolated static let tagfixScope = "osm:tagfix"
  /// Backends with a known OSM server. Staging edits development OSM, never the real map.
  nonisolated static let osmServers = ["https://mr-api.osm.lol": TaskText.devOSM]
  /// Disposable deployments that may receive task writes (AGENTS.md). Never maproulette.org.
  nonisolated static let writeOrigins: Set<String> = ["https://mr-api.osm.lol"]

  let endpoints: AuthEndpoints
  private(set) var view: SessionView
  /// Demo setting (default off): choice "gone" outcomes delete the OSM element. Off, they resolve
  /// as Not an issue. Applies to clients created afterwards; only writable builds can turn it on.
  var allowElementDeletion: Bool {
    get { deletionSetting && writesConfigured }
    set {
      deletionSetting = newValue && writesConfigured
      UserDefaults.standard.set(deletionSetting, forKey: Self.deletionKey)
    }
  }
  private var deletionSetting: Bool

  @ObservationIgnored private let store: KeychainStore
  @ObservationIgnored private var grant: Grant?
  @ObservationIgnored private var refreshing: Task<Void, any Error>?
  @ObservationIgnored private let log = Logger(subsystem: "org.maproulette.example", category: "auth")
  @ObservationIgnored private let demo: (any Transport)?
  private static let deletionKey = "allowElementDeletion"

  var signInAvailable: Bool { endpoints.enabled && demo == nil }
  /// Task writes need sign-in and never target the production MapRoulette deployment.
  var writesConfigured: Bool {
    demo != nil
      || (signInAvailable
        && Self.writesAllowed(origin: endpoints.origin, loopbackAllowed: endpoints.loopbackAllowed))
  }
  /// The OSM server this build's backend edits, for confirmations and changeset links.
  var osmServer: String? { demo != nil ? TaskText.devOSM : Self.osmServers[endpoints.origin] }
  /// ASWebAuthenticationSession runs ephemeral: no cookies are shared with Safari, so every
  /// sign-in shows the OSM login and can choose an account.
  let accountHint =
    "Sign-in opens a private browser session, so you can choose any OpenStreetMap account each time."

  init(config: AppConfig) {
    guard
      let endpoints = try? AuthEndpoints(
        baseURL: config.baseURL, clientID: config.clientID, allowLoopback: config.allowLoopback)
    else { fatalError("Invalid MAPROULETTE_BASE_URL or MAPROULETTE_OAUTH_CLIENT_ID build setting") }
    self.endpoints = endpoints
    store = KeychainStore(binding: endpoints.storageBinding)
    demo = nil
    view = SessionView(message: "")
    deletionSetting = UserDefaults.standard.bool(forKey: Self.deletionKey)
    restore()
  }

  #if DEBUG
    /// Screenshot/demo mode: an in-process mock backend, signed in as user 7. No network.
    init(demo transport: any Transport) {
      endpoints = try! AuthEndpoints(baseURL: "https://demo.invalid", clientID: "", allowLoopback: false)
      store = KeychainStore(binding: "demo")
      demo = transport
      view = SessionView(
        signedIn: true, userID: 7, message: "Signed in as MapRoulette user 7 · demo backend, no network",
        canWriteTasks: true, canEditOsm: true)
      deletionSetting = false
    }
  #endif

  // MARK: Persistence

  private struct Grant: Codable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var scopes: Set<String>
    var needsRefresh: Bool { expiresAt.timeIntervalSinceNow < 60 }
  }
  private struct Saved: Codable {
    var binding: String
    var grant: Grant?
    var userID: Int64?
    var refreshPending = false
  }

  private func restore() {
    do {
      guard let data = try store.read() else { return publish(signedOutMessage()) }
      let saved = try JSONDecoder().decode(Saved.self, from: data)
      guard saved.binding == endpoints.storageBinding else { throw SessionFailure() }
      // A process death during refresh leaves its outcome uncertain. Never retry the old token.
      if saved.refreshPending {
        try? store.clear()
        return publish("A previous refresh was interrupted. Sign in again.")
      }
      if endpoints.enabled, let grant = saved.grant, let user = saved.userID, user > 0 {
        self.grant = grant
        view.userID = user
      }
      publish(grant != nil ? signedInMessage() : signedOutMessage())
    } catch {
      try? store.clear()
      grant = nil
      view.userID = nil
      publish("Saved sign-in could not be restored. Sign in again.")
    }
  }

  private func persist(refreshPending: Bool = false) throws {
    let saved = Saved(
      binding: endpoints.storageBinding, grant: grant, userID: view.userID,
      refreshPending: refreshPending)
    try store.write(JSONEncoder().encode(saved))
  }

  private func signedOutMessage() -> String {
    endpoints.enabled
      ? "Signed out · Public access"
      : "Sign-in needs a configured test backend and approved OAuth client ID. Public browsing is available."
  }

  private func signedInMessage() -> String {
    let scopes = grant?.scopes ?? []
    let limit =
      !writesConfigured ? ""
      : !scopes.contains(Self.writeScope) ? " · read-only sign-in"
      : !scopes.contains(Self.tagfixScope) ? " · cannot edit OpenStreetMap" : ""
    return "Signed in as MapRoulette user \(view.userID.map(String.init) ?? "?")\(limit)"
  }

  private func publish(_ message: String) {
    let scopes = grant?.scopes ?? []
    let writable = scopes.contains(Self.writeScope)
    view = SessionView(
      generation: view.generation, signedIn: grant != nil, userID: grant != nil ? view.userID : nil,
      message: message, canWriteTasks: writable,
      canEditOsm: writable && scopes.contains(Self.tagfixScope))
  }

  /// Ends the current generation: old clients and in-flight requests can no longer use credentials.
  private func invalidate(_ message: String) throws {
    view.generation += 1
    grant = nil
    view.userID = nil
    refreshing = nil
    defer { publish(message) }
    try store.clear()
  }

  private func checkGeneration(_ expected: Int) throws {
    if expected != view.generation { throw CancellationError() }
  }

  // MARK: Sign-in

  /// Browser authorization-code flow with S256 PKCE. `authenticate` opens the URL in an
  /// ephemeral web authentication session and returns the callback URL. Signs out first
  /// (revoking the current grant) when a session exists, e.g. a read-only one.
  func signIn(authenticate: (URL) async throws -> URL) async {
    guard signInAvailable else { return }
    // Keep the revocation result visible: the old grant may still be valid on the server.
    let revocation = view.signedIn ? await signOut() : nil
    try? invalidate([revocation, "Opening browser sign-in…"].compactMap { $0 }.joined(separator: " "))
    let expected = view.generation
    let verifier = Self.randomToken()
    let state = Self.randomToken()
    let scope = Self.requestedScope(writesConfigured: writesConfigured)
    var url = URLComponents(url: endpoints.authorize, resolvingAgainstBaseURL: false)!
    url.queryItems = [
      URLQueryItem(name: "response_type", value: "code"),
      URLQueryItem(name: "client_id", value: endpoints.clientID),
      URLQueryItem(name: "redirect_uri", value: AuthEndpoints.redirect),
      URLQueryItem(name: "scope", value: scope),
      URLQueryItem(name: "state", value: state),
      URLQueryItem(name: "code_challenge", value: Self.challenge(verifier)),
      URLQueryItem(name: "code_challenge_method", value: "S256"),
    ]
    let callback: URL
    do {
      callback = try await authenticate(url.url!)
    } catch {
      if view.generation == expected { try? invalidate("Sign-in canceled or could not complete.") }
      return
    }
    // An old browser result must not replace a newer sign-in or signed-in account.
    guard view.generation == expected else { return }
    let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
    func item(_ name: String) -> String? { items.first { $0.name == name }?.value }
    guard endpoints.acceptsCallback(callback), item("state") == state else {
      try? invalidate("Sign-in callback did not match this app's pending request.")
      return
    }
    if let error = item("error") {
      try? invalidate(error == "access_denied" ? "Sign-in was declined." : "Sign-in canceled or could not complete.")
      return
    }
    guard let code = item("code"), !code.isEmpty else {
      try? invalidate("Sign-in canceled or could not complete.")
      return
    }
    var stage = "token exchange"
    do {
      let next = try await requestToken([
        "grant_type": "authorization_code", "code": code, "redirect_uri": AuthEndpoints.redirect,
        "client_id": endpoints.clientID, "code_verifier": verifier,
      ], current: nil, requested: scope)
      try checkGeneration(expected)
      stage = "account lookup"
      let token = next.accessToken
      let identity = try await MapRouletteClient(
        environment: MapRouletteEnvironment(serviceURL: endpoints.api), accessToken: { token }
      ).getCurrentUser()
      try checkGeneration(expected)
      guard !identity.guest, identity.id > 0, identity.scopes == next.scopes else {
        throw SessionFailure()
      }
      stage = "secure storage"
      grant = next
      view.userID = identity.id
      try persist()
      view.generation += 1
      publish(signedInMessage())
    } catch is CancellationError {
      return
    } catch {
      // Error type only; never token content or server text.
      log.debug("Sign-in failed during \(stage, privacy: .public): \(String(describing: type(of: error)), privacy: .public)")
      if view.generation == expected {
        grant = nil
        try? invalidate("Sign-in failed during \(stage). Please try again.")
      }
    }
  }

  /// Token endpoint call. Validates the response before it is used; never follows redirects.
  private func requestToken(_ form: [String: String], current: Grant?, requested: String) async throws -> Grant {
    guard endpoints.permitsConnection(endpoints.token) else { throw SessionFailure() }
    let (status, body) = try await Self.post(endpoints.token, form)
    guard status == 200,
      let o = try JSONSerialization.jsonObject(with: body) as? [String: Any],
      (o["token_type"] as? String)?.lowercased() == "bearer",
      let access = o["access_token"] as? String, !access.isEmpty,
      let refresh = o["refresh_token"] as? String, !refresh.isEmpty,
      let expiresIn = (o["expires_in"] as? NSNumber)?.doubleValue, expiresIn > 0
    else { throw SessionFailure() }
    let granted = Self.scopes(o["scope"] as? String)
    // A refresh must keep the grant's scope; a new grant may be narrower if the client is.
    if let current {
      guard granted == current.scopes else { throw SessionFailure() }
    } else {
      guard Self.acceptableGrant(granted, requested: requested) else { throw SessionFailure() }
    }
    return Grant(
      accessToken: access, refreshToken: refresh, expiresAt: Date().addingTimeInterval(expiresIn),
      scopes: granted)
  }

  // MARK: Tokens

  /// The current access token, refreshed when needed. Refresh is serialized; a failed or
  /// interrupted refresh signs out instead of retrying a possibly consumed refresh token.
  fileprivate func accessToken(_ expected: Int) async throws -> String? {
    try checkGeneration(expected)
    if let refreshing { try await refreshing.value }
    try checkGeneration(expected)
    guard let current = grant else { return nil }
    if current.needsRefresh {
      let task = Task { try await self.refresh(current, expected) }
      refreshing = task
      defer { if refreshing == task { refreshing = nil } }
      try await task.value
    }
    try Task.checkCancellation()
    try checkGeneration(expected)
    return grant?.accessToken
  }

  private func refresh(_ current: Grant, _ expected: Int) async throws {
    do {
      // Persist uncertainty before a single-use refresh token leaves the device.
      try persist(refreshPending: true)
      let next = try await requestToken(
        ["grant_type": "refresh_token", "client_id": endpoints.clientID, "refresh_token": current.refreshToken],
        current: current, requested: "")
      try checkGeneration(expected)
      grant = next
      try persist()
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      log.debug("Refresh failed: \(String(describing: type(of: error)), privacy: .public)")
      if view.generation == expected { try? invalidate("Refresh could not complete safely. Sign in again.") }
      throw SessionFailure()
    }
  }

  /// One forced refresh after the server rejected `usedHeader`. Refresh failure signs out.
  fileprivate func renewAfterRejection(_ expected: Int, usedHeader: String) async {
    guard view.generation == expected, let current = grant,
      usedHeader == "Bearer \(current.accessToken)", refreshing == nil
    else { return }
    grant?.expiresAt = .distantPast
    _ = try? await accessToken(expected)
  }

  /// Clears locally before contacting the server; old requests cannot acquire new tokens.
  @discardableResult
  func signOut() async -> String {
    let token = grant?.refreshToken ?? grant?.accessToken
    let localCleared = (try? invalidate("Signed out locally. Checking server revocation…")) != nil
    let expected = view.generation
    var confirmed = true
    if let token, endpoints.permitsConnection(endpoints.revoke) {
      let result = try? await Self.post(endpoints.revoke, ["client_id": endpoints.clientID, "token": token])
      confirmed = result.map { (200...299).contains($0.0) } ?? false
    }
    let message =
      !localCleared
      ? "Signed out in memory, but secure storage could not be cleared. Server revocation \(confirmed ? "confirmed" : "unconfirmed")."
      : confirmed ? "Signed out. Server revocation confirmed."
      : "Signed out locally. Server revocation could not be confirmed."
    if view.generation == expected { publish(message) }
    return message
  }

  // MARK: Clients

  /// SDK clients bound to the current generation. After sign-out or an account switch their
  /// requests fail before sending.
  func newClient() -> SessionClient {
    let expected = view.generation
    let deletion = allowElementDeletion
    #if DEBUG
      if let demo {
        // The in-process mock never leaves the device; the staging environment only lets the
        // SDK send its writes to it.
        return SessionClient(
          client: MapRouletteClient(
            environment: .staging, transport: demo, allowElementDeletion: deletion,
            accessToken: { "demo" }))
      }
    #endif
    let transport = GuardedTransport(session: self, expected: expected, inner: URLSessionTransport())
    // AuthEndpoints validated the origin, so this cannot fail.
    let environment = try! MapRouletteEnvironment(serviceURL: endpoints.api)
    return SessionClient(
      client: MapRouletteClient(
        environment: environment, transport: transport, allowElementDeletion: deletion,
        accessToken: { [weak self] in try await self?.accessToken(expected) }))
  }

  /// Enforced below the UI: a lifecycle write leaves the device only for an allowlisted backend
  /// and a grant that includes `tasks:write`. Everything else is refused unsent.
  fileprivate func admit(_ request: HTTPRequest, _ expected: Int) throws {
    try checkGeneration(expected)
    if Self.isTaskWrite(request.url),
      !(writesConfigured && grant?.scopes.contains(Self.writeScope) == true)
    {
      throw MapRouletteError(.permission)
    }
  }

  fileprivate func received(_ request: HTTPRequest, _ response: HTTPResponse, _ expected: Int) async throws {
    try checkGeneration(expected)
    // The rejected request is never resent: the SDK reports authentication to the caller.
    // osm_reauth_required concerns the server's OSM token, not this MapRoulette token: no refresh.
    if response.status == 401, !Self.isOsmReauth(response.body),
      let header = request.headers["Authorization"]
    {
      await renewAfterRejection(expected, usedHeader: header)
    }
  }

  // MARK: Rules (unit-tested)

  /// Writes and OSM edits are requested only for an allowlisted backend.
  nonisolated static func requestedScope(writesConfigured: Bool) -> String {
    writesConfigured ? "\(readScope) \(writeScope) \(tagfixScope)" : readScope
  }

  /// A new grant: read plus a subset of what was requested; `osm:tagfix` only with `tasks:write`.
  nonisolated static func acceptableGrant(_ granted: Set<String>, requested: String) -> Bool {
    granted.contains(readScope) && scopes(requested).isSuperset(of: granted)
      && (!granted.contains(tagfixScope) || granted.contains(writeScope))
  }

  /// A writable build whose grant lacks task writes or OSM editing: "Sign in again to enable editing".
  nonisolated static func needsReconsent(writesConfigured: Bool, granted: Set<String>) -> Bool {
    writesConfigured && !(granted.contains(writeScope) && granted.contains(tagfixScope))
  }

  /// Exact origin allowlist; loopback only in a debug build that enabled it for local testing.
  nonisolated static func writesAllowed(origin: String, loopbackAllowed: Bool) -> Bool {
    guard let c = URLComponents(string: origin), c.user == nil, c.password == nil, c.path.isEmpty,
      c.query == nil, c.fragment == nil
    else { return false }
    if writeOrigins.contains(origin) { return true }
    return loopbackAllowed && c.scheme == "http" && (c.host == "127.0.0.1" || c.host == "localhost")
  }

  /// Any task lifecycle write (start, refreshLock, release, skip, choice or status), whatever
  /// the method. The read-only `choice/check` is not one. Unparseable URLs count as writes.
  nonisolated static func isTaskWrite(_ url: URL) -> Bool {
    guard let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath else {
      return true
    }
    return path.range(
      of: #"/task/\d+/(start|refreshLock|release|skip|choice|\d+)/?$"#, options: .regularExpression)
      != nil
  }

  /// A 401 from the choice route that asks for OSM re-consent; the MapRoulette grant is still valid.
  nonisolated static func isOsmReauth(_ body: Data) -> Bool {
    ((try? JSONSerialization.jsonObject(with: body)) as? [String: Any])?["error"] as? String
      == "osm_reauth_required"
  }

  nonisolated static func scopes(_ value: String?) -> Set<String> {
    Set((value ?? "").split(separator: " ").map(String.init))
  }

  private nonisolated static func randomToken() -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    precondition(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess)
    return base64URL(Data(bytes))
  }

  nonisolated static func challenge(_ verifier: String) -> String {
    base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
  }

  private nonisolated static func base64URL(_ data: Data) -> String {
    data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
  }

  /// Form POST to an authentication endpoint without cookies, caches or redirects.
  private nonisolated static func post(_ url: URL, _ form: [String: String]) async throws -> (Int, Data) {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    let body = form.sorted { $0.key < $1.key }.map {
      "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
    }.joined(separator: "&")
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    request.httpBody = Data(body.utf8)
    let session = URLSession(configuration: authConfiguration, delegate: NoRedirect(), delegateQueue: nil)
    defer { session.finishTasksAndInvalidate() }
    let (data, response) = try await session.data(for: request)
    guard let http = response as? HTTPURLResponse else { throw SessionFailure() }
    return (http.statusCode, data)
  }

  private nonisolated static var authConfiguration: URLSessionConfiguration {
    let c = URLSessionConfiguration.ephemeral
    c.timeoutIntervalForRequest = 30
    c.urlCache = nil
    c.httpCookieStorage = nil
    c.urlCredentialStorage = nil
    return c
  }
}

private final class NoRedirect: NSObject, URLSessionTaskDelegate, Sendable {
  func urlSession(
    _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
  ) { completionHandler(nil) }
}

/// Binds an SDK transport to one session generation and enforces the write allowlist.
private struct GuardedTransport: Transport {
  let session: AppSession
  let expected: Int
  let inner: URLSessionTransport

  func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
    try await session.admit(request, expected)
    let response = try await inner.execute(request)
    try await session.received(request, response, expected)
    return response
  }
}
