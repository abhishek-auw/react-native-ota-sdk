package com.otasdk

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages bundle storage and switching on Android.
 *
 * Bundle lifecycle:
 *  downloaded → verified → stored as pending → applied  on restart → marked active
 *
 * Storage locations (app-private, no permissions needed):
 *   /data/data/<packageName>/files/ota/bundles/<hash>/index.android.bundle
 */
class BundleManager(private val context: Context, private val prefs: OTAPrefs) {

    companion object {
        private const val TAG = "OTA-BundleManager"
        private const val OTA_DIR = "ota/bundles"

        /**
         * Delta patch format this SDK understands. Must match
         * DELTA_MANIFEST_VERSION in packages/backend/src/services/DeltaService.ts.
         * A mismatch makes the patch fail cleanly and the caller fall back to a
         * full download.
         */
        private const val DELTA_MANIFEST_VERSION = 2
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
     * Apply a delta patch ZIP on top of an existing bundle directory.
     *
     * The patch ZIP (produced by DeltaService on the server) contains:
     * - `_delta_manifest.json` — version, per-file entries and deletions
     * - `bin/<n>` — bsdiff patches, applied against the base file
     * - `raw/<n>` — whole replacement files
     *
     * Most changed files arrive as bsdiff patches: a one-line JS edit in a
     * multi-megabyte bundle is a few kilobytes on the wire rather than the
     * whole file.
     *
     * Steps:
     *  1. Copy the base bundle directory into a new dir keyed by toHash
     *  2. Extract the patch ZIP to a scratch directory
     *  3. Reconstruct each file listed in the manifest, verifying its hash
     *  4. Delete files the manifest marks as removed
     *  5. Record the patched bundle as pending
     *
     * Throws on any inconsistency. The caller catches, discards the partial
     * result, and falls back to downloading the full bundle.
     *
     * @return Path to the JS bundle file after patching
     */
    fun applyDeltaPatch(
        patchZipFile: File,
        fromHash: String,
        toHash: String,
        bundleId: String,
    ): String {
        val baseBundleDir = File(otaRoot, fromHash)
        if (!baseBundleDir.exists()) {
            throw IllegalStateException(
                "Cannot apply delta: base bundle dir not found for hash $fromHash"
            )
        }

        val targetDir = File(otaRoot, toHash)
        val scratchDir = File(otaRoot, "patch-$toHash")

        try {
            targetDir.mkdirs()
            scratchDir.mkdirs()

            // 1. Seed the target dir from the base
            baseBundleDir.copyRecursively(targetDir, overwrite = true)

            // 2. Unpack the patch somewhere separate — its entry names
            //    (bin/0, raw/1) are not bundle paths and must not land in the
            //    bundle directory.
            unzip(patchZipFile, scratchDir)

            val manifestFile = File(scratchDir, "_delta_manifest.json")
            if (!manifestFile.exists()) {
                throw IllegalStateException("Patch is missing _delta_manifest.json")
            }

            val manifest = org.json.JSONObject(manifestFile.readText())
            val version = manifest.optInt("version", 1)
            if (version != DELTA_MANIFEST_VERSION) {
                throw IllegalStateException(
                    "Unsupported delta manifest version $version " +
                        "(this SDK understands $DELTA_MANIFEST_VERSION)"
                )
            }

            // 3. Reconstruct every changed file
            val files = manifest.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    applyDeltaFile(files.getJSONObject(i), baseBundleDir, scratchDir, targetDir)
                }
            }

            // 4. Deletions
            val deleted = manifest.optJSONArray("deleted")
            if (deleted != null) {
                for (i in 0 until deleted.length()) {
                    val relativePath = deleted.getString(i)
                    val victim = safeChild(targetDir, relativePath)
                    if (victim.exists()) {
                        victim.delete()
                        Log.d(TAG, "Delta: deleted $relativePath")
                    }
                }
            }

            // 5. Locate JS bundle
            val bundleFile = findBundleFile(targetDir)
                ?: throw IllegalStateException("No JS bundle found after patching to hash $toHash")

            Log.d(TAG, "Delta applied: ${bundleFile.absolutePath} (from=$fromHash to=$toHash)")

            prefs.pendingBundlePath = bundleFile.absolutePath
            prefs.pendingBundleHash = toHash
            prefs.pendingBundleId   = bundleId

            return bundleFile.absolutePath
        } catch (e: Exception) {
            // Leave nothing half-patched behind for the next attempt to trip on.
            targetDir.deleteRecursively()
            throw e
        } finally {
            scratchDir.deleteRecursively()
        }
    }

    /** Reconstruct one file described by a manifest entry. */
    private fun applyDeltaFile(
        entry: org.json.JSONObject,
        baseDir: File,
        scratchDir: File,
        targetDir: File,
    ) {
        val path = entry.getString("path")
        val mode = entry.getString("mode")
        val entryName = entry.getString("entry")
        val expectedSize = entry.getLong("newSize")
        val expectedSha = entry.getString("newSha256")

        val payloadFile = safeChild(scratchDir, entryName)
        if (!payloadFile.exists()) {
            throw IllegalStateException("Patch entry \"$entryName\" missing for $path")
        }
        val payload = payloadFile.readBytes()

        val reconstructed = when (mode) {
            "bsdiff" -> {
                val baseFile = safeChild(baseDir, path)
                if (!baseFile.exists()) {
                    throw IllegalStateException(
                        "Base bundle is missing \"$path\", required by a bsdiff entry"
                    )
                }
                BsPatch.apply(baseFile.readBytes(), payload)
            }
            "raw" -> payload
            else -> throw IllegalStateException("Unknown delta mode \"$mode\" for $path")
        }

        if (reconstructed.size.toLong() != expectedSize) {
            throw IllegalStateException(
                "Reconstructed \"$path\" is ${reconstructed.size} bytes, expected $expectedSize"
            )
        }

        val actualSha = HashVerifier.sha256Hex(reconstructed)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw IllegalStateException("Reconstructed \"$path\" failed its hash check")
        }

        val destination = safeChild(targetDir, path)
        destination.parentFile?.mkdirs()
        destination.writeBytes(reconstructed)
    }

    /**
     * Resolve a relative path inside [parent], refusing anything that escapes it.
     * A manifest is server-supplied data, so "../../etc/passwd" has to be
     * treated as hostile even though we generated it.
     */
    private fun safeChild(parent: File, relativePath: String): File {
        val child = File(parent, relativePath)
        require(child.canonicalPath.startsWith(parent.canonicalPath + File.separator) ||
                child.canonicalPath == parent.canonicalPath) {
            "Path traversal detected in delta manifest: $relativePath"
        }
        return child
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
