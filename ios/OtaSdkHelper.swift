import Foundation
import UIKit

/// Uncaught-exception handler that was installed before ours; called after it.
/// File-level so the C handler below can reach it without capturing anything.
nonisolated(unsafe) private var previousExceptionHandler: (@convention(c) (NSException) -> Void)?

/// Public entry-point for host apps that need to integrate OTA bundle loading
/// into their AppDelegate before React Native starts.
///
/// Usage in AppDelegate.swift:
///
///   func sourceURL(for bridge: RCTBridge!) -> URL! {
///       return OtaSdkHelper.activeBundleURL()
///           ?? Bundle.main.url(forResource: "main", withExtension: "jsbundle")
///   }
@objc public class OtaSdkHelper: NSObject {

    /// Set once this process has run its once-per-process crash-guard setup.
    nonisolated(unsafe) private static var processWatchInstalled = false
    /// Whether this bundle load has been counted as a foreground launch yet.
    nonisolated(unsafe) private static var launchCounted = false
    nonisolated(unsafe) private static var observers: [NSObjectProtocol] = []

    /// Returns the active OTA bundle URL, or nil if no OTA bundle has been
    /// applied yet (caller should fall back to the embedded asset).
    ///
    /// Also promotes any pending bundle (downloaded + applyPendingBundle() called)
    /// to active before React Native starts, so the very next launch after an
    /// update loads the new bundle.
    ///
    /// Also runs the crash guard. This is the one point that is guaranteed to
    /// run before any JS does, so it is where a bundle that crashes on startup
    /// gets replaced by the previous good one.
    ///
    /// Call this from sourceURL(for:) in your RCTBridgeDelegate.
    @objc public static func activeBundleURL() -> URL? {
        let prefs = OTAPrefs()
        let manager = BundleManager(prefs: prefs)
        let crashGuard = CrashGuard(prefs: prefs, bundleManager: manager)

        // Once per process, and before the pending bundle is promoted, so how
        // the last session ended is charged to the bundle that ran it. A
        // reload from restartApp() asks again in the same process; the
        // session still running then is not a previous one.
        let firstLoadInProcess = !processWatchInstalled
        if firstLoadInProcess {
            processWatchInstalled = true
            crashGuard.recordPreviousSession()
            installJsFatalHandler()
        }

        // Promote pending → active if a bundle is waiting to be applied
        if prefs.pendingBundlePath != nil {
            manager.applyPendingBundle()
        }

        if crashGuard.checkBeforeLoad() {
            NSLog("[OTA] Crash guard rolled back — loading %@",
                  prefs.activeBundlePath ?? "the embedded bundle")
        }
        // iOS loads the bundle as soon as it is asked for its URL, and runs
        // the app's JS even on a background launch, so configure() follows.
        crashGuard.onBundleLoading()

        launchCounted = false
        if firstLoadInProcess {
            DispatchQueue.main.async { watchForeground(crashGuard) }
        }
        countLaunchIfActive(crashGuard)

        return manager.getActiveBundleURL()
    }

    /// Count this load against the active bundle once the app is in the
    /// foreground, and mark the session as foregrounded.
    ///
    /// A cold launch asks for the bundle URL before the app becomes active, so
    /// that waits for didBecomeActive. A reload from restartApp() asks while
    /// the app is already active, so that counts straight away.
    private static func countLaunchIfActive(_ crashGuard: CrashGuard) {
        let check = {
            guard UIApplication.shared.applicationState == .active else { return }
            if !launchCounted {
                launchCounted = true
                crashGuard.recordLaunchAttempt()
            }
            crashGuard.onForeground()
        }
        if Thread.isMainThread { check() } else { DispatchQueue.main.async(execute: check) }
    }

    /// Track whether the app is in the foreground for the life of the process.
    ///
    /// A background launch (silent push, background fetch) loads the bundle
    /// too, but never reaches notifyAppReady(), so counting it would roll back
    /// a healthy bundle. And a process that dies in the background was not a
    /// crash the user saw: swiping the app away always backgrounds it first.
    private static func watchForeground(_ crashGuard: CrashGuard) {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in countLaunchIfActive(crashGuard) })
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in crashGuard.onBackground() })
        observers.append(center.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { _ in crashGuard.onBackground() })
        // Became active before this ran: didBecomeActive has already fired.
        countLaunchIfActive(crashGuard)
    }

    /// Note a fatal JS error before the process dies, then hand the exception
    /// on unchanged. In a release build React Native turns a fatal JS error
    /// into an NSException named "RCTFatalException: …"; that, unlike any
    /// other crash, is unambiguously the bundle's fault.
    private static func installJsFatalHandler() {
        previousExceptionHandler = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler { exception in
            if exception.name.rawValue.hasPrefix("RCTFatalException") {
                let prefs = OTAPrefs()
                CrashGuard(prefs: prefs, bundleManager: BundleManager(prefs: prefs)).recordJsFatal()
            }
            previousExceptionHandler?(exception)
        }
    }
}
