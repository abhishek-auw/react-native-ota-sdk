import Foundation

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

    /// Returns the active OTA bundle URL, or nil if no OTA bundle has been
    /// applied yet (caller should fall back to the embedded asset).
    ///
    /// Also promotes any pending bundle (downloaded + applyPendingBundle() called)
    /// to active before React Native starts, so the very next launch after an
    /// update loads the new bundle.
    ///
    /// Call this from sourceURL(for:) in your RCTBridgeDelegate.
    @objc public static func activeBundleURL() -> URL? {
        let prefs = OTAPrefs()
        let manager = BundleManager(prefs: prefs)

        // Promote pending → active if a bundle is waiting to be applied
        if prefs.pendingBundlePath != nil {
            manager.applyPendingBundle()
        }

        return manager.getActiveBundleURL()
    }
}
