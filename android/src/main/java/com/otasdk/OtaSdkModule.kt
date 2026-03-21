package com.otasdk

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.*
import android.util.Log

/**
 * OtaSdkModule — main React Native native module.
 *
 * All heavy work (HTTP, file I/O, hashing, crash guard) runs on a
 * background coroutine. The JS thread is never blocked.
 *
 * JS-exposed methods:
 *   configure(config)
 *   checkForUpdate(promise)
 *   downloadBundle(bundleId, url, hash, promise)
 *   applyPendingBundle(promise)
 *   getStatus(promise)
 *   markStable()
 *
 * JS events emitted:
 *   OTA_DOWNLOAD_PROGRESS   { bundleId, progress }   0.0 – 1.0
 *   OTA_EVENT               { type, payload }
 */
class OtaSdkModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "OTA-SDK"
        const val MODULE_NAME = "OtaSdk"
    }

    // ── Dependencies (all native, no JS) ──────────────────────────────
    private val prefs         = OTAPrefs(reactContext)
    private val crashGuard    = CrashGuard(prefs)
    private val bundleManager = BundleManager(reactContext, prefs)
    private val downloader    = BundleDownloader()
    private val apiClient     = OTAApiClient()

    private var config: OTAConfig? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun getName() = MODULE_NAME

    // ── Called once from JS when the app initialises ──────────────────

    @ReactMethod
    fun configure(configMap: ReadableMap) {
        config = OTAConfig(
            appId      = configMap.getString("appId") ?: "",
            serverUrl  = configMap.getString("serverUrl") ?: "",
            channel    = configMap.getString("channel") ?: "production",
            crashThreshold = if (configMap.hasKey("crashThreshold"))
                configMap.getInt("crashThreshold") else 3,
        )
        Log.d(TAG, "Configured: appId=${config?.appId} server=${config?.serverUrl}")

        // Run crash guard on every configure (= every app start)
        scope.launch { runCrashGuardCheck() }
    }

    // ── Update check (network call on background thread) ─────────────

    @ReactMethod
    fun checkForUpdate(promise: Promise) {
        val cfg = config ?: return promise.reject("NOT_CONFIGURED", "Call configure() first")

        scope.launch {
            try {
                val deviceHash   = DeviceInfo.getDeviceHash(reactApplicationContext)
                val appVersion   = DeviceInfo.getAppVersion(reactApplicationContext)
                val currentHash  = prefs.activeBundleHash

                val result = apiClient.checkForUpdate(
                    serverUrl     = cfg.serverUrl,
                    appId         = cfg.appId,
                    platform      = "android",
                    appVersion    = appVersion,
                    currentHash   = currentHash,
                    channel       = cfg.channel,
                    deviceHash    = deviceHash,
                )

                val map = Arguments.createMap()
                map.putBoolean("updateAvailable", result.updateAvailable)
                if (result.updateAvailable && result.update != null) {
                    val u = result.update
                    map.putString("bundleId",    u.bundleId)
                    map.putString("downloadUrl", u.downloadUrl)
                    map.putString("hash",        u.hash)
                    map.putBoolean("mandatory",  u.mandatory)
                    u.releaseNotes?.let { map.putString("releaseNotes", it) }
                }
                promise.resolve(map)

            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate failed", e)
                promise.reject("CHECK_FAILED", e.message, e)
            }
        }
    }

    // ── Bundle download (streams to disk, reports progress) ──────────

    @ReactMethod
    fun downloadBundle(bundleId: String, downloadUrl: String, expectedHash: String, promise: Promise) {
        val cfg = config ?: return promise.reject("NOT_CONFIGURED", "Call configure() first")

        scope.launch {
            try {
                emitEvent("OTA_EVENT", mapOf("type" to "download_started", "bundleId" to bundleId))

                val zipFile = bundleManager.zipFileForHash(expectedHash)

                val result = downloader.download(
                    url          = downloadUrl,
                    destFile     = zipFile,
                    expectedHash = expectedHash,
                    onProgress   = { progress ->
                        // Emit progress back to JS
                        emitProgress(bundleId, progress)
                    }
                )

                // Extract ZIP and store bundle
                val bundlePath = bundleManager.storePendingBundle(
                    zipFile  = result.file,
                    hash     = result.computedHash,
                    bundleId = bundleId,
                )

                // Report download event to analytics
                reportEvent(cfg, "download", bundleId)

                emitEvent("OTA_EVENT", mapOf(
                    "type"       to "download_complete",
                    "bundleId"   to bundleId,
                    "bundlePath" to bundlePath,
                ))

                val map = Arguments.createMap()
                map.putString("bundlePath", bundlePath)
                map.putString("hash", result.computedHash)
                promise.resolve(map)

            } catch (e: BundleDownloader.DownloadException) {
                Log.e(TAG, "Download failed: ${e.message}")
                emitEvent("OTA_EVENT", mapOf("type" to "download_failed", "error" to (e.message ?: "")))
                promise.reject("DOWNLOAD_FAILED", e.message, e)

            } catch (e: Exception) {
                Log.e(TAG, "Unexpected download error", e)
                promise.reject("DOWNLOAD_FAILED", e.message, e)
            }
        }
    }

    // ── Apply pending bundle (takes effect on next JS reload) ─────────

    @ReactMethod
    fun applyPendingBundle(promise: Promise) {
        val pendingPath = prefs.pendingBundlePath
        if (pendingPath == null) {
            promise.reject("NO_PENDING", "No pending bundle to apply")
            return
        }
        try {
            bundleManager.applyPendingBundle()
            emitEvent("OTA_EVENT", mapOf("type" to "bundle_applied", "path" to pendingPath))
            promise.resolve(pendingPath)
        } catch (e: Exception) {
            promise.reject("APPLY_FAILED", e.message, e)
        }
    }

    // ── Status query ──────────────────────────────────────────────────

    @ReactMethod
    fun getStatus(promise: Promise) {
        val map = Arguments.createMap()
        map.putString("activeBundleHash",  prefs.activeBundleHash)
        map.putString("activeBundlePath",  prefs.activeBundlePath ?: "")
        map.putBoolean("hasPending",       prefs.pendingBundlePath != null)
        map.putString("pendingBundleHash", prefs.pendingBundleHash)
        map.putInt("crashCount",           prefs.crashCount)
        promise.resolve(map)
    }

    // ── Called by JS after app is stable (≥5s without crash) ─────────

    @ReactMethod
    fun markStable() {
        crashGuard.markStable()
        val cfg = config
        if (cfg != null && prefs.activeBundleHash.isNotEmpty()) {
            scope.launch { reportEvent(cfg, "install_ok", null) }
        }
    }

    // ── Manual rollback ───────────────────────────────────────────────

    @ReactMethod
    fun rollback(promise: Promise) {
        bundleManager.rollback()
        emitEvent("OTA_EVENT", mapOf("type" to "rollback"))
        promise.resolve(null)
    }

    // ── NativeEventEmitter contract ───────────────────────────────────
    // Required so JS NativeEventEmitter doesn't warn about missing methods

    @ReactMethod
    fun addListener(eventName: String) { /* no-op — events are push-only */ }

    @ReactMethod
    fun removeListeners(count: Int) { /* no-op */ }

    // ── Internals ─────────────────────────────────────────────────────

    private suspend fun runCrashGuardCheck() {
        val shouldRollback = crashGuard.onAppStart()
        if (shouldRollback) {
            Log.w(TAG, "Crash guard triggered rollback")
            bundleManager.rollback()
            config?.let { cfg ->
                reportEvent(cfg, "crash", prefs.activeBundleHash.ifEmpty { null })
            }
            emitEvent("OTA_EVENT", mapOf("type" to "rollback", "reason" to "crash_threshold"))
        }
    }

    private fun emitProgress(bundleId: String, progress: Float) {
        val map = Arguments.createMap()
        map.putString("bundleId", bundleId)
        map.putDouble("progress", progress.toDouble())
        sendEvent("OTA_DOWNLOAD_PROGRESS", map)
    }

    private fun emitEvent(eventName: String, payload: Map<String, Any>) {
        val map = Arguments.createMap()
        payload.forEach { (k, v) ->
            when (v) {
                is String  -> map.putString(k, v)
                is Boolean -> map.putBoolean(k, v)
                is Int     -> map.putInt(k, v)
                is Double  -> map.putDouble(k, v)
                is Float   -> map.putDouble(k, v.toDouble())
            }
        }
        sendEvent(eventName, map)
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private suspend fun reportEvent(cfg: OTAConfig, eventType: String, bundleId: String?) {
        try {
            apiClient.reportEvent(
                serverUrl  = cfg.serverUrl,
                appId      = cfg.appId,
                bundleId   = bundleId,
                deviceHash = DeviceInfo.getDeviceHash(reactApplicationContext),
                eventType  = eventType,
                platform   = "android",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Analytics event failed (non-fatal): ${e.message}")
        }
    }

    override fun onCatalystInstanceDestroy() {
        scope.cancel()
        super.onCatalystInstanceDestroy()
    }
}
