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

    /// The JS-to-native compatibility token this binary declares.
    ///
    /// Read from Info.plist, deliberately not from JS config — if the token
    /// lived in JavaScript, an OTA bundle could bump its own runtime and pull
    /// in bundles built against a native contract this binary does not have,
    /// which is exactly what the token exists to prevent.
    ///
    ///     <key>OTARuntimeVersion</key><string>1</string>
    ///
    /// Falls back to "1" but logs, because silently defaulting is how a device
    /// ends up stranded on a runtime nothing is published to.
    static func runtimeVersion() -> String {
        if let value = Bundle.main.infoDictionary?["OTARuntimeVersion"] as? String,
           !value.trimmingCharacters(in: .whitespaces).isEmpty {
            return value
        }
        // Xcode will store a bare numeric plist value as a number, not a string.
        if let number = Bundle.main.infoDictionary?["OTARuntimeVersion"] as? NSNumber {
            return number.stringValue
        }
        NSLog("[OTA] no OTARuntimeVersion in Info.plist — defaulting to \"1\"")
        return "1"
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
