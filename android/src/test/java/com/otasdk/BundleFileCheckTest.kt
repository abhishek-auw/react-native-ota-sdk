package com.otasdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BundleFileCheckTest {

    /** A Hermes bytecode header: magic, version, 20-byte source hash, file length. */
    private fun hermesHead(version: Int, declaredLength: Int): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0x1F1903C103BC1FC6L)
            .putInt(version)
            .put(ByteArray(20) { 7 })
            .putInt(declaredLength)
            .array()

    @Test
    fun `reads the Hermes bytecode version`() {
        assertEquals(96, BundleFileCheck.hermesVersion(hermesHead(96, 1000)))
        assertNull(BundleFileCheck.hermesVersion("var a = 1;".toByteArray()))
    }

    @Test
    fun `a complete bundle for this app's Hermes is fine`() {
        assertNull(BundleFileCheck.problem(hermesHead(96, 1000), 1000, appHermesVersion = 96))
    }

    @Test
    fun `an empty file cannot load`() {
        assertNotNull(BundleFileCheck.problem(ByteArray(0), 0, appHermesVersion = 96))
    }

    @Test
    fun `truncated bytecode cannot load`() {
        val problem = BundleFileCheck.problem(hermesHead(96, 1000), 500, appHermesVersion = 96)
        assertTrue(problem!!.contains("truncated"))
    }

    @Test
    fun `bytecode for another Hermes cannot load`() {
        val problem = BundleFileCheck.problem(hermesHead(94, 1000), 1000, appHermesVersion = 96)
        assertTrue(problem!!.contains("version 94"))
    }

    @Test
    fun `the Hermes version is not checked when the app's is unknown`() {
        assertNull(BundleFileCheck.problem(hermesHead(94, 1000), 1000, appHermesVersion = null))
    }

    @Test
    fun `plain JavaScript is fine`() {
        val js = "__d(function(g,r,i,a,m,e,d){});".toByteArray()
        assertNull(BundleFileCheck.problem(js, js.size.toLong(), appHermesVersion = 96))
    }

    @Test
    fun `binary junk cannot load`() {
        val junk = ByteArray(600) { (it * 31).toByte() }
        assertNotNull(BundleFileCheck.problem(junk, junk.size.toLong(), appHermesVersion = 96))
    }
}
