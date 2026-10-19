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

    /// Server bundle id of the active bundle. Empty when the app is on the JS
    /// compiled into the binary, or when the active bundle was applied by an
    /// SDK build that predates this key.
    var activeBundleId: String {
        get { defaults.string(forKey: "ota_active_id") ?? "" }
        set { defaults.set(newValue, forKey: "ota_active_id") }
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

    // MARK: Previous good bundle (the rollback target)

    var previousBundlePath: String? { defaults.string(forKey: "ota_prev_path") }
    var previousBundleHash: String  { defaults.string(forKey: "ota_prev_hash") ?? "" }
    var previousBundleId: String    { defaults.string(forKey: "ota_prev_id") ?? "" }

    // MARK: Crash guard
    //
    // Crash-guard writes call synchronize(). UserDefaults otherwise persists
    // in the background, and a process that crashes a moment later can lose
    // the write — which is exactly the moment these values exist for.

    static let defaultCrashThreshold = 3
    static let defaultMaxUnconfirmedLaunches = 5
    private static let maxFailedHashes = 20

    /// Sessions of the active, unconfirmed bundle that ended in a crash.
    var crashCount: Int {
        get { defaults.integer(forKey: "ota_crash_count") }
        set { defaults.set(newValue, forKey: "ota_crash_count"); defaults.synchronize() }
    }

    /// Foreground launches of the active bundle since it was applied, while unconfirmed.
    var launchCount: Int {
        get { defaults.integer(forKey: "ota_launch_count") }
        set { defaults.set(newValue, forKey: "ota_launch_count"); defaults.synchronize() }
    }

    /// True while an unconfirmed bundle's session is in the foreground. Still
    /// true on the next launch means the process died in the foreground — a
    /// crash. A user swipe-kill always comes after the app is backgrounded.
    var sessionInForeground: Bool {
        get { defaults.bool(forKey: "ota_session_foreground") }
        set { defaults.set(newValue, forKey: "ota_session_foreground"); defaults.synchronize() }
    }

    /// True from the moment an unconfirmed bundle is loaded until its JS
    /// reaches configure(). Still true on the next launch means the bundle
    /// never started: it failed to load.
    var awaitingJsStart: Bool {
        get { defaults.bool(forKey: "ota_awaiting_js_start") }
        set { defaults.set(newValue, forKey: "ota_awaiting_js_start"); defaults.synchronize() }
    }

    /// Hash of the bundle whose last launch never got its JS started, or "".
    var loadFailedHash: String {
        get { defaults.string(forKey: "ota_load_failed_hash") ?? "" }
        set { defaults.set(newValue, forKey: "ota_load_failed_hash"); defaults.synchronize() }
    }

    /// Hash of the bundle that last died of a fatal JS error, or "".
    var fatalJsCrashHash: String {
        get { defaults.string(forKey: "ota_fatal_js_hash") ?? "" }
        set { defaults.set(newValue, forKey: "ota_fatal_js_hash"); defaults.synchronize() }
    }

    /// Whether the active bundle has been confirmed healthy (notifyAppReady()).
    /// Defaults to true so a bundle applied by an SDK build that predates this
    /// key — and has been running ever since — is not treated as new.
    var activeBundleConfirmed: Bool {
        defaults.object(forKey: "ota_active_confirmed") as? Bool ?? true
    }

    /// Saved from configure() so the check that runs before JS loads, on the
    /// next launch, can read it.
    var crashThreshold: Int {
        get { defaults.object(forKey: "ota_crash_threshold") as? Int ?? OTAPrefs.defaultCrashThreshold }
        set { defaults.set(newValue, forKey: "ota_crash_threshold") }
    }

    /// Whether a bundle that starts but then crashes is rolled back too.
    /// Saved from configure(), like `crashThreshold`. Off by default: a crash
    /// while the app is in use is a bug in the app, not a broken bundle.
    var rollbackOnCrash: Bool {
        get { defaults.bool(forKey: "ota_rollback_on_crash") }
        set { defaults.set(newValue, forKey: "ota_rollback_on_crash") }
    }

    /// Saved from configure(), like `crashThreshold`.
    var maxUnconfirmedLaunches: Int {
        get { defaults.object(forKey: "ota_max_unconfirmed") as? Int ?? OTAPrefs.defaultMaxUnconfirmedLaunches }
        set { defaults.set(newValue, forKey: "ota_max_unconfirmed") }
    }

    /// Hashes of bundles that were rolled back. checkForUpdate never offers these again.
    var failedBundleHashes: [String] {
        defaults.stringArray(forKey: "ota_failed_hashes") ?? []
    }

    /// Set when a launch rolled back before JS was up to report it. Holds the
    /// failed bundle's server id, or "" when it had none.
    var rollbackToReport: String? { defaults.string(forKey: "ota_rollback_report") }

    /// Why that rollback happened: bundle_invalid, bundle_load_failed,
    /// js_fatal, crash_threshold or not_confirmed.
    var rollbackReason: String { defaults.string(forKey: "ota_rollback_reason") ?? "" }

    func clearRollbackToReport() {
        defaults.removeObject(forKey: "ota_rollback_report")
        defaults.removeObject(forKey: "ota_rollback_reason")
    }

    /// Record how the previous session ended.
    func endPreviousSession(crashed: Bool) {
        defaults.set(false, forKey: "ota_session_foreground")
        if crashed { defaults.set(crashCount + 1, forKey: "ota_crash_count") }
        defaults.synchronize()
    }

    func confirmActiveBundle() {
        resetGuard()
        defaults.set(true, forKey: "ota_active_confirmed")
        defaults.synchronize()
    }

    /// Make the pending bundle active and unconfirmed. The bundle it replaces
    /// becomes the rollback target only if it was confirmed; otherwise the
    /// existing rollback target is kept.
    func promotePendingToActive() {
        guard let pendingPath = pendingBundlePath else { return }
        if let activePath = activeBundlePath, activeBundleConfirmed {
            defaults.set(activePath,       forKey: "ota_prev_path")
            defaults.set(activeBundleHash, forKey: "ota_prev_hash")
            defaults.set(activeBundleId,   forKey: "ota_prev_id")
        }
        activeBundlePath = pendingPath
        activeBundleHash = pendingBundleHash
        activeBundleId   = pendingBundleId
        defaults.set(false, forKey: "ota_active_confirmed")
        resetGuard()
        clearPending()
        defaults.synchronize()
    }

    /// Replace the failing active bundle with `restorePath` (the previous good
    /// bundle, or nil for the JS compiled into the binary), blocklist it, and
    /// flag the rollback for reporting.
    func rollBackActiveBundle(restorePath: String?, reason: String) {
        let failedHash = activeBundleHash
        var failed = failedBundleHashes.filter { $0 != failedHash }
        if !failedHash.isEmpty { failed.append(failedHash) }
        defaults.set(Array(failed.suffix(OTAPrefs.maxFailedHashes)), forKey: "ota_failed_hashes")
        defaults.set(activeBundleId, forKey: "ota_rollback_report")
        defaults.set(reason, forKey: "ota_rollback_reason")

        if let restorePath {
            let prevHash = previousBundleHash
            let prevId   = previousBundleId
            activeBundlePath = restorePath
            activeBundleHash = prevHash
            activeBundleId   = prevId
        } else {
            activeBundlePath = nil
            activeBundleHash = ""
            activeBundleId   = ""
        }
        defaults.set(true, forKey: "ota_active_confirmed")
        resetGuard()
        clearPrevious()
        defaults.synchronize()
    }

    private func resetGuard() {
        defaults.set(0, forKey: "ota_crash_count")
        defaults.set(0, forKey: "ota_launch_count")
        defaults.set(false, forKey: "ota_session_foreground")
        defaults.set(false, forKey: "ota_awaiting_js_start")
        defaults.removeObject(forKey: "ota_fatal_js_hash")
        defaults.removeObject(forKey: "ota_load_failed_hash")
    }

    // MARK: Helpers

    func clearPending() {
        defaults.removeObject(forKey: "ota_pending_path")
        defaults.removeObject(forKey: "ota_pending_hash")
        defaults.removeObject(forKey: "ota_pending_id")
    }

    func clearPrevious() {
        defaults.removeObject(forKey: "ota_prev_path")
        defaults.removeObject(forKey: "ota_prev_hash")
        defaults.removeObject(forKey: "ota_prev_id")
    }

    func resetCrashCount() {
        resetGuard()
        defaults.set(true, forKey: "ota_active_confirmed")
        defaults.synchronize()
    }
}
