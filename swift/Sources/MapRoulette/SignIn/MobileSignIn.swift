import CryptoKit
import Foundation

/// The native OAuth client an app registered on a mobile-enabled backend (fork backend only).
public struct MobileSignInConfiguration: Hashable, Sendable {
  public static let readScope = "tasks:read"
  public static let writeScope = "tasks:write"
  public static let tagfixScope = "osm:tagfix"

  public let environment: MapRouletteEnvironment
  public let clientID: String
  /// The exact registered callback, e.g. `com.example.app:/oauth2redirect`.
  public let redirectURI: URL

  /// Throws `.validation` for a malformed client ID or a redirect URI without a scheme, or with
  /// credentials, a query or a fragment.
  public init(environment: MapRouletteEnvironment, clientID: String, redirectURI: URL) throws {
    guard clientID.range(of: "^[A-Za-z0-9._-]{1,100}$", options: .regularExpression) != nil else {
      throw MapRouletteError(.validation, reason: "clientID must be 1-100 characters of A-Z a-z 0-9 . _ -")
    }
    guard let c = URLComponents(url: redirectURI, resolvingAgainstBaseURL: false),
      c.scheme?.isEmpty == false, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil
    else {
      throw MapRouletteError(
        .validation, reason: "redirectURI must have a scheme and no credentials, query or fragment")
    }
    self.environment = environment
    self.clientID = clientID
    self.redirectURI = redirectURI
  }

  /// The scheme to hand to the web authentication session.
  public var callbackScheme: String { redirectURI.scheme ?? "" }
  /// Separates stored credentials per backend origin and client.
  public var storageBinding: String { "\(origin(path: "").absoluteString)|\(clientID)" }

  /// Scopes for an app that answers choice tasks when `writes`, else read only.
  public static func scopes(writes: Bool) -> Set<String> {
    writes ? [readScope, writeScope, tagfixScope] : [readScope]
  }

  var authorizeURL: URL { origin(path: "/oauth/mobile/authorize") }
  var tokenURL: URL { origin(path: "/oauth/mobile/token") }
  var revokeURL: URL { origin(path: "/oauth/mobile/revoke") }
  var guestURL: URL { origin(path: "/oauth/mobile/guest") }

  /// OAuth routes live at the backend origin, not under the API path.
  private func origin(path: String) -> URL {
    var c = URLComponents(url: environment.serviceURL, resolvingAgainstBaseURL: false)!
    c.path = path
    c.query = nil
    return c.url!
  }

  /// The browser callback must be exactly the registered redirect (a query is allowed).
  func accepts(callback: URL) -> Bool {
    guard let actual = URLComponents(url: callback, resolvingAgainstBaseURL: false),
      let expected = URLComponents(url: redirectURI, resolvingAgainstBaseURL: false)
    else { return false }
    return actual.scheme?.lowercased() == expected.scheme?.lowercased() && actual.host == expected.host
      && actual.port == expected.port && actual.path == expected.path && actual.fragment == nil
  }
}

/// Where a signed-in grant is kept between launches. Implementations must keep the data secret
/// (the Keychain on Apple platforms) and never log it.
public protocol CredentialStore: Sendable {
  func read() throws -> Data?
  func write(_ data: Data) throws
  func clear() throws
}

/// A store that forgets everything when released: for tests, previews and demo modes.
public final class InMemoryCredentialStore: CredentialStore, @unchecked Sendable {
  private let lock = NSLock()
  private var data: Data?
  public init(_ data: Data? = nil) { self.data = data }
  public func read() throws -> Data? {
    lock.lock()
    defer { lock.unlock() }
    return data
  }
  public func write(_ data: Data) throws {
    lock.lock()
    defer { lock.unlock() }
    self.data = data
  }
  public func clear() throws {
    lock.lock()
    defer { lock.unlock() }
    data = nil
  }
}

