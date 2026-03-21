import Foundation
import CommonCrypto

/// SHA-256 hashing and verification using Apple's CommonCrypto.
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

    /// Verify a file's hash using constant-time comparison.
    static func verifyFile(at url: URL, expectedHash: String) -> Bool {
        guard !expectedHash.isEmpty else { return false }
        guard let actual = try? computeFileHash(at: url) else { return false }
        return constantTimeEqual(actual, expectedHash.lowercased())
    }

    /// Constant-time string comparison — prevents timing side-channels.
    private static func constantTimeEqual(_ a: String, _ b: String) -> Bool {
        guard a.count == b.count else { return false }
        var result: UInt8 = 0
        zip(a.utf8, b.utf8).forEach { result |= $0 ^ $1 }
        return result == 0
    }
}
