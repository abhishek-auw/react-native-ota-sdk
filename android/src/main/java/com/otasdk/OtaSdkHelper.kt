package com.otasdk

import android.content.Context

/**
 * Public entry-point for host apps that need to integrate OTA bundle loading
 * into their MainApplication before React Native starts.
 *
 * Usage in MainApplication.kt:
 *
 *   import com.otasdk.OtaSdkHelper
 *
 *   object : DefaultReactNativeHost(this) {
 *       override fun getJSBundleFile(): String? =
 *           OtaSdkHelper.getActiveBundlePath(applicationContext)
 *   }
 */
object OtaSdkHelper {

    /**
     * Returns the path to the active OTA JS bundle, or null if no OTA bundle
     * has been applied yet (React Native will fall back to the bundled asset).
     *
     * Also promotes any pending bundle (one that was downloaded and
     * applyPendingBundle() was called for) to active before React starts,
     * so the very next launch after an update loads the new bundle.
     *
     * Call this from getJSBundleFile() in your ReactNativeHost override.
     */
    @JvmStatic
    fun getActiveBundlePath(context: Context): String? {
        val appContext = context.applicationContext
        val prefs = OTAPrefs(appContext)
        val bundleManager = BundleManager(appContext, prefs)

        // Promote pending → active if a bundle is waiting to be applied
        if (prefs.pendingBundlePath != null) {
            bundleManager.applyPendingBundle()
        }

        return bundleManager.getActiveBundlePath()
    }
}
