import Foundation

/// The SDK version, sent in the User-Agent. It must equal the repository's `VERSION` file;
/// a test checks this.
public enum MapRouletteSDK {
  public static let version = "0.1.0"
}

/// The MapRoulette deployment a client talks to.
///
/// Reads work against any environment. In SDK 0.x, task writes (lock, release, skip, status and
/// choice submission) go only to the disposable staging deployment, to a loopback server, or to a
/// custom environment created with `allowWrites: true`; see `allowsWrites`. Production
/// MapRoulette (`maproulette.org` and its subdomains) is always read-only.
public struct MapRouletteEnvironment: Hashable, Sendable, CustomStringConvertible {
  /// The API base, e.g. `https://maproulette.org/api/v2/`.
  public let serviceURL: URL
  private let allowWrites: Bool

  /// Public production MapRoulette. Reads only: this SDK version refuses writes here.
  public static let production = MapRouletteEnvironment(
    trusted: URL(string: "https://maproulette.org/api/v2/")!)
  /// The disposable staging deployment (fork backend, dev OSM, separate database). Writes allowed.
  public static let staging = MapRouletteEnvironment(
    trusted: URL(string: "https://mr-api.osm.lol/api/v2/")!)

  /// A custom deployment; `serviceURL` is stored ending in "/". The URL must be https (or http
  /// on a loopback host) with a host and no credentials, query or fragment.
  ///
  /// - Parameter allowWrites: opt in to task writes for your own mobile-enabled backend. Passing
  ///   `true` for a `maproulette.org` or `*.maproulette.org` host throws a `.validation` error.
  public init(serviceURL: URL, allowWrites: Bool = false) throws {
    guard let c = URLComponents(url: serviceURL, resolvingAgainstBaseURL: false),
      c.scheme == "https" || (c.scheme == "http" && isLoopback(c.host)),
      c.host?.isEmpty == false, (c.port ?? 0) <= 65535, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil
    else {
      throw MapRouletteError(
        .validation,
        reason: "serviceURL must be https (or http on loopback) with a host and no credentials, query or fragment")
    }
    if allowWrites && isProductionMapRoulette(c.host) {
      throw MapRouletteError(
        .validation,
        reason: "allowWrites is not supported for production MapRoulette (maproulette.org): it has no mobile routes, "
          + "and this SDK never writes there")
    }
    self.allowWrites = allowWrites
    // Always end in "/" (like Kotlin), so equal bases compare equal.
    self.serviceURL =
      serviceURL.absoluteString.hasSuffix("/") ? serviceURL : serviceURL.appendingPathComponent("")
  }

  private init(trusted url: URL) {
    serviceURL = url
    allowWrites = false
  }

  /// Whether this SDK version sends task writes here: to the staging origin
  /// (`https://mr-api.osm.lol`), to loopback hosts (local servers and tests), and to a custom
  /// environment created with `allowWrites: true`. Never to production MapRoulette.
  public var allowsWrites: Bool {
    guard let c = URLComponents(url: serviceURL, resolvingAgainstBaseURL: false) else { return false }
    if isLoopback(c.host) || allowWrites { return true }
    return c.scheme == "https" && c.host?.lowercased() == "mr-api.osm.lol" && (c.port ?? 443) == 443
  }

  public var description: String { serviceURL.absoluteString }
}

func isLoopback(_ host: String?) -> Bool {
  ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host?.lowercased() ?? "")
}

/// `maproulette.org` or any subdomain, ignoring case and a trailing dot.
func isProductionMapRoulette(_ host: String?) -> Bool {
  var name = host?.lowercased() ?? ""
  while name.hasSuffix(".") { name.removeLast() }
  return name == "maproulette.org" || name.hasSuffix(".maproulette.org")
}