/// A signed-in account. `generation` changes on every sign-in and sign-out; a client bound to an
/// older generation can no longer use credentials.
public struct MobileAccount: Hashable, Sendable {
  public let userID: Int64
  public let scopes: Set<String>
  public let generation: Int
  public init(userID: Int64, scopes: Set<String>, generation: Int) {
    self.userID = userID
    self.scopes = scopes
    self.generation = generation
  }
  public var canWriteTasks: Bool { scopes.contains(MobileSignInConfiguration.writeScope) }
  public var canEditOsm: Bool { canWriteTasks && scopes.contains(MobileSignInConfiguration.tagfixScope) }
}

/// A guest: answers are stored, not published, until the guest is claimed with an OSM account.
/// `generation` changes like `MobileAccount.generation`.
public struct MobileGuest: Hashable, Sendable {
  public let guestID: String
  public let generation: Int
  public init(guestID: String, generation: Int) {
    self.guestID = guestID
    self.generation = generation
  }
}

/// What `upgradeGuest()` found.
public enum GuestUpgrade: Hashable, Sendable {
  /// The guest was claimed: now signed in as this account; the guest credentials are gone.
  case signedIn(MobileAccount)
  /// Not claimed yet, or the backend can't issue write grants right now: still a guest; try later.
  case notYet
}

/// Why a sign-in did not finish. None of these carry server text or credentials.
public enum SignInFailure: Error, Hashable, Sendable {
  /// The web authentication session was canceled or failed.
  case canceled
  /// The callback was not the registered redirect, or its `state` did not match.
  case callbackMismatch
  /// The user declined on the provider's page (`access_denied`).
  case denied
  /// Another OAuth error code from the provider.
  case providerError(String)
  /// The token exchange failed or returned an unacceptable grant.
  case tokenExchange
  /// The new token could not be checked with `oauth/mobile/me`, or it did not match the grant.
  case accountLookup
  /// The credential store failed.
  case storage
  /// A newer sign-in or a sign-out started meanwhile; this result was discarded.
  case superseded
}

/// What `restore()` found in the credential store.
public enum RestoreResult: Hashable, Sendable {
  case signedIn(MobileAccount)
  /// A guest (no signed-in account).
  case guest(MobileGuest)
  case signedOut
  /// The app died during a token refresh, so the saved refresh token may be spent. Signed out.
  case refreshInterrupted
  /// The saved data was unreadable or belongs to another backend or client. Signed out.
  case unreadable
}

/// The result of `signOut()`.
public struct SignOutResult: Hashable, Sendable {
  /// The stored grant was removed. When false, it is gone from memory only.
  public let localCleared: Bool
  /// The backend confirmed the revocation. False when it failed or there was nothing to revoke.
  public let revocationConfirmed: Bool
  public init(localCleared: Bool, revocationConfirmed: Bool) {
    self.localCleared = localCleared
    self.revocationConfirmed = revocationConfirmed
  }
}

