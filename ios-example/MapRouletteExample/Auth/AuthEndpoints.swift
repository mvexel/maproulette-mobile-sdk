import Foundation

/// Build configuration. Debug builds read it from Info.plist (set by build settings); Release builds
/// keep the anonymous production defaults, like the Android release build.
struct AppConfig: Sendable {
  let baseURL: String
  let clientID: String
  let allowLoopback: Bool

  static let production = AppConfig(baseURL: "https://maproulette.org", clientID: "", allowLoopback: false)

  static var current: AppConfig {
    #if DEBUG
      let info = Bundle.main.infoDictionary ?? [:]
      func value(_ key: String) -> String {
        (info[key] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
      }
      let base = value("MRBaseURL")
      return AppConfig(
        baseURL: base.isEmpty ? production.baseURL : base, clientID: value("MROAuthClientID"),
        allowLoopback: value("MRAllowLoopback") == "YES")
    #else
      return production
    #endif
  }
}

/// One backend origin owns both credentials and SDK data; no discovery or redirect guessing.
struct AuthEndpoints: Sendable {
  struct InvalidConfiguration: Error {}

  static let redirect = "org.maproulette.example:/oauth2redirect"
  static let callbackScheme = "org.maproulette.example"

  let origin: String
  let clientID: String
  let loopbackAllowed: Bool
  private let scheme: String, host: String, port: Int?

  var enabled: Bool { !clientID.isEmpty }
  var api: URL { URL(string: origin + "/api/v2/")! }
  var authorize: URL { URL(string: origin + "/oauth/mobile/authorize")! }
  var token: URL { URL(string: origin + "/oauth/mobile/token")! }
  var revoke: URL { URL(string: origin + "/oauth/mobile/revoke")! }
  var storageBinding: String { "\(origin)|\(clientID)" }

  init(baseURL: String, clientID: String, allowLoopback: Bool) throws {
    guard let c = URLComponents(string: baseURL), let scheme = c.scheme?.lowercased(),
      let host = c.host?.lowercased(), !host.isEmpty, c.user == nil, c.password == nil,
      c.query == nil, c.fragment == nil, c.path.isEmpty || c.path == "/"
    else { throw InvalidConfiguration() }
    let loopback = host == "127.0.0.1" || host == "localhost"
    guard scheme == "https" || (allowLoopback && loopback && scheme == "http") else {
      throw InvalidConfiguration()
    }
    guard clientID.isEmpty || clientID.range(of: "^[A-Za-z0-9._-]{1,100}$", options: .regularExpression) != nil
    else { throw InvalidConfiguration() }
    self.scheme = scheme
    self.host = host
    self.port = c.port
    self.clientID = clientID
    self.loopbackAllowed = allowLoopback
    origin = "\(scheme)://\(host)" + (c.port.map { ":\($0)" } ?? "")
  }

  /// Authentication requests go only to the configured origin.
  func permitsConnection(_ url: URL) -> Bool {
    guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
    return c.scheme?.lowercased() == scheme && c.host?.lowercased() == host && c.port == port
      && c.user == nil && c.password == nil && c.fragment == nil
  }

  /// The browser callback must be exactly the registered redirect (query allowed, no fragment).
  func acceptsCallback(_ url: URL) -> Bool {
    guard let actual = URLComponents(url: url, resolvingAgainstBaseURL: false),
      let expected = URLComponents(string: Self.redirect)
    else { return false }
    return actual.scheme == expected.scheme && actual.host == expected.host
      && actual.port == expected.port && actual.path == expected.path && actual.fragment == nil
  }
}
