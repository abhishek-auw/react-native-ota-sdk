import Foundation

/// Persistent storage for OTA state using UserDefaults (app-private).
class OTAPrefs {

    private let defaults = UserDefaults.standard

    // MARK: Active bundle (currently running)

    var activeBundlePath: String? {
        get { defaults.string(forKey: "ota_active_path") }
        set { defaults.set(newValue, forKey: "ota_active_path") }
    }

    var activeBundleHash: String {
        get { defaults.string(forKey: "ota_active_hash") ?? "" }
        set { defaults.set(newValue, forKey: "ota_active_hash") }
    }

    // MARK: Pending bundle (downloaded, awaiting apply)

    var pendingBundlePath: String? {
        get { defaults.string(forKey: "ota_pending_path") }
        set { defaults.set(newValue, forKey: "ota_pending_path") }
    }

    var pendingBundleHash: String {
        get { defaults.string(forKey: "ota_pending_hash") ?? "" }
        set { defaults.set(newValue, forKey: "ota_pending_hash") }
    }

    var pendingBundleId: String {
        get { defaults.string(forKey: "ota_pending_id") ?? "" }
        set { defaults.set(newValue, forKey: "ota_pending_id") }
    }

    // MARK: Crash guard

    var crashCount: Int {
        get { defaults.integer(forKey: "ota_crash_count") }
        set { defaults.set(newValue, forKey: "ota_crash_count") }
    }

    var lastAppliedBundleHash: String {
        get { defaults.string(forKey: "ota_last_applied") ?? "" }
        set { defaults.set(newValue, forKey: "ota_last_applied") }
    }

    // MARK: Helpers

    func clearPending() {
        defaults.removeObject(forKey: "ota_pending_path")
        defaults.removeObject(forKey: "ota_pending_hash")
        defaults.removeObject(forKey: "ota_pending_id")
    }

    func resetCrashCount() {
        defaults.set(0, forKey: "ota_crash_count")
    }
}
