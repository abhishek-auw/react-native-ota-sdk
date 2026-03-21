package com.otasdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: BundleDownloader

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        downloader = BundleDownloader()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `download succeeds and verifies hash correctly`() {
        val content = "fake bundle zip content".toByteArray()
        val expectedHash = HashVerifier.computeFileHash(
            tempFolder.newFile("ref").also { it.writeBytes(content) }
        )
        server.enqueue(MockResponse().setBody(okio.Buffer().write(content)))

        val destFile = tempFolder.newFile("bundle.zip")
        val result = downloader.download(
            url = server.url("/bundle.zip").toString(),
            destFile = destFile,
            expectedHash = expectedHash,
        )

        assertTrue(destFile.exists())
        assertEquals(expectedHash, result.computedHash)
    }

    @Test(expected = BundleDownloader.DownloadException::class)
    fun `download throws when hash does not match (tampered bundle)`() {
        val content = "real bundle content".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(content)))

        val destFile = tempFolder.newFile("bundle.zip")
        downloader.download(
            url = server.url("/bundle.zip").toString(),
            destFile = destFile,
            expectedHash = "0000000000000000000000000000000000000000000000000000000000000000",
        )
        // Should throw — tampered content
    }

    @Test(expected = BundleDownloader.DownloadException::class)
    fun `download throws on non-200 HTTP response`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val destFile = tempFolder.newFile("bundle.zip")
        downloader.download(
            url = server.url("/missing.zip").toString(),
            destFile = destFile,
            expectedHash = "anyhash",
        )
    }

    @Test
    fun `download cleans up partial file on hash mismatch`() {
        val content = "bad content".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(content)))

        val destFile = tempFolder.newFile("partial.zip")
        runCatching {
            downloader.download(
                url = server.url("/bundle.zip").toString(),
                destFile = destFile,
                expectedHash = "wrong-hash-value-padded-to-64-chars-aaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            )
        }
        // Partial file should be deleted on failure
        assertFalse(destFile.exists())
    }

    @Test
    fun `download reports progress`() {
        val content = ByteArray(1024 * 100) { it.toByte() } // 100KB
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(content))
                .addHeader("Content-Length", content.size.toString())
        )
        val ref = tempFolder.newFile("ref").also { it.writeBytes(content) }
        val expectedHash = HashVerifier.computeFileHash(ref)

        val progressValues = mutableListOf<Float>()
        val destFile = tempFolder.newFile("bundle.zip")
        downloader.download(
            url = server.url("/bundle.zip").toString(),
            destFile = destFile,
            expectedHash = expectedHash,
            onProgress = { progressValues.add(it) }
        )

        assertTrue("Progress events should be emitted", progressValues.isNotEmpty())
        assertTrue("Final progress should be ~1.0", progressValues.last() >= 0.99f)
    }
}
