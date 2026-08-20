package com.otasdk

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * bspatch — applies a binary patch produced by the server's bsdiff.
 *
 * Only the patch side lives on device; generating diffs is the server's job.
 *
 * Patch layout (see packages/backend/src/utils/bsdiff.ts):
 *
 *   magic        8 bytes   "OTABSDF1"
 *   ctrlLen      8 bytes   compressed length of the control block
 *   diffLen      8 bytes   compressed length of the diff block
 *   newSize      8 bytes   length of the reconstructed file
 *   ctrl block   zlib      triples of (addLen, extraLen, oldSeek)
 *   diff block   zlib      byte-wise deltas for the "add" runs
 *   extra block  zlib      literal bytes with no match in old
 *
 * Offsets are sign-magnitude, NOT two's complement: eight little-endian bytes
 * where bit 7 of the last byte carries the sign. Reading these as a normal
 * signed long produces silently wrong values for the (frequently negative)
 * oldSeek field.
 */
object BsPatch {

    private const val MAGIC = "OTABSDF1"
    private const val HEADER_SIZE = 32

    class PatchFormatException(message: String) : Exception(message)

    /**
     * Reconstruct the new file from [oldData] and [patchData].
     *
     * @throws PatchFormatException if the patch is malformed or truncated.
     */
    @JvmStatic
    fun apply(oldData: ByteArray, patchData: ByteArray): ByteArray {
        if (patchData.size < HEADER_SIZE) {
            throw PatchFormatException("Patch is smaller than its header")
        }

        val magic = String(patchData, 0, 8, Charsets.US_ASCII)
        if (magic != MAGIC) {
            throw PatchFormatException("Bad magic: expected $MAGIC, got $magic")
        }

        val ctrlLen = readOffset(patchData, 8)
        val diffLen = readOffset(patchData, 16)
        val newSize = readOffset(patchData, 24)

        if (ctrlLen < 0 || diffLen < 0 || newSize < 0) {
            throw PatchFormatException("Negative length in patch header")
        }
        if (newSize > Int.MAX_VALUE) {
            throw PatchFormatException("Reconstructed size $newSize exceeds addressable range")
        }
        if (HEADER_SIZE + ctrlLen + diffLen > patchData.size) {
            throw PatchFormatException("Patch is truncated")
        }

        val ctrlStart = HEADER_SIZE
        val diffStart = (ctrlStart + ctrlLen).toInt()
        val extraStart = (diffStart + diffLen).toInt()

        val ctrl = inflate(patchData, ctrlStart, ctrlLen.toInt(), "control")
        val diff = inflate(patchData, diffStart, diffLen.toInt(), "diff")
        val extra = inflate(patchData, extraStart, patchData.size - extraStart, "extra")

        val newData = ByteArray(newSize.toInt())
        var oldPos = 0L
        var newPos = 0
        var ctrlPos = 0
        var diffPos = 0
        var extraPos = 0

        while (newPos < newSize) {
            if (ctrlPos + 24 > ctrl.size) {
                throw PatchFormatException("Control block exhausted before output was full")
            }

            val addLen = readOffset(ctrl, ctrlPos)
            val extraLen = readOffset(ctrl, ctrlPos + 8)
            val oldSeek = readOffset(ctrl, ctrlPos + 16)
            ctrlPos += 24

            if (addLen < 0 || extraLen < 0) {
                throw PatchFormatException("Negative run length in control block")
            }
            if (newPos + addLen > newSize) {
                throw PatchFormatException("Add run overruns the output buffer")
            }
            if (diffPos + addLen > diff.size) {
                throw PatchFormatException("Diff block exhausted")
            }

            // Copy from old, adding the byte-wise delta. Positions outside the
            // old file contribute zero, matching the reference implementation.
            for (i in 0 until addLen.toInt()) {
                val oldIndex = oldPos + i
                val oldByte = if (oldIndex in 0 until oldData.size.toLong()) {
                    oldData[oldIndex.toInt()].toInt() and 0xff
                } else {
                    0
                }
                val delta = diff[diffPos + i].toInt() and 0xff
                newData[newPos + i] = ((delta + oldByte) and 0xff).toByte()
            }

            newPos += addLen.toInt()
            oldPos += addLen
            diffPos += addLen.toInt()

            if (newPos + extraLen > newSize) {
                throw PatchFormatException("Extra run overruns the output buffer")
            }
            if (extraPos + extraLen > extra.size) {
                throw PatchFormatException("Extra block exhausted")
            }

            System.arraycopy(extra, extraPos, newData, newPos, extraLen.toInt())
            newPos += extraLen.toInt()
            extraPos += extraLen.toInt()

            oldPos += oldSeek
        }

        return newData
    }

    /** Read a sign-magnitude 64-bit offset. */
    private fun readOffset(buf: ByteArray, offset: Int): Long {
        var y = (buf[offset + 7].toLong() and 0x7f)
        for (i in 6 downTo 0) {
            y = y * 256 + (buf[offset + i].toLong() and 0xff)
        }
        return if ((buf[offset + 7].toInt() and 0x80) != 0) -y else y
    }

    private fun inflate(src: ByteArray, offset: Int, length: Int, label: String): ByteArray {
        if (length < 0 || offset + length > src.size) {
            throw PatchFormatException("$label block extends past the end of the patch")
        }

        val inflater = Inflater()
        try {
            inflater.setInput(src, offset, length)
            val out = ByteArrayOutputStream(maxOf(length * 4, 1024))
            val buffer = ByteArray(64 * 1024)

            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (e: Exception) {
            throw PatchFormatException("$label block failed to decompress: ${e.message}")
        } finally {
            inflater.end()
        }
    }
}
