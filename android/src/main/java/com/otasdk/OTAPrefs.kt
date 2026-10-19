package com.otasdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent storage for OTA state.
 * Stores: active bundle path, active bundle hash, crash counter, pending bundle path.
 */
class OTAPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ota_sdk_prefs", Context.MODE_PRIVATE)

    // ── Active (currently running) bundle ─────────────────────────────
    var activeBundlePath: String?
        get() = prefs.getString(KEY_ACTIVE_PATH, null)
        set(v) = prefs.edit().putString(KEY_ACTIVE_PATH, v).apply()

    var activeBundleHash: String
        get() = prefs.getString(KEY_ACTIVE_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ACTIVE_HASH, v).apply()

    /**
     * Server bundle id of the active bundle. Empty when the app is on the JS
     * compiled into the binary, or when the active bundle was applied by an
     * SDK build that predates this key.
     */
    var activeBundleId: String
        get() = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ACTIVE_ID, v).apply()

    // ── Pending (downloaded, not yet applied) bundle ──────────────────
    var pendingBundlePath: String?
        get() = prefs.getString(KEY_PENDING_PATH, null)
        set(v) = prefs.edit().putString(KEY_PENDING_PATH, v).apply()

    var pendingBundleHash: String
        get() = prefs.getString(KEY_PENDING_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PENDING_HASH, v).apply()

    var pendingBundleId: String
        get() = prefs.getString(KEY_PENDING_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PENDING_ID, v).apply()

    // ── Previous good bundle (the rollback target) ────────────────────
    val previousBundlePath: String?
        get() = prefs.getString(KEY_PREV_PATH, null)

    val previousBundleHash: String
        get() = prefs.getString(KEY_PREV_HASH, "") ?: ""

    val previousBundleId: String
        get() = prefs.getString(KEY_PREV_ID, "") ?: ""

    // ── Crash guard ───────────────────────────────────────────────────
    //
    // Crash-guard writes use commit(), not apply(). apply() hands the write to
    // a background thread, and a process that crashes a moment later can lose
    // it — which is exactly the moment these values exist for.

    /** Sessions of the active, unconfirmed bundle that ended in a crash. */
    var crashCount: Int
        get() = prefs.getInt(KEY_CRASH_COUNT, 0)
        set(v) { prefs.edit().putInt(KEY_CRASH_COUNT, v).commit() }

    /** Foreground launches of the active bundle since it was applied, while unconfirmed. */
    var launchCount: Int
        get() = prefs.getInt(KEY_LAUNCH_COUNT, 0)
        set(v) { prefs.edit().putInt(KEY_LAUNCH_COUNT, v).commit() }

    /**
     * True while an unconfirmed bundle's session is in the foreground. Still
     * true on the next launch means the process died in the foreground — a
     * crash. A user swipe-kill always comes after the app is backgrounded.
     */
    var sessionInForeground: Boolean
        get() = prefs.getBoolean(KEY_SESSION_FOREGROUND, false)
        set(v) { prefs.edit().putBoolean(KEY_SESSION_FOREGROUND, v).commit() }

    /** Hash of the bundle that last died of a fatal JS error, or "". */
    var fatalJsCrashHash: String
        get() = prefs.getString(KEY_FATAL_JS_HASH, "") ?: ""
        set(v) { prefs.edit().putString(KEY_FATAL_JS_HASH, v).commit() }

    /** Timestamp of the newest ApplicationExitInfo already looked at. */
    var lastSeenExitTimestamp: Long
        get() = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_EXIT_TS, v).apply()

    /**
     * Whether the active bundle has been confirmed healthy (notifyAppReady()).
     * Defaults to true so a bundle applied by an SDK build that predates this
     * key — and has been running ever since — is not treated as new.
     */
    val activeBundleConfirmed: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE_CONFIRMED, true)

    /**
     * Saved from configure() so the check that runs before JS loads, on the
     * next launch, can read it.
     */
    var crashThreshold: Int
        get() = prefs.getInt(KEY_CRASH_THRESHOLD, DEFAULT_CRASH_THRESHOLD)
        set(v) = prefs.edit().putInt(KEY_CRASH_THRESHOLD, v).apply()

    /** Saved from configure(), like [crashThreshold]. */
    var maxUnconfirmedLaunches: Int
        get() = prefs.getInt(KEY_MAX_UNCONFIRMED, DEFAULT_MAX_UNCONFIRMED_LAUNCHES)
        set(v) = prefs.edit().putInt(KEY_MAX_UNCONFIRMED, v).apply()

    /** Hashes of bundles that were rolled back. checkForUpdate never offers these again. */
    val failedBundleHashes: List<String>
        get() = (prefs.getString(KEY_FAILED_HASHES, "") ?: "")
            .split(',').filter { it.isNotEmpty() }

    /**
     * Set when a launch rolled back before JS was up to report it. Holds the
     * failed bundle's server id, or "" when it had none.
     */
    val rollbackToReport: String?
        get() = prefs.getString(KEY_ROLLBACK_REPORT, null)

    /** Why that rollback happened: js_fatal, crash_threshold or not_confirmed. */
    val rollbackReason: String
        get() = prefs.getString(KEY_ROLLBACK_REASON, "") ?: ""

    fun clearRollbackToReport() {
        prefs.edit().remove(KEY_ROLLBACK_REPORT).remove(KEY_ROLLBACK_REASON).apply()
    }

    /** Record how the previous session ended, in one write. */
    fun endPreviousSession(crashed: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SESSION_FOREGROUND, false)
            .putInt(KEY_CRASH_COUNT, crashCount + if (crashed) 1 else 0)
            .commit()
    }

    fun confirmActiveBundle() {
        resetGuard(prefs.edit())
            .putBoolean(KEY_ACTIVE_CONFIRMED, true)
            .commit()
    }

    /**
     * Make the pending bundle active, unconfirmed, in one write. The bundle it
     * replaces becomes the rollback target only if it was confirmed; otherwise
     * the existing rollback target is kept.
     */
    fun promotePendingToActive() {
        val pendingPath = pendingBundlePath ?: return
        val edit = prefs.edit()
        if (activeBundlePath != null && activeBundleConfirmed) {
            edit.putString(KEY_PREV_PATH, activeBundlePath)
                .putString(KEY_PREV_HASH, activeBundleHash)
                .putString(KEY_PREV_ID, activeBundleId)
        }
        resetGuard(edit)
            .putString(KEY_ACTIVE_PATH, pendingPath)
            .putString(KEY_ACTIVE_HASH, pendingBundleHash)
            .putString(KEY_ACTIVE_ID, pendingBundleId)
            .putBoolean(KEY_ACTIVE_CONFIRMED, false)
            .remove(KEY_PENDING_PATH)
            .remove(KEY_PENDING_HASH)
            .remove(KEY_PENDING_ID)
            .commit()
    }

    /**
     * Replace the failing active bundle with [restorePath] (the previous good
     * bundle, or null for the JS compiled into the binary), blocklist it, and
     * flag the rollback for reporting — all in one write.
     */
    fun rollBackActiveBundle(restorePath: String?, reason: String) {
        val failedHash = activeBundleHash
        val failed = (failedBundleHashes - failedHash + failedHash)
            .filter { it.isNotEmpty() }
            .takeLast(MAX_FAILED_HASHES)
        val edit = resetGuard(prefs.edit())
            .putString(KEY_FAILED_HASHES, failed.joinToString(","))
            .putString(KEY_ROLLBACK_REPORT, activeBundleId)
            .putString(KEY_ROLLBACK_REASON, reason)
            .putBoolean(KEY_ACTIVE_CONFIRMED, true)
        if (restorePath != null) {
            edit.putString(KEY_ACTIVE_PATH, restorePath)
                .putString(KEY_ACTIVE_HASH, previousBundleHash)
                .putString(KEY_ACTIVE_ID, previousBundleId)
        } else {
            edit.remove(KEY_ACTIVE_PATH)
                .putString(KEY_ACTIVE_HASH, "")
                .putString(KEY_ACTIVE_ID, "")
        }
        edit.remove(KEY_PREV_PATH)
            .remove(KEY_PREV_HASH)
            .remove(KEY_PREV_ID)
            .commit()
    }

    private fun resetGuard(edit: SharedPreferences.Editor): SharedPreferences.Editor =
        edit.putInt(KEY_CRASH_COUNT, 0)
            .putInt(KEY_LAUNCH_COUNT, 0)
            .putBoolean(KEY_SESSION_FOREGROUND, false)
            .remove(KEY_FATAL_JS_HASH)

    fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_PATH)
            .remove(KEY_PENDING_HASH)
            .remove(KEY_PENDING_ID)
            .apply()
    }

    fun clearPrevious() {
        prefs.edit()
            .remove(KEY_PREV_PATH)
            .remove(KEY_PREV_HASH)
            .remove(KEY_PREV_ID)
            .apply()
    }

    fun resetCrashCount() {
        resetGuard(prefs.edit())
            .putBoolean(KEY_ACTIVE_CONFIRMED, true)
            .commit()
    }

    companion object {
        const val DEFAULT_CRASH_THRESHOLD = 3
        const val DEFAULT_MAX_UNCONFIRMED_LAUNCHES = 5
        private const val MAX_FAILED_HASHES = 20

        private const val KEY_ACTIVE_PATH    = "active_bundle_path"
        private const val KEY_ACTIVE_HASH    = "active_bundle_hash"
        private const val KEY_ACTIVE_ID      = "active_bundle_id"
        private const val KEY_PENDING_PATH   = "pending_bundle_path"
        private const val KEY_PENDING_HASH   = "pending_bundle_hash"
        private const val KEY_PENDING_ID     = "pending_bundle_id"
        private const val KEY_PREV_PATH      = "previous_bundle_path"
        private const val KEY_PREV_HASH      = "previous_bundle_hash"
        private const val KEY_PREV_ID        = "previous_bundle_id"
        private const val KEY_CRASH_COUNT    = "crash_count"
        private const val KEY_ACTIVE_CONFIRMED = "active_bundle_confirmed"
        private const val KEY_CRASH_THRESHOLD  = "crash_threshold"
        private const val KEY_FAILED_HASHES    = "failed_bundle_hashes"
        private const val KEY_ROLLBACK_REPORT  = "rollback_to_report"
        private const val KEY_ROLLBACK_REASON  = "rollback_reason"
        private const val KEY_LAUNCH_COUNT     = "launch_count"
        private const val KEY_SESSION_FOREGROUND = "session_in_foreground"
        private const val KEY_FATAL_JS_HASH    = "fatal_js_crash_hash"
        private const val KEY_LAST_EXIT_TS     = "last_seen_exit_timestamp"
        private const val KEY_MAX_UNCONFIRMED  = "max_unconfirmed_launches"
    }
}
