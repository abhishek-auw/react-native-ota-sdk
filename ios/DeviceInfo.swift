import Foundation
import UIKit
import CommonCrypto

/// Privacy-safe device identifiers — no raw IDFA or IDFV exposed.
enum DeviceInfo {

    /// Returns a stable SHA-256 hash of the vendor identifier.
    /// Never exposes the raw UUID to the server.
    static func deviceHash() -> String {
        let raw = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        return sha256(raw)
    }

    /// Returns the app's CFBundleShortVersionString (e.g. "1.2.3").
    static func appVersion() -> String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    // MARK: - Private

    private static func sha256(_ string: String) -> String {
        let data = Data(string.utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &digest) }
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
