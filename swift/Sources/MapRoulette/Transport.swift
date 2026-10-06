import Foundation

public enum HTTPMethod: String, Sendable { case get = "GET", put = "PUT", post = "POST" }
public struct HTTPRequest: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
  public var description: String { "HTTPRequest(\(method.rawValue))" }
  public var debugDescription: String { description }
  public let url: URL, headers: [String: String]
  public let method: HTTPMethod
  public let body: Data?
  public init(url: URL, headers: [String: String], method: HTTPMethod = .get, body: Data? = nil) {
    self.url = url
    self.headers = headers
    self.method = method
    self.body = body
  }
}
public struct HTTPResponse: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
  public var description: String { "HTTPResponse(status: \(status))" }
  public var debugDescription: String { description }
  public let status: Int, headers: [String: String], body: Data
  public init(status: Int, headers: [String: String] = [:], body: Data) {
    self.status = status
    self.headers = headers
    self.body = body
  }
}
/// Custom transports must honor cancellation and never forward credentials on redirects.
public protocol Transport: Sendable {
  func execute(_ request: HTTPRequest) async throws -> HTTPResponse
}

private final class NoRedirect: NSObject, URLSessionTaskDelegate, Sendable {
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping @Sendable (URLRequest?) -> Void
  ) { completionHandler(nil) }
}
public final class URLSessionTransport: Transport {
  private let session: URLSession
  public init() {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 30
    configuration.timeoutIntervalForResource = 60
    configuration.urlCache = nil
    configuration.httpCookieStorage = nil
    configuration.urlCredentialStorage = nil
    session = URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
  }
  deinit { session.invalidateAndCancel() }
  public func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
    var native = URLRequest(url: request.url)
    native.httpMethod = request.method.rawValue
    native.httpBody = request.body
    native.allHTTPHeaderFields = request.headers
    let (data, response) = try await session.data(for: native)
    guard let response = response as? HTTPURLResponse else { throw MapRouletteError(.network) }
    let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, pair in
      result[String(describing: pair.key)] = String(describing: pair.value)
    }
    return HTTPResponse(status: response.statusCode, headers: headers, body: data)
  }
}
