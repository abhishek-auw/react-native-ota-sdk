package com.otasdk

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * Utilities to get stable, privacy-safe device identifiers and app version.
 */
object DeviceInfo {

    private const val TAG = "OTA-SDK"
    private const val META_RUNTIME_VERSION = "com.otasdk.RUNTIME_VERSION"
    private const val DEFAULT_RUNTIME_VERSION = "1"

    /**
     * Returns a stable, one-way hashed device identifier.
     * Uses ANDROID_ID (stable per device/user) → SHA-256 → hex.
     * Never exposes the raw ANDROID_ID to the server.
     */
    /**
     * The JS-to-native compatibility token this binary declares.
     *
     * Read from AndroidManifest meta-data, deliberately *not* from JS config.
     * The whole point of the token is that a bundle cannot change which
     * bundles the device is eligible for — if it lived in JavaScript, an OTA
     * update could bump its own runtime and pull in bundles built against a
     * native contract this binary does not have.
     *
     *   <meta-data android:name="com.otasdk.RUNTIME_VERSION" android:value="1" />
     *
     * Falls back to "1" so an app that has not declared one still works, but
     * logs loudly — silently defaulting is how a device ends up stranded on a
     * runtime nobody publishes to.
     */
    fun getRuntimeVersion(context: Context): String {
        return try {
            val info = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            // Read as a string first: android:value="1" is parsed as an Int by
            // the manifest compiler, and getString() returns null for it.
            val meta = info.metaData
            val value = meta?.getString(META_RUNTIME_VERSION)
                ?: meta?.get(META_RUNTIME_VERSION)?.toString()

            if (value.isNullOrBlank()) {
                Log.w(TAG, "no $META_RUNTIME_VERSION in AndroidManifest — defaulting to \"1\"")
                DEFAULT_RUNTIME_VERSION
            } else {
                value
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read $META_RUNTIME_VERSION, defaulting to \"1\"", e)
            DEFAULT_RUNTIME_VERSION
        }
    }

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
