package com.otasdk

import java.io.File
import java.io.InputStream

/**
 * Checks, without loading it, whether a JS bundle file can be loaded by this
 * app — so a broken bundle is rolled back before React Native ever tries it,
 * instead of crashing the app.
 *
 * Catches a file that is missing or empty, Hermes bytecode cut short, Hermes
 * bytecode built for a different Hermes than the app ships, and binary junk
 * that is neither bytecode nor JavaScript.
 */
object BundleFileCheck {

    /** Hermes bytecode files start with this, little-endian. */
    private const val HERMES_MAGIC = 0x1F1903C103BC1FC6L

    // BytecodeFileHeader: magic u64, version u32, sourceHash u8[20], fileLength u32
    private const val VERSION_OFFSET = 8
    private const val FILE_LENGTH_OFFSET = 32
    private const val HEADER_BYTES = 36

    /** How much of a non-bytecode file is looked at for binary junk. */
    private const val TEXT_SNIFF_BYTES = 512

    /** Hermes bytecode version of a bundle, or null when it is not bytecode. */
    fun hermesVersion(head: ByteArray): Int? {
        if (head.size < HEADER_BYTES || readLong(head, 0) != HERMES_MAGIC) return null
        return readInt(head, VERSION_OFFSET)
    }

    /**
     * Why the bundle cannot be loaded, or null when it looks loadable.
     *
     * @param head the first bytes of the file (at least [TEXT_SNIFF_BYTES] when
     *        the file has that many)
     * @param fileLength the file's size on disk
     * @param appHermesVersion bytecode version of the bundle the app ships
     *        with, or null when unknown or not bytecode
     */
    fun problem(head: ByteArray, fileLength: Long, appHermesVersion: Int?): String? {
        if (fileLength == 0L) return "the bundle file is empty"

        val version = hermesVersion(head)
        if (version != null) {
            val declared = readInt(head, FILE_LENGTH_OFFSET).toLong() and 0xFFFFFFFFL
            if (declared != fileLength) {
                return "Hermes bytecode is $fileLength bytes but its header says $declared: the file is truncated or corrupt"
            }
            if (appHermesVersion != null && version != appHermesVersion) {
                return "Hermes bytecode version $version, but this app runs version $appHermesVersion"
            }
            return null
        }

        // Not bytecode, so it has to be JavaScript source, which never
        // contains a NUL byte.
        val sniff = minOf(head.size, TEXT_SNIFF_BYTES)
        if ((0 until sniff).any { head[it] == 0.toByte() }) {
            return "the bundle is neither Hermes bytecode nor JavaScript"
        }
        return null
    }

    /** [problem] for a file on disk; a missing file is a problem too. */
    fun problem(file: File, appHermesVersion: Int?): String? {
        if (!file.isFile) return "the bundle file is missing"
        val head = file.inputStream().use { readHead(it) }
        return problem(head, file.length(), appHermesVersion)
    }

    fun readHead(input: InputStream): ByteArray {
        val buffer = ByteArray(TEXT_SNIFF_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i)) }

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i)) }
}
