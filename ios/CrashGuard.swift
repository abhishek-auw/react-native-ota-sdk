import Foundation

/// Detects a newly applied bundle that is failing and rolls back to the
/// previous good bundle before it is loaded again.
///
/// A broken bundle — one that cannot load at all — is handled first:
///  - Before every load, the bundle file is checked (`BundleFileCheck`):
///    missing, empty, truncated, built for another Hermes, or binary junk.
///    Such a bundle is rolled back on that same launch, before React Native
///    tries it, so the app never crashes on it. Confirmed bundles too: a file
///    damaged on disk is no more loadable for having worked before.
///  - A bundle that was loaded but whose JS never reached configure() before
///    the app died failed to load, and is rolled back on the next launch.
///
/// Only with rollbackOnCrash on (off by default — a crash while the app is in
/// use is a bug in the app, not a broken bundle), a bundle that starts but
/// then misbehaves is rolled back too ("confirm or revert"):
///  - A newly applied bundle starts unconfirmed. notifyAppReady() confirms it
///    (`markStable`); a confirmed bundle is never counted or rolled back.
///  - While unconfirmed, each session's ending is classified on the next
///    launch (`recordPreviousSession`). Only a session that crashed counts
///    against the bundle — the app dying in the foreground. A user closing
///    the app does not: that always happens from the background.
///  - Before React Native loads any JS (`checkBeforeLoad`) the bundle is
///    rolled back, for that same launch, when:
///      · its last session died of a fatal JS error — the bundle's own fault,
///        so one is enough;
///      · it has crashed `crashThreshold` times; or
///      · it has been launched `maxUnconfirmedLaunches` times without ever
///        being confirmed (a white screen that never crashes).
///
/// The check runs natively, from OtaSdkHelper, because a bundle that crashes
/// before JS calls configure() is exactly the one that must be caught.
class CrashGuard {

    enum Decision: Equatable {
        /// No OTA bundle, or it is confirmed: nothing to guard.
        case notGuarded
        /// Unconfirmed, still within its limits: load it.
        case load
        case rollBack(reason: String)
    }

    private let prefs: OTAPrefs
    private let bundleManager: BundleManager

    init(prefs: OTAPrefs, bundleManager: BundleManager) {
        self.prefs = prefs
        self.bundleManager = bundleManager
    }

    static let reasonBundleInvalid = "bundle_invalid"

    static func evaluateLaunch(hasOtaBundle: Bool, confirmed: Bool, failedToStart: Bool,
                               rollbackOnCrash: Bool, diedOfJsFatal: Bool,
                               crashes: Int, launches: Int,
                               crashThreshold: Int, maxUnconfirmedLaunches: Int) -> Decision {
        if !hasOtaBundle || confirmed { return .notGuarded }
        if failedToStart { return .rollBack(reason: "bundle_load_failed") }
        if !rollbackOnCrash { return .load }
        if diedOfJsFatal { return .rollBack(reason: "js_fatal") }
        if crashes >= max(crashThreshold, 1) { return .rollBack(reason: "crash_threshold") }
        if launches >= max(maxUnconfirmedLaunches, 1) { return .rollBack(reason: "not_confirmed") }
        return .load
    }

    private var guarded: Bool {
        prefs.activeBundlePath != nil && !prefs.activeBundleConfirmed
    }

    /// Call once per process, before any pending bundle is promoted, so the
    /// previous session is charged to the bundle that actually ran it.
    func recordPreviousSession() {
        let diedInForeground = prefs.sessionInForeground
        guard guarded else {
            if diedInForeground { prefs.sessionInForeground = false }
            if prefs.awaitingJsStart { prefs.awaitingJsStart = false }
            return
        }
        if prefs.awaitingJsStart {
            NSLog("[OTA-CrashGuard] Bundle %@ was loaded but its JS never started",
                  prefs.activeBundleHash)
            prefs.loadFailedHash = prefs.activeBundleHash
            prefs.awaitingJsStart = false
        }
        let crashed = diedInForeground || prefs.fatalJsCrashHash == prefs.activeBundleHash
        prefs.endPreviousSession(crashed: crashed)
        if crashed {
            NSLog("[OTA-CrashGuard] Previous session of bundle %@ crashed (%d / %d)",
                  prefs.activeBundleHash, prefs.crashCount, prefs.crashThreshold)
        }
    }

    /// Call before React Native loads the bundle. Returns true if it rolled
    /// back; the active bundle path has then changed.
    func checkBeforeLoad() -> Bool {
        var rolledBack = false
        // Each rollback clears the rollback target, so this ends on the
        // embedded bundle at worst.
        while let path = prefs.activeBundlePath, let problem = bundleManager.bundleProblem(path: path) {
            NSLog("[OTA-CrashGuard] Rolling back bundle %@: it cannot load — %@",
                  prefs.activeBundleHash, problem)
            bundleManager.rollbackToPrevious(reason: CrashGuard.reasonBundleInvalid)
            rolledBack = true
        }
        if rolledBack { return true }

        let activeHash = prefs.activeBundleHash
        let decision = CrashGuard.evaluateLaunch(
            hasOtaBundle:           prefs.activeBundlePath != nil,
            confirmed:              prefs.activeBundleConfirmed,
            failedToStart:          !activeHash.isEmpty && prefs.loadFailedHash == activeHash,
            rollbackOnCrash:        prefs.rollbackOnCrash,
            diedOfJsFatal:          !activeHash.isEmpty && prefs.fatalJsCrashHash == activeHash,
            crashes:                prefs.crashCount,
            launches:               prefs.launchCount,
            crashThreshold:         prefs.crashThreshold,
            maxUnconfirmedLaunches: prefs.maxUnconfirmedLaunches
        )
        guard case .rollBack(let reason) = decision else { return false }

        NSLog("[OTA-CrashGuard] Rolling back bundle %@: %@ (crashes=%d, launches=%d)",
              activeHash, reason, prefs.crashCount, prefs.launchCount)
        bundleManager.rollbackToPrevious(reason: reason)
        return true
    }

    /// Call once per bundle load, when the app is in the foreground.
    func recordLaunchAttempt() {
        guard guarded else { return }
        let count = prefs.launchCount + 1
        prefs.launchCount = count
        NSLog("[OTA-CrashGuard] Launch %d / %d of unconfirmed bundle %@",
              count, prefs.maxUnconfirmedLaunches, prefs.activeBundleHash)
    }

    /// The app came to the foreground.
    func onForeground() {
        if guarded { prefs.sessionInForeground = true }
    }

    /// The app went to the background: dying from here on is not a crash of this session.
    func onBackground() {
        if prefs.sessionInForeground { prefs.sessionInForeground = false }
        if prefs.awaitingJsStart { prefs.awaitingJsStart = false }
    }

    /// The bundle is about to be loaded. Until `onJsStarted`, dying means the
    /// bundle failed to load.
    func onBundleLoading() {
        if guarded { prefs.awaitingJsStart = true }
    }

    /// The bundle's JS is running: it got as far as configure().
    func onJsStarted() {
        if prefs.awaitingJsStart { prefs.awaitingJsStart = false }
    }

    /// A fatal JS error is about to kill the process.
    func recordJsFatal() {
        if guarded { prefs.fatalJsCrashHash = prefs.activeBundleHash }
    }

    /// Call once the app is up and working. Confirms the active bundle, which
    /// stops it being counted or rolled back.
    func markStable() {
        if guarded {
            NSLog("[OTA-CrashGuard] Bundle %@ confirmed", prefs.activeBundleHash)
            prefs.confirmActiveBundle()
        }
    }
}