/// Browser sign-in for a mobile-enabled backend: OAuth authorization code with S256 PKCE, grant
/// storage, serialized refresh, and revocation. UI-free: the app opens the authorization URL in
/// an (ephemeral) web authentication session and passes the callback back.
///
/// A refresh token is used at most once. Before a refresh leaves the device, the store records it
/// as pending; if the app dies before the result is saved, `restore()` signs out instead of
/// retrying a token that may already be spent. A failed refresh signs out too.
public actor MobileSignIn {
  public nonisolated let configuration: MobileSignInConfiguration
  /// The signed-in account, nil when signed out.
  public private(set) var account: MobileAccount?
  /// The guest, when the app answers as a guest. Kept across sign-in and sign-out so that
  /// the guest's answers can still be claimed; `forgetGuest()` removes it.
  public private(set) var guest: MobileGuest?
  /// Bumped on every sign-in and sign-out.
  public private(set) var generation = 0

  private let store: any CredentialStore
  private let transport: any Transport
  private let now: @Sendable () -> Date
  private var grant: Grant?
  private var guestCredential: GuestCredential?
  private var guestToken: (value: String, expiresAt: Date)?
  private var refreshing: Task<Void, any Error>?

  public init(
    configuration: MobileSignInConfiguration, store: any CredentialStore,
    transport: any Transport = URLSessionTransport(), now: @escaping @Sendable () -> Date = Date.init
  ) {
    self.configuration = configuration
    self.store = store
    self.transport = transport
    self.now = now
  }

  // MARK: Persistence

  struct Grant: Codable, Sendable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var scopes: Set<String>
  }
  struct GuestCredential: Codable, Sendable {
    var id: String
    var secret: String
  }
  private struct Saved: Codable {
    var binding: String
    var grant: Grant?
    var userID: Int64?
    var refreshPending = false
    var guest: GuestCredential?
  }

  /// Loads the saved grant. Call once at launch, before handing out clients.
  @discardableResult
  public func restore() -> RestoreResult {
    do {
      guard let data = try store.read() else { return .signedOut }
      let saved = try JSONDecoder().decode(Saved.self, from: data)
      guard saved.binding == configuration.storageBinding else { throw SignInFailure.storage }
      guestCredential = saved.guest
      if saved.refreshPending {
        // The guest credential doesn't rotate, so it survives an interrupted refresh.
        try? invalidate(keepGuest: true)
        return guest.map(RestoreResult.guest) ?? .refreshInterrupted
      }
      guard let grant = saved.grant, let user = saved.userID, user > 0 else {
        generation += 1
        guard let credential = saved.guest else { return .signedOut }
        let guest = MobileGuest(guestID: credential.id, generation: generation)
        self.guest = guest
        return .guest(guest)
      }
      self.grant = grant
      generation += 1
      if let credential = saved.guest { guest = MobileGuest(guestID: credential.id, generation: generation) }
      let account = MobileAccount(userID: user, scopes: grant.scopes, generation: generation)
      self.account = account
      return .signedIn(account)
    } catch {
      try? store.clear()
      grant = nil
      account = nil
      guest = nil
      guestCredential = nil
      return .unreadable
    }
  }

  private func persist(refreshPending: Bool = false) throws {
    let saved = Saved(
      binding: configuration.storageBinding, grant: grant, userID: account?.userID, refreshPending: refreshPending,
      guest: guestCredential)
    try store.write(JSONEncoder().encode(saved))
  }

  /// Ends the current generation: older clients and in-flight requests lose their credentials.
  /// With `keepGuest`, the guest credential stays (and stays stored).
  private func invalidate(keepGuest: Bool = false) throws {
    generation += 1
    grant = nil
    account = nil
    refreshing = nil
    guestToken = nil
    if !keepGuest { guestCredential = nil }
    guest = guestCredential.map { MobileGuest(guestID: $0.id, generation: generation) }
    if guestCredential == nil { try store.clear() } else { try persist() }
  }

  private func check(_ expected: Int) throws {
    if expected != generation { throw CancellationError() }
  }

  // MARK: Sign-in

  /// Signs in with the browser. `authenticate` opens the URL in a web authentication session for
  /// `configuration.callbackScheme` and returns the callback URL; throwing from it means canceled.
  /// A current account is signed out (and revoked) first. Throws `SignInFailure`.
  public func signIn(
    scopes requested: Set<String>, authenticate: @Sendable (URL) async throws -> URL
  ) async throws -> MobileAccount {
    if account != nil { _ = await signOut() }
    try? invalidate(keepGuest: true)
    let expected = generation
    let verifier = Self.randomToken()
    let state = Self.randomToken()
    let scope = requested.sorted().joined(separator: " ")
    var url = URLComponents(url: configuration.authorizeURL, resolvingAgainstBaseURL: false)!
    url.queryItems = [
      URLQueryItem(name: "response_type", value: "code"),
      URLQueryItem(name: "client_id", value: configuration.clientID),
      URLQueryItem(name: "redirect_uri", value: configuration.redirectURI.absoluteString),
      URLQueryItem(name: "scope", value: scope),
      URLQueryItem(name: "state", value: state),
      URLQueryItem(name: "code_challenge", value: Self.challenge(verifier)),
      URLQueryItem(name: "code_challenge_method", value: "S256"),
    ]
    let callback: URL
    do {
      callback = try await authenticate(url.url!)
    } catch {
      throw generation == expected ? SignInFailure.canceled : SignInFailure.superseded
    }
    // An old browser result must not replace a newer sign-in.
    guard generation == expected else { throw SignInFailure.superseded }
    let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
    func item(_ name: String) -> String? { items.first { $0.name == name }?.value }
    guard configuration.accepts(callback: callback), item("state") == state else {
      throw SignInFailure.callbackMismatch
    }
    if let error = item("error") {
      throw error == "access_denied" ? SignInFailure.denied : SignInFailure.providerError(error)
    }
    guard let code = item("code"), !code.isEmpty else { throw SignInFailure.callbackMismatch }

    let next: Grant
    do {
      next = try await requestToken(
        [
          "grant_type": "authorization_code", "code": code,
          "redirect_uri": configuration.redirectURI.absoluteString,
          "client_id": configuration.clientID, "code_verifier": verifier,
        ], current: nil, requested: requested)
    } catch {
      throw generation == expected ? SignInFailure.tokenExchange : SignInFailure.superseded
    }
    guard generation == expected else { throw SignInFailure.superseded }
    let identity: UserIdentity
    do {
      let token = next.accessToken
      identity = try await MapRouletteClient(
        environment: configuration.environment, transport: transport, accessToken: { token }
      ).getCurrentUser()
    } catch {
      throw generation == expected ? SignInFailure.accountLookup : SignInFailure.superseded
    }
    guard generation == expected else { throw SignInFailure.superseded }
    guard !identity.guest, identity.id > 0, identity.scopes == next.scopes else {
      throw SignInFailure.accountLookup
    }
    grant = next
    generation += 1
    guestToken = nil
    let account = MobileAccount(userID: identity.id, scopes: next.scopes, generation: generation)
    self.account = account
    if let credential = guestCredential { guest = MobileGuest(guestID: credential.id, generation: generation) }
    do {
      try persist()
    } catch {
      try? invalidate(keepGuest: true)
      throw SignInFailure.storage
    }
    return account
  }

  /// A new grant: `tasks:read` plus a subset of what was requested; `osm:tagfix` only with
  /// `tasks:write`.
  public static func acceptableGrant(_ granted: Set<String>, requested: Set<String>) -> Bool {
    granted.contains(MobileSignInConfiguration.readScope) && requested.isSuperset(of: granted)
      && (!granted.contains(MobileSignInConfiguration.tagfixScope)
        || granted.contains(MobileSignInConfiguration.writeScope))
  }

  /// Token endpoint call; the response is validated before use.
  private func requestToken(_ form: [String: String], current: Grant?, requested: Set<String>) async throws -> Grant {
    try parseGrant(try await post(configuration.tokenURL, form), current: current, requested: requested)
  }

  private func parseGrant(_ response: HTTPResponse, current: Grant?, requested: Set<String>) throws -> Grant {
    guard response.status == 200,
      let o = try JSONSerialization.jsonObject(with: response.body) as? [String: Any],
      (o["token_type"] as? String)?.lowercased() == "bearer",
      let access = o["access_token"] as? String, !access.isEmpty,
      let refresh = o["refresh_token"] as? String, !refresh.isEmpty,
      let expiresIn = (o["expires_in"] as? NSNumber)?.doubleValue, expiresIn > 0
    else { throw SignInFailure.tokenExchange }
    let granted = Set(((o["scope"] as? String) ?? "").split(separator: " ").map(String.init))
    // A refresh must keep the grant's scope; a new grant may be narrower than requested.
    if let current {
      guard granted == current.scopes else { throw SignInFailure.tokenExchange }
    } else {
      guard Self.acceptableGrant(granted, requested: requested) else { throw SignInFailure.tokenExchange }
    }
    return Grant(
      accessToken: access, refreshToken: refresh, expiresAt: now().addingTimeInterval(expiresIn), scopes: granted)
  }

  // MARK: Tokens

  /// The access token for `generation`, refreshed when it expires within a minute; for a guest,
  /// a guest token (scope `guest`); nil when signed out. Throws `CancellationError` when the generation is stale. Refreshes are
  /// serialized; a failed refresh signs out.
  public func accessToken(generation expected: Int) async throws -> String? {
    try check(expected)
    if let refreshing { try await refreshing.value }
    try check(expected)
    guard let current = grant else { return try await currentGuestToken(expected) }
    if current.expiresAt.timeIntervalSince(now()) < 60 {
      let task = Task { try await self.refresh(current, expected) }
      refreshing = task
      defer { if refreshing == task { refreshing = nil } }
      try await task.value
    }
    try Task.checkCancellation()
    try check(expected)
    return grant?.accessToken
  }

  private func refresh(_ current: Grant, _ expected: Int) async throws {
    do {
      // Record the uncertainty before a single-use refresh token leaves the device.
      try persist(refreshPending: true)
      let next = try await requestToken(
        ["grant_type": "refresh_token", "client_id": configuration.clientID, "refresh_token": current.refreshToken],
        current: current, requested: [])
      try check(expected)
      grant = next
      try persist()
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      if generation == expected { try? invalidate(keepGuest: true) }
      throw MapRouletteError(.authentication)
    }
  }

  /// One forced refresh after the server rejected the token in `usedHeader` (`Bearer …`). Does
  /// nothing when the token was already replaced or a refresh is running.
  public func renewAfterRejection(generation expected: Int, usedHeader: String) async {
    if grant == nil, generation == expected, let token = guestToken, usedHeader == "Bearer \(token.value)" {
      guestToken = nil
      return
    }
    guard generation == expected, let current = grant, usedHeader == "Bearer \(current.accessToken)",
      refreshing == nil
    else { return }
    grant?.expiresAt = .distantPast
    _ = try? await accessToken(generation: expected)
  }

  /// Signs out: clears locally first (older clients lose their credentials at once), then asks
  /// the backend to revoke the grant. A guest credential stays; see `forgetGuest()`.
  @discardableResult
  public func signOut() async -> SignOutResult {
    let token = grant?.refreshToken ?? grant?.accessToken
    let localCleared = (try? invalidate(keepGuest: true)) != nil
    var confirmed = false
    if let token {
      let result = try? await post(configuration.revokeURL, ["client_id": configuration.clientID, "token": token])
      confirmed = result.map { (200...299).contains($0.status) } ?? false
    }
    return SignOutResult(localCleared: localCleared, revocationConfirmed: confirmed)
  }

  // MARK: Guests

  /// Registers a guest (`POST /oauth/mobile/guest`) when nobody is signed in and there is no
  /// guest yet; otherwise returns the current guest. The secret goes to the credential store and
  /// never leaves this actor. Throws `MapRouletteError`: `.notFound` when the backend has guests
  /// off, `.rateLimit` (with `retryAfter`), `.authentication` for a client without guest access.
  public func startGuest() async throws -> MobileGuest {
    if let guest { return guest }
    guard account == nil else {
      throw MapRouletteError(.validation, reason: "sign out before starting a guest")
    }
    let expected = generation
    let response = try await post(configuration.guestURL, ["client_id": configuration.clientID])
    guard response.status == 201 else { throw Self.oauthFailure(response) }
    guard let o = try? JSONSerialization.jsonObject(with: response.body) as? [String: Any],
      let id = o["guestId"] as? String, id.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil,
      let secret = o["guestSecret"] as? String, Self.isToken(secret)
    else { throw MapRouletteError(.protocolFailure) }
    // A sign-in or another guest started meanwhile.
    guard generation == expected, account == nil, self.guest == nil else { throw CancellationError() }
    guestCredential = GuestCredential(id: id, secret: secret)
    generation += 1
    let guest = MobileGuest(guestID: id, generation: generation)
    self.guest = guest
    do {
      try persist()
    } catch {
      try? invalidate()
      throw SignInFailure.storage
    }
    return guest
  }

  /// Signs in a claimed guest (`guest_claim` grant): when the guest's answers were claimed on the
  /// web, the phone becomes that account and the guest credentials are dropped. `.notYet` while
  /// unclaimed or while the backend issues no write grants.
  public func upgradeGuest() async throws -> GuestUpgrade {
    guard account == nil, let credential = guestCredential else {
      throw MapRouletteError(.validation, reason: "no guest to upgrade")
    }
    let expected = generation
    let requested = MobileSignInConfiguration.scopes(writes: true)
    let response = try await post(
      configuration.tokenURL,
      [
        "grant_type": "urn:maproulette:grant-type:guest_claim", "client_id": configuration.clientID,
        "guest_id": credential.id, "guest_secret": credential.secret,
      ])
    if response.status == 400, ["claim_pending", "invalid_scope"].contains(Self.oauthError(response.body)) {
      return .notYet
    }
    guard response.status == 200 else { throw Self.oauthFailure(response) }
    let next = try parseGrant(response, current: nil, requested: requested)
    try check(expected)
    let token = next.accessToken
    let identity = try await MapRouletteClient(
      environment: configuration.environment, transport: transport, accessToken: { token }
    ).getCurrentUser()
    try check(expected)
    guard !identity.guest, identity.id > 0, identity.scopes == next.scopes else {
      throw SignInFailure.accountLookup
    }
    grant = next
    guestCredential = nil
    guestToken = nil
    guest = nil
    generation += 1
    let account = MobileAccount(userID: identity.id, scopes: next.scopes, generation: generation)
    self.account = account
    do {
      try persist()
    } catch {
      try? invalidate()
      throw SignInFailure.storage
    }
    return .signedIn(account)
  }

  /// Drops the guest credential, e.g. after `MapRouletteClient.deleteGuest()`. Unclaimed answers
  /// can no longer be reached from this phone. A signed-in account stays.
  public func forgetGuest() throws {
    guestCredential = nil
    guestToken = nil
    guest = nil
    if grant == nil {
      try invalidate()
    } else {
      try persist()
    }
  }

  /// A short-lived guest token (`guest` grant), fetched again when it expires within a minute.
  private func currentGuestToken(_ expected: Int) async throws -> String? {
    guard let credential = guestCredential else { return nil }
    if let token = guestToken, token.expiresAt.timeIntervalSince(now()) >= 60 { return token.value }
    let task = Task { () throws -> Void in
      let response = try await self.post(
        self.configuration.tokenURL,
        [
          "grant_type": "urn:maproulette:grant-type:guest", "client_id": self.configuration.clientID,
          "guest_id": credential.id, "guest_secret": credential.secret,
        ])
      try await self.receivedGuestToken(response, expected)
    }
    refreshing = task
    defer { if refreshing == task { refreshing = nil } }
    try await task.value
    try Task.checkCancellation()
    try check(expected)
    return guestToken?.value
  }

  private func receivedGuestToken(_ response: HTTPResponse, _ expected: Int) throws {
    try check(expected)
    if response.status == 400, Self.oauthError(response.body) == "guest_claimed" {
      // Answers were claimed: run `upgradeGuest()`.
      throw MapRouletteError(.conflict, status: 400, reason: "guest_claimed")
    }
    guard response.status == 200 else {
      if response.status == 400 || response.status == 401 {
        // Unknown, deleted or expired guest: the credential is useless.
        try? forgetGuest()
      }
      throw MapRouletteError(.authentication, status: response.status, reason: Self.oauthError(response.body))
    }
    guard let o = try? JSONSerialization.jsonObject(with: response.body) as? [String: Any],
      (o["token_type"] as? String)?.lowercased() == "bearer",
      let access = o["access_token"] as? String, Self.isToken(access),
      let expiresIn = (o["expires_in"] as? NSNumber)?.doubleValue, expiresIn > 0,
      (o["scope"] as? String) == "guest"
    else { throw MapRouletteError(.protocolFailure) }
    guestToken = (access, now().addingTimeInterval(expiresIn))
  }

  private static func isToken(_ value: String) -> Bool {
    value.range(of: "^[A-Za-z0-9_-]{20,200}$", options: .regularExpression) != nil
  }

  /// The OAuth `error` code, only when it is a plain code.
  private static func oauthError(_ body: Data) -> String? {
    guard let code = ((try? JSONSerialization.jsonObject(with: body)) as? [String: Any])?["error"] as? String,
      code.range(of: "^[a-z_]{1,40}$", options: .regularExpression) != nil
    else { return nil }
    return code
  }

  private static func oauthFailure(_ response: HTTPResponse) -> MapRouletteError {
    let kind: ErrorKind
    switch response.status {
    case 400, 401: kind = .authentication
    case 403: kind = .permission
    case 404: kind = .notFound
    case 409: kind = .conflict
    case 429: kind = .rateLimit
    case 500...599: kind = .server
    default: kind = .http
    }
    return MapRouletteError(
      kind, status: response.status,
      retryAfter: response.headers.first { $0.key.lowercased() == "retry-after" }?.value,
      reason: oauthError(response.body))
  }

  // MARK: Clients

  /// A client bound to the current generation: after a sign-out or account switch its requests
  /// fail with `CancellationError` before sending. A 401 triggers one refresh for the next
  /// request (never a resend). Wrap `transport` to add your own checks.
  public func client(
    environment: MapRouletteEnvironment? = nil, transport: (any Transport)? = nil, allowElementDeletion: Bool = false
  ) -> MapRouletteClient {
    let expected = generation
    let bound = BoundTransport(signIn: self, generation: expected, inner: transport ?? self.transport)
    return MapRouletteClient(
      environment: environment ?? configuration.environment, transport: bound,
      allowElementDeletion: allowElementDeletion,
      accessToken: { [weak self] in try await self?.accessToken(generation: expected) })
  }

  fileprivate func admit(_ expected: Int) throws { try check(expected) }

  fileprivate func received(_ request: HTTPRequest, _ response: HTTPResponse, _ expected: Int) async throws {
    try check(expected)
    // osm_reauth_required concerns the backend's OSM token, not this grant: no refresh.
    if response.status == 401, !Self.isOsmReauth(response.body), let header = request.headers["Authorization"] {
      await renewAfterRejection(generation: expected, usedHeader: header)
    }
  }

  static func isOsmReauth(_ body: Data) -> Bool {
    ((try? JSONSerialization.jsonObject(with: body)) as? [String: Any])?["error"] as? String
      == "osm_reauth_required"
  }

  // MARK: Helpers

  /// Form POST to an OAuth endpoint at the configured origin.
  private func post(_ url: URL, _ form: [String: String]) async throws -> HTTPResponse {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    let body = form.sorted { $0.key < $1.key }.map {
      "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
    }.joined(separator: "&")
    let response = try await transport.execute(
      HTTPRequest(
        url: url,
        headers: ["Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"],
        method: .post, body: Data(body.utf8)))
    return response
  }

  static func randomToken() -> String {
    var generator = SystemRandomNumberGenerator()
    return base64URL(Data((0..<32).map { _ in UInt8.random(in: .min ... .max, using: &generator) }))
  }

  /// The S256 code challenge (RFC 7636) for `verifier`.
  public static func challenge(_ verifier: String) -> String {
    base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
  }

  private static func base64URL(_ data: Data) -> String {
    data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
  }
}

/// Binds requests to one sign-in generation and renews the token after a 401.
private struct BoundTransport: Transport {
  let signIn: MobileSignIn
  let generation: Int
  let inner: any Transport

  func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
    try await signIn.admit(generation)
    let response = try await inner.execute(request)
    try await signIn.received(request, response, generation)
    return response
  }
}
