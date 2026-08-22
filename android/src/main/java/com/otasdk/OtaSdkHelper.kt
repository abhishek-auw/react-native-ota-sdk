package com.otasdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Public entry-point for host apps that need to integrate OTA bundle loading
 * before React Native starts.
 *
 * New Architecture (bridgeless, the default since React Native 0.76) —
 * MainApplication.kt:
 *
 *   override val reactHost: ReactHost by lazy {
 *       getDefaultReactHost(
 *           context = applicationContext,
 *           packageList = PackageList(this).packages,
 *           jsBundleFilePath = OtaSdkHelper.getJSBundleFile(applicationContext),
 *       )
 *   }
 *
 * Old Architecture (bridge):
 *
 *   override fun getJSBundleFile(): String? =
 *       OtaSdkHelper.getJSBundleFile(applicationContext)
 *
 * Note that in bridgeless mode `reactNativeHost` is never consulted for bundle
 * loading — overriding getJSBundleFile() there has no effect at all, silently.
 */
object OtaSdkHelper {

    private const val TAG = "OTA-Host"

    /**
     * The JS bundle React Native should load, or null to fall back to the
     * bundle packaged in the APK.
     *
     * Returns null on debuggable builds so Metro keeps working during
     * development — an OTA bundle would otherwise fight the dev server. The
     * check reads FLAG_DEBUGGABLE from the host app rather than a BuildConfig,
     * since a library cannot see the app's own BuildConfig. Pass
     * `enableInDebuggableBuilds = true` to exercise the OTA path in a
     * debuggable build (Metro must not be running).
     *
     * Also promotes a pending bundle — one that was downloaded and had
     * applyPendingBundle() called for it — to active before React Native
     * starts, which is what makes an update take effect on the next launch.
     */
    @JvmStatic
    @JvmOverloads
    fun getJSBundleFile(
        context: Context,
        enableInDebuggableBuilds: Boolean = false,
    ): String? {
        val appContext = context.applicationContext
        val debuggable =
            (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (debuggable && !enableInDebuggableBuilds) {
            Log.i(TAG, "debuggable build — using Metro or the packaged bundle, OTA skipped")
            return null
        }

        val path = getActiveBundlePath(appContext)
        // Logged deliberately, at a level that survives a normal release build.
        // A silent null here is indistinguishable from the hook never being
        // called, which is a genuinely painful thing to debug.
        Log.i(TAG, if (path != null) "loading OTA bundle: $path" else "no OTA bundle yet — using the packaged bundle")
        return path
    }

    /**
     * Lower-level variant: returns the active OTA bundle path with no build-type
     * check, promoting any pending bundle first. Prefer [getJSBundleFile].
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
