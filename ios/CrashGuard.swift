import Foundation

/// Detects crash loops on newly applied bundles and triggers rollback.
class CrashGuard {

    private let prefs: OTAPrefs
    private let threshold: Int

    init(prefs: OTAPrefs, threshold: Int = 3) {
        self.prefs = prefs
        self.threshold = threshold
    }

    /// Call at every app start. Returns true if rollback should happen.
    func onAppStart() -> Bool {
        let currentHash = prefs.activeBundleHash
        let lastHash    = prefs.lastAppliedBundleHash

        // New bundle detected — reset counter
        if currentHash != lastHash {
            NSLog("[OTA-CrashGuard] New bundle (%@), resetting counter", currentHash)
            prefs.lastAppliedBundleHash = currentHash
            prefs.resetCrashCount()
            return false
        }

        // Same bundle — increment crash counter
        let count = prefs.crashCount + 1
        prefs.crashCount = count
        NSLog("[OTA-CrashGuard] Crash count for %@: %d / %d", currentHash, count, threshold)

        if count >= threshold {
            NSLog("[OTA-CrashGuard] Threshold reached — rollback triggered")
            return true
        }
        return false
    }

    /// Call once the app has been running stably (≥5 seconds).
    func markStable() {
        if prefs.crashCount > 0 {
            NSLog("[OTA-CrashGuard] App stable — reset crash counter")
            prefs.resetCrashCount()
        }
    }
}
