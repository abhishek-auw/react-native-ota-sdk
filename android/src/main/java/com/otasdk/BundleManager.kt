package com.otasdk

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages bundle storage and switching on Android.
 *
 * Bundle lifecycle:
 *  downloaded → verified → stored as pending → applied on restart → marked active
 *
 * Storage locations (app-private, no permissions needed):
 *   /data/data/<packageName>/files/ota/bundles/<hash>/index.android.bundle
 */
class BundleManager(private val context: Context, private val prefs: OTAPrefs) {

    companion object {
        private const val TAG = "OTA-BundleManager"
        private const val OTA_DIR = "ota/bundles"
    }

    private val otaRoot: File
        get() = File(context.filesDir, OTA_DIR).also { it.mkdirs() }

    /**
     * Destination file for a bundle identified by its hash.
     */
    fun bundleFileForHash(hash: String): File =
        File(otaRoot, "$hash/index.android.bundle")

    /**
     * Destination file for a downloaded ZIP before extraction.
     */
    fun zipFileForHash(hash: String): File =
        File(otaRoot, "$hash/download.zip")

    /**
     * Store a downloaded + verified ZIP to the OTA bundle directory.
     * Unzips and sets up the bundle path for use by React Native.
     *
     * @return path to the extracted JS bundle file
     */
    fun storePendingBundle(
        zipFile: File,
        hash: String,
        bundleId: String,
    ): String {
        val bundleDir = File(otaRoot, hash)
        bundleDir.mkdirs()

        // Extract ZIP contents into bundleDir
        unzip(zipFile, bundleDir)

        // Find the main JS bundle file (index.android.bundle or main.jsbundle)
        val bundleFile = findBundleFile(bundleDir)
            ?: throw IllegalStateException("No JS bundle found in ZIP for hash $hash")

        Log.d(TAG, "Pending bundle stored: ${bundleFile.absolutePath}")

        // Record pending state
        prefs.pendingBundlePath = bundleFile.absolutePath
        prefs.pendingBundleHash = hash
        prefs.pendingBundleId = bundleId

        return bundleFile.absolutePath
    }

    /**
     * Promote pending bundle to active.
     * Called after a successful restart on the new bundle.
     */
    fun applyPendingBundle() {
        val pendingPath = prefs.pendingBundlePath ?: return
        val pendingHash = prefs.pendingBundleHash

        prefs.activeBundlePath = pendingPath
        prefs.activeBundleHash = pendingHash
        prefs.clearPending()

        Log.d(TAG, "Bundle applied: $pendingPath (hash=$pendingHash)")
    }

    /**
     * Get the active JS bundle path.
     * Returns null if no OTA bundle has ever been applied (use bundled asset).
     */
    fun getActiveBundlePath(): String? = prefs.activeBundlePath

    /**
     * Roll back to the original bundled asset (clear all OTA state).
     */
    fun rollback() {
        Log.w(TAG, "Rolling back — clearing OTA bundle state")
        prefs.activeBundlePath = null
        prefs.activeBundleHash = ""
        prefs.clearPending()
        prefs.resetCrashCount()
    }

    /**
     * Delete all OTA bundles except the current active one.
     * Called periodically to clean up old bundles.
     */
    fun cleanOldBundles() {
        val activeHash = prefs.activeBundleHash
        otaRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != activeHash) {
                Log.d(TAG, "Cleaning old bundle: ${dir.name}")
                dir.deleteRecursively()
            }
        }
    }

    // ── ZIP extraction ──────────────────────────────────────────────
    private fun unzip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(destDir, entry.name)
                // Security: prevent path traversal
                require(target.canonicalPath.startsWith(destDir.canonicalPath)) {
                    "ZIP path traversal detected: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zipFile.delete() // Remove ZIP after extraction
    }

    private fun findBundleFile(dir: File): File? =
        sequenceOf("index.android.bundle", "main.jsbundle", "index.bundle")
            .map { File(dir, it) }
            .firstOrNull { it.exists() }
            ?: dir.walk().firstOrNull { it.name.endsWith(".bundle") }
}
