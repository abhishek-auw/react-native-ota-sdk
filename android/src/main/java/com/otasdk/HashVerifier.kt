package com.otasdk

import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

/**
 * Computes and verifies SHA-256 hashes AND ECDSA P-256 signatures of bundle files.
 * Uses Java's built-in security APIs — no third-party crypto dependency.
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
     * Verify an ECDSA P-256 signature over the raw bytes of a file.
     *
     * @param file          The file whose bytes were signed (the bundle ZIP).
     * @param signatureB64  Base-64-encoded DER signature produced by the CI signing step.
     * @param publicKeyPem  PEM-encoded SPKI public key stored on the server per-app.
     * @return true if the signature is valid, false otherwise.
     */
    fun verifyEcdsaSignature(file: File, signatureB64: String, publicKeyPem: String): Boolean {
        return try {
            // Strip PEM header/footer and decode
            val stripped = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.decode(stripped, Base64.DEFAULT)

            val keySpec = X509EncodedKeySpec(keyBytes)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(keySpec)

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)

            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    sig.update(buffer, 0, bytesRead)
                }
            }

            val sigBytes = Base64.decode(signatureB64, Base64.DEFAULT)
            sig.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
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
