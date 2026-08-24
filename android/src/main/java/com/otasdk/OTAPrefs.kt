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

    // ── Crash guard ───────────────────────────────────────────────────
    var crashCount: Int
        get() = prefs.getInt(KEY_CRASH_COUNT, 0)
        set(v) = prefs.edit().putInt(KEY_CRASH_COUNT, v).apply()

    var lastAppliedBundleHash: String
        get() = prefs.getString(KEY_LAST_APPLIED, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_APPLIED, v).apply()

    fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_PATH)
            .remove(KEY_PENDING_HASH)
            .remove(KEY_PENDING_ID)
            .apply()
    }

    fun resetCrashCount() {
        prefs.edit().putInt(KEY_CRASH_COUNT, 0).apply()
    }

    companion object {
        private const val KEY_ACTIVE_PATH    = "active_bundle_path"
        private const val KEY_ACTIVE_HASH    = "active_bundle_hash"
        private const val KEY_ACTIVE_ID      = "active_bundle_id"
        private const val KEY_PENDING_PATH   = "pending_bundle_path"
        private const val KEY_PENDING_HASH   = "pending_bundle_hash"
        private const val KEY_PENDING_ID     = "pending_bundle_id"
        private const val KEY_CRASH_COUNT    = "crash_count"
        private const val KEY_LAST_APPLIED   = "last_applied_hash"
    }
}
