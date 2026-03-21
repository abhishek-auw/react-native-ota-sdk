package com.otasdk

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Computes and verifies SHA-256 hashes of bundle files entirely in native code.
 * Uses Java's built-in MessageDigest — no third-party crypto dependency.
 */
object HashVerifier {

    /**
     * Compute SHA-256 of a file and return hex string.
     * Reads in 8KB chunks — handles large bundles without OOM.
     */
    fun computeFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify that a file's hash matches the expected value.
     * Uses a constant-time comparison to prevent timing attacks.
     */
    fun verifyFile(file: File, expectedHash: String): Boolean {
        if (expectedHash.isBlank()) return false
        val actual = computeFileHash(file)
        return constantTimeEquals(actual, expectedHash.lowercase())
    }

    /**
     * Constant-time string comparison — prevents timing side-channels.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
