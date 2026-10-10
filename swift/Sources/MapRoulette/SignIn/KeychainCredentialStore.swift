#if canImport(Security)
  import CryptoKit
  import Foundation
  import Security

  /// Keeps the grant in the Keychain as one generic-password item per backend and client, readable
  /// on this device only, after first unlock. Not synced, not in backups. Failures throw
  /// `SignInFailure.storage`.
  public struct KeychainCredentialStore: CredentialStore {
    private let service: String
    private let account: String

    /// - Parameters:
    ///   - service: your app's Keychain service name, e.g. `com.example.app.auth`.
    ///   - binding: `MobileSignInConfiguration.storageBinding`; hashed into the item's account.
    public init(service: String, binding: String) {
      self.service = service
      account = SHA256.hash(data: Data(binding.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private var query: [String: Any] {
      [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account,
      ]
    }

    public func read() throws -> Data? {
      var q = query
      q[kSecReturnData as String] = true
      q[kSecMatchLimit as String] = kSecMatchLimitOne
      var item: CFTypeRef?
      let status = SecItemCopyMatching(q as CFDictionary, &item)
      if status == errSecItemNotFound { return nil }
      guard status == errSecSuccess, let data = item as? Data else { throw SignInFailure.storage }
      return data
    }

    public func write(_ data: Data) throws {
      let attributes: [String: Any] = [
        kSecValueData as String: data,
        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
      ]
      var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
      if status == errSecItemNotFound {
        status = SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
      }
      guard status == errSecSuccess else { throw SignInFailure.storage }
    }

    public func clear() throws {
      let status = SecItemDelete(query as CFDictionary)
      guard status == errSecSuccess || status == errSecItemNotFound else { throw SignInFailure.storage }
    }
  }
#endif
