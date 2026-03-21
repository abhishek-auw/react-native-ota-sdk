package com.otasdk

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Utilities to get stable, privacy-safe device identifiers and app version.
 */
object DeviceInfo {

    /**
     * Returns a stable, one-way hashed device identifier.
     * Uses ANDROID_ID (stable per device/user) → SHA-256 → hex.
     * Never exposes the raw ANDROID_ID to the server.
     */
    fun getDeviceHash(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "unknown"

        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(androidId.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the app's versionName from PackageManager.
     * e.g. "1.2.3"
     */
    fun getAppVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
