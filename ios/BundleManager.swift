import Foundation
import ZIPFoundation

/// Manages bundle storage and switching on iOS.
///
/// Storage location (app-private Documents, excluded from iCloud backup):
///   <Documents>/ota/bundles/<hash>/main.jsbundle
class BundleManager {

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

    /// Promote pending → active.
    func applyPendingBundle() {
        guard let pendingPath = prefs.pendingBundlePath else { return }
        prefs.activeBundlePath = pendingPath
        prefs.activeBundleHash = prefs.pendingBundleHash
        prefs.clearPending()
        NSLog("[OTA] Bundle applied: %@", pendingPath)
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
