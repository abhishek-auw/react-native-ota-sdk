import Foundation
import CommonCrypto
import Security

/// SHA-256 hashing, ECDSA signature verification — all using Apple's built-in APIs.
/// No third-party dependencies — ships with every iOS device.
enum HashVerifier {

    /// Compute SHA-256 of a file. Reads in 64KB chunks — handles large bundles.
    static func computeFileHash(at url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url)
        defer { file.closeFile() }

        var context = CC_SHA256_CTX()
        CC_SHA256_Init(&context)

        let chunkSize = 64 * 1024
        while true {
            let chunk = file.readData(ofLength: chunkSize)
            if chunk.isEmpty { break }
            chunk.withUnsafeBytes { ptr in
                _ = CC_SHA256_Update(&context, ptr.baseAddress, CC_LONG(chunk.count))
            }
        }

        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        CC_SHA256_Final(&digest, &context)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Compute SHA-256 of in-memory data and return a hex string.
    /// Used to verify each file reconstructed from a delta patch.
    static func sha256Hex(_ data: Data) -> String {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { ptr in
            _ = CC_SHA256(ptr.baseAddress, CC_LONG(data.count), &digest)
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Verify a file's hash using constant-time comparison.
    static func verifyFile(at url: URL, expectedHash: String) -> Bool {
        guard !expectedHash.isEmpty else { return false }
        guard let actual = try? computeFileHash(at: url) else { return false }
        return constantTimeEqual(actual, expectedHash.lowercased())
    }

    // MARK: - ECDSA P-256 Signature Verification

    /// Verify an ECDSA-SHA256 P-256 signature over the raw bytes of a file.
    ///
    /// - Parameters:
    ///   - url:           URL of the downloaded bundle ZIP.
    ///   - signatureB64:  Base-64 DER signature produced by the CI signing step.
    ///   - publicKeyPem:  PEM SPKI public key embedded in the app at build time.
    /// - Returns: `true` if the signature is valid, `false` otherwise.
    static func verifyEcdsaSignature(at url: URL,
                                     signatureB64: String,
                                     publicKeyPem: String) -> Bool {
        guard let sigData = Data(base64Encoded: signatureB64) else { return false }

        // Strip PEM header/footer and decode DER
        let stripped = publicKeyPem
            .replacingOccurrences(of: "-----BEGIN PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----END PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .trimmingCharacters(in: .whitespaces)
        guard let derData = Data(base64Encoded: stripped) else { return false }

        // Import the public key using SecKey
        let attributes: [String: Any] = [
            kSecAttrKeyType as String:       kSecAttrKeyTypeEC,
            kSecAttrKeyClass as String:      kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: 256,
        ]
        var error: Unmanaged<CFError>?
        guard let publicKey = SecKeyCreateWithData(derData as CFData, attributes as CFDictionary, &error)
        else { return false }

        // Read the file into memory
        guard let fileData = try? Data(contentsOf: url) else { return false }

        // Verify with SecKeyVerifySignature (SHA256 is implicit for ecdsaSignatureMessageX962SHA256)
        let algorithm = SecKeyAlgorithm.ecdsaSignatureMessageX962SHA256
        guard SecKeyIsAlgorithmSupported(publicKey, .verify, algorithm) else { return false }

        return SecKeyVerifySignature(
            publicKey,
            algorithm,
            fileData as CFData,
            sigData as CFData,
            &error
        )
    }

    // MARK: - Helpers

    /// Constant-time string comparison — prevents timing side-channels.
    private static func constantTimeEqual(_ a: String, _ b: String) -> Bool {
        guard a.count == b.count else { return false }
        var result: UInt8 = 0
        zip(a.utf8, b.utf8).forEach { result |= $0 ^ $1 }
        return result == 0
    }
}
