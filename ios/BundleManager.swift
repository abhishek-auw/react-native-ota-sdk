import Foundation
import ZIPFoundation

/// Manages bundle storage and switching on iOS.
///
/// Storage location (app-private Documents, excluded from iCloud backup):
///   <Documents>/ota/bundles/<hash>/main.jsbundle
class BundleManager {

    /// Delta patch format this SDK understands. Must match
    /// DELTA_MANIFEST_VERSION in packages/backend/src/services/DeltaService.ts.
    /// A mismatch makes the patch fail cleanly and the caller fall back to a
    /// full download.
    static let deltaManifestVersion = 2

    private let prefs: OTAPrefs

    private var otaRoot: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let root = docs.appendingPathComponent("ota/bundles")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    init(prefs: OTAPrefs) {
        self.prefs = prefs
    }

    /// Destination for a downloaded ZIP before extraction.
    func zipURL(forHash hash: String) -> URL {
        otaRoot.appendingPathComponent("\(hash)/download.zip")
    }

    /// Extract ZIP and store the JS bundle. Returns the bundle file path.
    func storePendingBundle(zipURL: URL, hash: String, bundleId: String) throws -> String {
        let bundleDir = otaRoot.appendingPathComponent(hash)
        try FileManager.default.createDirectory(at: bundleDir, withIntermediateDirectories: true)

        // Unzip
        try FileManager.default.unzipItem(at: zipURL, to: bundleDir)
        try? FileManager.default.removeItem(at: zipURL)

        // Find main JS bundle
        guard let bundleFile = findBundleFile(in: bundleDir) else {
            throw NSError(domain: "OTABundleManager", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No JS bundle found in ZIP"])
        }

        let bundlePath = bundleFile.path
        prefs.pendingBundlePath = bundlePath
        prefs.pendingBundleHash = hash
        prefs.pendingBundleId   = bundleId

        NSLog("[OTA] Pending bundle stored: %@", bundlePath)
        return bundlePath
    }

    /// Apply a delta patch ZIP on top of an existing bundle directory.
    ///
    /// The patch ZIP (produced by DeltaService on the server) contains:
    /// - `_delta_manifest.json` — version, per-file entries and deletions
    /// - `bin/<n>` — bsdiff patches, applied against the base file
    /// - `raw/<n>` — whole replacement files
    ///
    /// Most changed files arrive as bsdiff patches, so a one-line JS edit in a
    /// multi-megabyte bundle is a few kilobytes on the wire.
    ///
    /// Throws on any inconsistency; the caller discards the partial result and
    /// falls back to downloading the full bundle.
    func applyDeltaPatch(patchZipURL: URL, fromHash: String, toHash: String, bundleId: String) throws -> String {
        let fm = FileManager.default
        let baseBundleDir = otaRoot.appendingPathComponent(fromHash)
        guard fm.fileExists(atPath: baseBundleDir.path) else {
            throw NSError(domain: "OTABundleManager", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "Base bundle dir not found for hash \(fromHash)"])
        }

        let targetDir = otaRoot.appendingPathComponent(toHash)
        let scratchDir = otaRoot.appendingPathComponent("patch-\(toHash)")

        do {
            try fm.createDirectory(at: targetDir, withIntermediateDirectories: true)
            try fm.createDirectory(at: scratchDir, withIntermediateDirectories: true)

            // 1. Seed target with all files from base
            let baseContents = try fm.contentsOfDirectory(at: baseBundleDir, includingPropertiesForKeys: nil)
            for item in baseContents {
                let dest = targetDir.appendingPathComponent(item.lastPathComponent)
                if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
                try fm.copyItem(at: item, to: dest)
            }

            // 2. Unpack the patch somewhere separate — its entry names
            //    (bin/0, raw/1) are not bundle paths and must not land in the
            //    bundle directory.
            try fm.unzipItem(at: patchZipURL, to: scratchDir)
            try? fm.removeItem(at: patchZipURL)

            let manifestURL = scratchDir.appendingPathComponent("_delta_manifest.json")
            guard fm.fileExists(atPath: manifestURL.path),
                  let manifestData = try? Data(contentsOf: manifestURL),
                  let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any] else {
                throw NSError(domain: "OTABundleManager", code: 4,
                              userInfo: [NSLocalizedDescriptionKey: "Patch is missing or has an unreadable _delta_manifest.json"])
            }

            let version = manifest["version"] as? Int ?? 1
            guard version == BundleManager.deltaManifestVersion else {
                throw NSError(domain: "OTABundleManager", code: 5,
                              userInfo: [NSLocalizedDescriptionKey:
                                "Unsupported delta manifest version \(version) (this SDK understands \(BundleManager.deltaManifestVersion))"])
            }

            // 3. Reconstruct every changed file
            if let files = manifest["files"] as? [[String: Any]] {
                for entry in files {
                    try applyDeltaFile(entry: entry,
                                       baseDir: baseBundleDir,
                                       scratchDir: scratchDir,
                                       targetDir: targetDir)
                }
            }

            // 4. Deletions
            if let deleted = manifest["deleted"] as? [String] {
                for relativePath in deleted {
                    let victim = try safeChild(of: targetDir, relativePath: relativePath)
                    if fm.fileExists(atPath: victim.path) {
                        try? fm.removeItem(at: victim)
                        NSLog("[OTA] Delta: deleted %@", relativePath)
                    }
                }
            }

            // 5. Find JS bundle
            guard let bundleFile = findBundleFile(in: targetDir) else {
                throw NSError(domain: "OTABundleManager", code: 3,
                              userInfo: [NSLocalizedDescriptionKey: "No JS bundle after patching to \(toHash)"])
            }

            try? fm.removeItem(at: scratchDir)

            let bundlePath = bundleFile.path
            prefs.pendingBundlePath = bundlePath
            prefs.pendingBundleHash = toHash
            prefs.pendingBundleId   = bundleId

            NSLog("[OTA] Delta applied: %@ (from=%@ to=%@)", bundlePath, fromHash, toHash)
            return bundlePath
        } catch {
            // Leave nothing half-patched behind for the next attempt to trip on.
            try? fm.removeItem(at: targetDir)
            try? fm.removeItem(at: scratchDir)
            throw error
        }
    }

    /// Reconstruct one file described by a manifest entry.
    private func applyDeltaFile(entry: [String: Any],
                                baseDir: URL,
                                scratchDir: URL,
                                targetDir: URL) throws {
        guard let path = entry["path"] as? String,
              let mode = entry["mode"] as? String,
              let entryName = entry["entry"] as? String,
              let expectedSize = entry["newSize"] as? Int,
              let expectedSha = entry["newSha256"] as? String else {
            throw NSError(domain: "OTABundleManager", code: 6,
                          userInfo: [NSLocalizedDescriptionKey: "Malformed delta manifest entry"])
        }

        let payloadURL = try safeChild(of: scratchDir, relativePath: entryName)
        guard let payload = try? Data(contentsOf: payloadURL) else {
            throw NSError(domain: "OTABundleManager", code: 7,
                          userInfo: [NSLocalizedDescriptionKey: "Patch entry \"\(entryName)\" missing for \(path)"])
        }

        let reconstructed: Data
        switch mode {
        case "bsdiff":
            let baseURL = try safeChild(of: baseDir, relativePath: path)
            guard let baseData = try? Data(contentsOf: baseURL) else {
                throw NSError(domain: "OTABundleManager", code: 8,
                              userInfo: [NSLocalizedDescriptionKey:
                                "Base bundle is missing \"\(path)\", required by a bsdiff entry"])
            }
            reconstructed = try BsPatch.apply(oldData: baseData, patchData: payload)
        case "raw":
            reconstructed = payload
        default:
            throw NSError(domain: "OTABundleManager", code: 9,
                          userInfo: [NSLocalizedDescriptionKey: "Unknown delta mode \"\(mode)\" for \(path)"])
        }

        guard reconstructed.count == expectedSize else {
            throw NSError(domain: "OTABundleManager", code: 10,
                          userInfo: [NSLocalizedDescriptionKey:
                            "Reconstructed \"\(path)\" is \(reconstructed.count) bytes, expected \(expectedSize)"])
        }

        guard HashVerifier.sha256Hex(reconstructed).caseInsensitiveCompare(expectedSha) == .orderedSame else {
            throw NSError(domain: "OTABundleManager", code: 11,
                          userInfo: [NSLocalizedDescriptionKey: "Reconstructed \"\(path)\" failed its hash check"])
        }

        let destination = try safeChild(of: targetDir, relativePath: path)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try reconstructed.write(to: destination)
    }

    /// Resolve a relative path inside `parent`, refusing anything that escapes it.
    /// A manifest is server-supplied data, so "../.." has to be treated as
    /// hostile even though we generated it.
    private func safeChild(of parent: URL, relativePath: String) throws -> URL {
        let child = parent.appendingPathComponent(relativePath).standardizedFileURL
        let root = parent.standardizedFileURL
        guard child.path == root.path || child.path.hasPrefix(root.path + "/") else {
            throw NSError(domain: "OTABundleManager", code: 12,
                          userInfo: [NSLocalizedDescriptionKey:
                            "Path traversal detected in delta manifest: \(relativePath)"])
        }
        return child
    }

    /// Promote pending → active.
    func applyPendingBundle() {
        guard let pendingPath = prefs.pendingBundlePath else { return }
        let pendingId = prefs.pendingBundleId
        prefs.activeBundlePath = pendingPath
        prefs.activeBundleHash = prefs.pendingBundleHash
        // Carry the id across too — clearPending() is about to drop it, and it
        // is the only human-readable handle on what the device is running.
        prefs.activeBundleId   = pendingId
        prefs.clearPending()
        NSLog("[OTA] Bundle applied: %@ (id=%@)", pendingPath, pendingId)
    }

    /// Get active bundle URL for RCTBridge. Returns nil to use embedded asset.
    func getActiveBundleURL() -> URL? {
        guard let path = prefs.activeBundlePath else { return nil }
        let url = URL(fileURLWithPath: path)
        return FileManager.default.fileExists(atPath: path) ? url : nil
    }

    /// Full rollback — clear all OTA state.
    func rollback() {
        NSLog("[OTA] Rolling back to embedded bundle")
        prefs.activeBundlePath = nil
        prefs.activeBundleHash = ""
        prefs.activeBundleId   = ""
        prefs.clearPending()
        prefs.resetCrashCount()
    }

    /// Delete all bundles except the currently active one.
    func cleanOldBundles() {
        let activeHash = prefs.activeBundleHash
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: otaRoot, includingPropertiesForKeys: nil
        ) else { return }

        for dir in contents where dir.lastPathComponent != activeHash {
            try? FileManager.default.removeItem(at: dir)
            NSLog("[OTA] Cleaned old bundle: %@", dir.lastPathComponent)
        }
    }

    // MARK: - Private

    private func findBundleFile(in dir: URL) -> URL? {
        let names = ["main.jsbundle", "index.ios.bundle", "index.bundle"]
        for name in names {
            let candidate = dir.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }
        // Recursive search fallback
        let enumerator = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)
        while let file = enumerator?.nextObject() as? URL {
            if file.pathExtension == "jsbundle" || file.lastPathComponent.hasSuffix(".bundle") {
                return file
            }
        }
        return nil
    }
}
