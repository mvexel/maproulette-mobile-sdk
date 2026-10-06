import Foundation

/// The SDK version, sent in the User-Agent. It must equal the repository's `VERSION` file;
/// a test checks this.
public enum MapRouletteSDK {
  public static let version = "0.1.0"
}

/// The MapRoulette deployment a client talks to.
///
/// Before 1.0, task writes (lock, release, skip, status and choice submission) go only to the
/// disposable staging deployment or to a loopback server; see `allowsWrites`. Reads work against
/// any environment.
public struct MapRouletteEnvironment: Hashable, Sendable, CustomStringConvertible {
  /// The API base, e.g. `https://maproulette.org/api/v2/`.
  public let serviceURL: URL

  /// Public production MapRoulette. Reads only: this SDK version refuses writes here.
  public static let production = MapRouletteEnvironment(
    trusted: URL(string: "https://maproulette.org/api/v2/")!)
  /// The disposable staging deployment (fork backend, dev OSM, separate database). Writes allowed.
  public static let staging = MapRouletteEnvironment(
    trusted: URL(string: "https://mr-api.osm.lol/api/v2/")!)

  /// A custom deployment; `serviceURL` is stored ending in "/". The URL must be https (or http
  /// on a loopback host) with a host and no credentials, query or fragment.
  public init(serviceURL: URL) throws {
    guard let c = URLComponents(url: serviceURL, resolvingAgainstBaseURL: false),
      c.scheme == "https" || (c.scheme == "http" && isLoopback(c.host)),
      c.host?.isEmpty == false, (c.port ?? 0) <= 65535, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil
    else {
      throw MapRouletteError(
        .validation,
        reason: "serviceURL must be https (or http on loopback) with a host and no credentials, query or fragment")
    }
    // Always end in "/" (like Kotlin), so equal bases compare equal.
    self.serviceURL =
      serviceURL.absoluteString.hasSuffix("/") ? serviceURL : serviceURL.appendingPathComponent("")
  }

  private init(trusted url: URL) { serviceURL = url }

  /// Whether this SDK version sends task writes here: only to the staging origin
  /// (`https://mr-api.osm.lol`) and to loopback hosts (local servers and tests).
  public var allowsWrites: Bool {
    guard let c = URLComponents(url: serviceURL, resolvingAgainstBaseURL: false) else { return false }
    if isLoopback(c.host) { return true }
    return c.scheme == "https" && c.host?.lowercased() == "mr-api.osm.lol" && (c.port ?? 443) == 443
  }

  public var description: String { serviceURL.absoluteString }
}

func isLoopback(_ host: String?) -> Bool {
  ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host?.lowercased() ?? "")
}
