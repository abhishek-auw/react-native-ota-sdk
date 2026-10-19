package com.otasdk

import android.util.Log

/**
 * CrashGuard detects a newly applied bundle that is failing and rolls back to
 * the previous good bundle before it is loaded again.
 *
 * A broken bundle — one that cannot load at all — is handled first:
 *  - Before every load, the bundle file is checked ([BundleFileCheck]):
 *    missing, empty, truncated, built for another Hermes, or binary junk.
 *    Such a bundle is rolled back on that same launch, before React Native
 *    tries it, so the app never crashes on it. Confirmed bundles too: a file
 *    damaged on disk is no more loadable for having worked before.
 *  - A bundle that was loaded but whose JS never reached configure() before
 *    the app died failed to load, and is rolled back on the next launch.
 *
 * Only with rollbackOnCrash on (off by default — a crash while the app is in
 * use is a bug in the app, not a broken bundle), a bundle that starts but then
 * misbehaves is rolled back too ("confirm or revert"):
 *  - A newly applied bundle starts unconfirmed. notifyAppReady() confirms it
 *    ([markStable]); a confirmed bundle is never counted or rolled back.
 *  - While unconfirmed, each session's ending is classified on the next
 *    launch ([recordPreviousSession]). Only a session that crashed counts
 *    against the bundle — the app dying in the foreground, or an exit the OS
 *    recorded as a crash or ANR. A user closing the app does not.
 *  - Before React Native loads any JS ([checkBeforeLoad]) the bundle is
 *    rolled back, for that same launch, when:
 *      · its last session died of a fatal JS error — the bundle's own fault,
 *        so one is enough;
 *      · it has crashed `crashThreshold` times; or
 *      · it has been launched `maxUnconfirmedLaunches` times without ever
 *        being confirmed (a white screen that never crashes).
 *
 * The check runs natively, from OtaSdkHelper, because a bundle that crashes
 * before JS calls configure() is exactly the one that must be caught.
 */
