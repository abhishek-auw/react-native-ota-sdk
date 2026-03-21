package com.otasdk

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a bundle ZIP from a URL to a local file.
 *
 * Features:
 *  - Streams directly to disk (no full RAM load)
 *  - Reports progress via callback
 *  - SHA-256 verification after download
 *  - Throws DownloadException on any failure
 */
class BundleDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class DownloadResult(
        val file: File,
        val computedHash: String,
    )

    class DownloadException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Download a bundle to [destFile].
     *
     * @param url           CDN URL of the bundle
     * @param destFile      Where to write the downloaded file
     * @param expectedHash  SHA-256 hash to verify after download (hex string)
     * @param onProgress    Callback with 0.0–1.0 progress fraction
     */
    fun download(
        url: String,
        destFile: File,
        expectedHash: String,
        onProgress: ((Float) -> Unit)? = null,
    ): DownloadResult {
        val request = Request.Builder().url(url).build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw DownloadException("Network error: ${e.message}", e)
        }

        if (!response.isSuccessful) {
            response.close()
            throw DownloadException("Server returned HTTP ${response.code}")
        }

        val body = response.body
            ?: throw DownloadException("Empty response body")

        val contentLength = body.contentLength() // -1 if unknown

        // Ensure parent directory exists
        destFile.parentFile?.mkdirs()

        var totalBytesRead = 0L
        try {
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            onProgress?.invoke(totalBytesRead.toFloat() / contentLength.toFloat())
                        }
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            destFile.delete() // Clean up partial file
            throw DownloadException("Write error: ${e.message}", e)
        }

        // Verify hash BEFORE returning — reject tampered bundles
        val computedHash = HashVerifier.computeFileHash(destFile)
        if (!HashVerifier.verifyFile(destFile, expectedHash)) {
            destFile.delete()
            throw DownloadException(
                "Hash mismatch! Expected=$expectedHash  Got=$computedHash"
            )
        }

        return DownloadResult(file = destFile, computedHash = computedHash)
    }
}
