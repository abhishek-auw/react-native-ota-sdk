package com.otasdk

import android.util.Log

/**
 * CrashGuard detects if a newly applied bundle is causing repeated crashes
 * and triggers a rollback to the previous stable bundle.
 *
 * Strategy:
 *  - On each successful app launch, reset the crash counter.
 *  - If the app crashes before reaching "stable" (within first 5 seconds),
 *    the crash counter is NOT reset — increment on next launch.
 *  - If crash count >= threshold, auto-rollback.
 *
 * Usage:
 *  Call CrashGuard.onAppStart() in your Application.onCreate()
 *  Call CrashGuard.markStable() after your app is fully initialised (5+ seconds)
 */
class CrashGuard(
    private val prefs: OTAPrefs,
    private val threshold: Int = 3,
) {
    companion object {
        private const val TAG = "OTA-CrashGuard"
    }

    /**
     * Call this at app start.
     * Returns true if a rollback should be triggered.
     */
    fun onAppStart(): Boolean {
        val pendingHash = prefs.pendingBundlePath
        val lastApplied = prefs.lastAppliedBundleHash
        val currentActive = prefs.activeBundleHash

        // If the active bundle changed since last crash check, reset counter
        if (currentActive != lastApplied) {
            Log.d(TAG, "New bundle detected ($currentActive), resetting crash counter")
            prefs.lastAppliedBundleHash = currentActive
            prefs.resetCrashCount()
            return false
        }

        // Increment crash count (will be reset if markStable() is called)
        val count = prefs.crashCount + 1
        prefs.crashCount = count
        Log.w(TAG, "Crash count for bundle $currentActive: $count / $threshold")

        if (count >= threshold) {
            Log.e(TAG, "Crash threshold reached — triggering rollback")
            return true
        }
        return false
    }

    /**
     * Call this when the app has been running for at least 5 seconds without crash.
     * Resets the crash counter, marking this bundle as stable.
     */
    fun markStable() {
        if (prefs.crashCount > 0) {
            Log.d(TAG, "App stable — resetting crash counter")
            prefs.resetCrashCount()
        }
    }
}
