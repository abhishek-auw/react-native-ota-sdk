package com.otasdk

import android.util.Log

/**
 * CrashGuard detects a newly applied bundle that is failing and rolls back to
 * the previous good bundle before it is loaded again.
 *
 * Strategy ("confirm or revert"):
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
        ROLL_BACK_JS_FATAL("js_fatal"),
        ROLL_BACK_CRASHES("crash_threshold"),
        ROLL_BACK_NOT_CONFIRMED("not_confirmed"),
    }

    companion object {
        private const val TAG = "OTA-CrashGuard"

        fun evaluateLaunch(
            hasOtaBundle: Boolean,
            confirmed: Boolean,
            diedOfJsFatal: Boolean,
            crashes: Int,
            launches: Int,
            crashThreshold: Int,
            maxUnconfirmedLaunches: Int,
        ): Decision = when {
            !hasOtaBundle || confirmed                               -> Decision.NOT_GUARDED
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
            return
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
        val activeHash = prefs.activeBundleHash
        val decision = evaluateLaunch(
            hasOtaBundle           = prefs.activeBundlePath != null,
            confirmed              = prefs.activeBundleConfirmed,
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
