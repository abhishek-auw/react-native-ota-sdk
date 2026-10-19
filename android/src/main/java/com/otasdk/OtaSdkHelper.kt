package com.otasdk

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

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

    /** Set once this process has run its once-per-process crash-guard setup. */
    private val processWatchInstalled = AtomicBoolean(false)

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
     *
     * Also runs the crash guard. This is the one point that is guaranteed to
     * run before any JS does, so it is where a bundle that crashes on startup
     * gets replaced by the previous good one.
     */
    @JvmStatic
    fun getActiveBundlePath(context: Context): String? {
        val appContext = context.applicationContext
        val prefs = OTAPrefs(appContext)
        val bundleManager = BundleManager(appContext, prefs)
        val crashGuard = CrashGuard(prefs, bundleManager)

        // Once per process, and before the pending bundle is promoted, so how
        // the last session ended is charged to the bundle that ran it.
        if (processWatchInstalled.compareAndSet(false, true)) {
            crashGuard.recordPreviousSession(lastExitRecordedAsCrash(appContext, prefs))
            installJsFatalHandler(crashGuard)
            (appContext as? Application)?.let { watchForeground(it, crashGuard) }
        }

        // Promote pending → active if a bundle is waiting to be applied
        if (prefs.pendingBundlePath != null) {
            bundleManager.applyPendingBundle()
        }

        if (crashGuard.checkBeforeLoad()) {
            Log.w(TAG, "crash guard rolled back — loading ${prefs.activeBundlePath ?: "the packaged bundle"}")
        }

        return bundleManager.getActiveBundlePath()
    }

    /**
     * Whether the OS recorded this app's last exit as a crash, native crash or
     * ANR. Android 11+ only; each exit record is looked at once.
     */
    private fun lastExitRecordedAsCrash(context: Context, prefs: OTAPrefs): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val last = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                ?.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return false

        if (last.timestamp <= prefs.lastSeenExitTimestamp) return false
        prefs.lastSeenExitTimestamp = last.timestamp
        return last.reason == ApplicationExitInfo.REASON_CRASH ||
            last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            last.reason == ApplicationExitInfo.REASON_ANR
    }

    /**
     * Note a fatal JS error before the process dies, then hand the crash on
     * unchanged. In a release build React Native turns a fatal JS error into a
     * JavascriptException thrown on a native thread; that, unlike any other
     * crash, is unambiguously the bundle's fault.
     */
    private fun installJsFatalHandler(crashGuard: CrashGuard) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val isJsError = generateSequence(error) { it.cause }
                    .take(10)
                    .any { it.javaClass.name == "com.facebook.react.common.JavascriptException" }
                if (isJsError) crashGuard.recordJsFatal()
            } catch (_: Throwable) {
                // Never get in the way of the crash itself.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * Track whether an Activity is visible, and count the first time one is.
     *
     * A process started in the background — headless JS, a push handler —
     * loads the bundle too, but never reaches notifyAppReady(), so counting it
     * would roll back a healthy bundle. And a process that dies while nothing
     * is visible was not a crash the user saw: swiping the app away always
     * stops its activities first.
     *
     * onActivityStarted, not onActivityCreated: React Native usually asks for
     * the bundle from inside the first Activity's onCreate, after the created
     * callback has already been dispatched.
     */
    private fun watchForeground(app: Application, crashGuard: CrashGuard) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            private var launchCounted = false

            override fun onActivityStarted(activity: Activity) {
                if (started++ > 0) return
                if (!launchCounted) {
                    launchCounted = true
                    crashGuard.recordLaunchAttempt()
                }
                crashGuard.onForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (started > 0 && --started == 0) crashGuard.onBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
