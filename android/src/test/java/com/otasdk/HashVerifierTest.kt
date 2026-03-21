package com.otasdk

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HashVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `computeFileHash returns 64-char lowercase hex string`() {
        val file = tempFolder.newFile("bundle.zip")
        file.writeText("hello world")
        val hash = HashVerifier.computeFileHash(file)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[a-f0-9]+")))
    }

    @Test
    fun `computeFileHash is deterministic for same content`() {
        val file = tempFolder.newFile("bundle.zip")
        file.writeText("same content")
        val h1 = HashVerifier.computeFileHash(file)
        val h2 = HashVerifier.computeFileHash(file)
        assertEquals(h1, h2)
    }

    @Test
    fun `computeFileHash differs for different content`() {
        val f1 = tempFolder.newFile("b1.zip").also { it.writeText("bundle v1") }
        val f2 = tempFolder.newFile("b2.zip").also { it.writeText("bundle v2") }
        assertNotEquals(HashVerifier.computeFileHash(f1), HashVerifier.computeFileHash(f2))
    }

    @Test
    fun `verifyFile returns true when hash matches`() {
        val file = tempFolder.newFile("good.zip")
        file.writeText("valid bundle content")
        val hash = HashVerifier.computeFileHash(file)
        assertTrue(HashVerifier.verifyFile(file, hash))
    }

    @Test
    fun `verifyFile returns false when file is tampered`() {
        val file = tempFolder.newFile("tampered.zip")
        file.writeText("original content")
        val hash = HashVerifier.computeFileHash(file)
        // Tamper the file
        file.writeText("tampered content")
        assertFalse(HashVerifier.verifyFile(file, hash))
    }

    @Test
    fun `verifyFile returns false for blank expected hash`() {
        val file = tempFolder.newFile("any.zip")
        file.writeText("content")
        assertFalse(HashVerifier.verifyFile(file, ""))
    }

    @Test
    fun `computeFileHash handles large file without OOM`() {
        val file = tempFolder.newFile("large.zip")
        // Write 10MB of data
        val mb = ByteArray(1024 * 1024) { it.toByte() }
        repeat(10) { file.appendBytes(mb) }
        val hash = HashVerifier.computeFileHash(file)
        assertEquals(64, hash.length)
    }
}
