import CryptoKit
import Foundation
import Security

/// Credentials live only in the Keychain (this device only, after first unlock), one item per
/// backend origin and client ID. Never in UserDefaults or logs.
struct KeychainStore: Sendable {
  struct Failure: Error {}

  private let service = "org.maproulette.example.auth"
  private let account: String

  init(binding: String) {
    account = SHA256.hash(data: Data(binding.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  private var query: [String: Any] {
    [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
  }

  func read() throws -> Data? {
    var q = query
    q[kSecReturnData as String] = true
    q[kSecMatchLimit as String] = kSecMatchLimitOne
    var item: CFTypeRef?
    let status = SecItemCopyMatching(q as CFDictionary, &item)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = item as? Data else { throw Failure() }
    return data
  }

  func write(_ data: Data) throws {
    let attributes: [String: Any] = [
      kSecValueData as String: data,
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    if status == errSecItemNotFound {
      status = SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
    }
    guard status == errSecSuccess else { throw Failure() }
  }

  func clear() throws {
    let status = SecItemDelete(query as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure() }
  }
}