class CrashGuard(
    private val prefs: OTAPrefs,
    private val bundleManager: BundleManager,
) {
    enum class Decision(val reason: String?) {
        /** No OTA bundle, or it is confirmed: nothing to guard. */
        NOT_GUARDED(null),
        /** Unconfirmed, still within its limits: load it. */
        LOAD(null),
        ROLL_BACK_LOAD_FAILED("bundle_load_failed"),
        ROLL_BACK_JS_FATAL("js_fatal"),
        ROLL_BACK_CRASHES("crash_threshold"),
        ROLL_BACK_NOT_CONFIRMED("not_confirmed"),
    }

    companion object {
        private const val TAG = "OTA-CrashGuard"
        const val REASON_BUNDLE_INVALID = "bundle_invalid"

        /** This process's JS has reached configure(). */
        @Volatile private var jsStartedInProcess = false

        fun evaluateLaunch(
            hasOtaBundle: Boolean,
            confirmed: Boolean,
            failedToStart: Boolean,
            rollbackOnCrash: Boolean,
            diedOfJsFatal: Boolean,
            crashes: Int,
            launches: Int,
            crashThreshold: Int,
            maxUnconfirmedLaunches: Int,
        ): Decision = when {
            !hasOtaBundle || confirmed                               -> Decision.NOT_GUARDED
            failedToStart                                            -> Decision.ROLL_BACK_LOAD_FAILED
            !rollbackOnCrash                                         -> Decision.LOAD
            diedOfJsFatal                                            -> Decision.ROLL_BACK_JS_FATAL
            crashes >= crashThreshold.coerceAtLeast(1)               -> Decision.ROLL_BACK_CRASHES
            launches >= maxUnconfirmedLaunches.coerceAtLeast(1)      -> Decision.ROLL_BACK_NOT_CONFIRMED
            else                                                     -> Decision.LOAD
        }
    }

    private val guarded: Boolean
        get() = prefs.activeBundlePath != null && !prefs.activeBundleConfirmed

    /**
     * Call once per process, before any pending bundle is promoted, so the
     * previous session is charged to the bundle that actually ran it.
     *
     * @param exitRecordedAsCrash the OS recorded the last exit as a crash or
     *        ANR (ApplicationExitInfo, Android 11+)
     */
    fun recordPreviousSession(exitRecordedAsCrash: Boolean) {
        val diedInForeground = prefs.sessionInForeground
        if (!guarded) {
            if (diedInForeground) prefs.sessionInForeground = false
            if (prefs.awaitingJsStart) prefs.awaitingJsStart = false
            return
        }
        if (prefs.awaitingJsStart) {
            Log.w(TAG, "Bundle ${prefs.activeBundleHash} was loaded but its JS never started")
            prefs.loadFailedHash = prefs.activeBundleHash
            prefs.awaitingJsStart = false
        }
        val crashed = diedInForeground || exitRecordedAsCrash ||
            prefs.fatalJsCrashHash == prefs.activeBundleHash
        prefs.endPreviousSession(crashed)
        if (crashed) {
            Log.w(TAG, "Previous session of bundle ${prefs.activeBundleHash} crashed " +
                "(${prefs.crashCount} / ${prefs.crashThreshold})")
        }
    }

    /**
     * Call before React Native loads the bundle.
     * Returns true if it rolled back; the active bundle path has then changed.
     */
    fun checkBeforeLoad(): Boolean {
        var rolledBack = false
        // Each rollback clears the rollback target, so this ends on the
        // packaged bundle at worst.
        while (true) {
            val path = prefs.activeBundlePath ?: break
            val problem = bundleManager.bundleProblem(path) ?: break
            Log.e(TAG, "Rolling back bundle ${prefs.activeBundleHash}: it cannot load — $problem")
            bundleManager.rollbackToPrevious(REASON_BUNDLE_INVALID)
            rolledBack = true
        }
        if (rolledBack) return true

        val activeHash = prefs.activeBundleHash
        val decision = evaluateLaunch(
            hasOtaBundle           = prefs.activeBundlePath != null,
            confirmed              = prefs.activeBundleConfirmed,
            failedToStart          = activeHash.isNotEmpty() && prefs.loadFailedHash == activeHash,
            rollbackOnCrash        = prefs.rollbackOnCrash,
            diedOfJsFatal          = activeHash.isNotEmpty() && prefs.fatalJsCrashHash == activeHash,
            crashes                = prefs.crashCount,
            launches               = prefs.launchCount,
            crashThreshold         = prefs.crashThreshold,
            maxUnconfirmedLaunches = prefs.maxUnconfirmedLaunches,
        )
        val reason = decision.reason ?: return false

        Log.e(TAG, "Rolling back bundle $activeHash: $reason " +
            "(crashes=${prefs.crashCount}, launches=${prefs.launchCount})")
        bundleManager.rollbackToPrevious(reason)
        return true
    }

    /** Call once per process, when the app first comes to the foreground. */
    fun recordLaunchAttempt() {
        if (!guarded) return
        val count = prefs.launchCount + 1
        prefs.launchCount = count
        Log.d(TAG, "Launch $count / ${prefs.maxUnconfirmedLaunches} of unconfirmed bundle ${prefs.activeBundleHash}")
    }

    /** The app came to the foreground. */
    fun onForeground() {
        if (guarded) prefs.sessionInForeground = true
    }

    /** The app went to the background: dying from here on is not a crash of this session. */
    fun onBackground() {
        if (prefs.sessionInForeground) prefs.sessionInForeground = false
        if (prefs.awaitingJsStart) prefs.awaitingJsStart = false
    }

    /**
     * The bundle is being loaded with the app in the foreground. Until
     * [onJsStarted], dying means the bundle failed to load.
     */
    fun onBundleLoading() {
        if (guarded && !jsStartedInProcess) prefs.awaitingJsStart = true
    }

    /** The bundle's JS is running: it got as far as configure(). */
    fun onJsStarted() {
        jsStartedInProcess = true
        if (prefs.awaitingJsStart) prefs.awaitingJsStart = false
    }

    /** A fatal JS error is about to kill the process. */
    fun recordJsFatal() {
        if (guarded) prefs.fatalJsCrashHash = prefs.activeBundleHash
    }

    /**
     * Call once the app is up and working. Confirms the active bundle, which
     * stops it being counted or rolled back.
     */
    fun markStable() {
        if (guarded) {
            Log.d(TAG, "Bundle ${prefs.activeBundleHash} confirmed")
            prefs.confirmActiveBundle()
        }
    }
}
