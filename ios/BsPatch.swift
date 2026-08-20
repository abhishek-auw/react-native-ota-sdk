import Foundation
import Compression

/// bspatch — applies a binary patch produced by the server's bsdiff.
///
/// Only the patch side lives on device; generating diffs is the server's job.
///
/// Patch layout (see packages/backend/src/utils/bsdiff.ts):
///
///     magic        8 bytes   "OTABSDF1"
///     ctrlLen      8 bytes   compressed length of the control block
///     diffLen      8 bytes   compressed length of the diff block
///     newSize      8 bytes   length of the reconstructed file
///     ctrl block   zlib      triples of (addLen, extraLen, oldSeek)
///     diff block   zlib      byte-wise deltas for the "add" runs
///     extra block  zlib      literal bytes with no match in old
///
/// Offsets are sign-magnitude, NOT two's complement: eight little-endian bytes
/// where bit 7 of the last byte carries the sign. Reading these as a regular
/// Int64 gives silently wrong values for the (frequently negative) oldSeek.
enum BsPatch {

    private static let magic = "OTABSDF1"
    private static let headerSize = 32

    enum PatchError: LocalizedError {
        case malformed(String)

        var errorDescription: String? {
            switch self {
            case .malformed(let reason): return "Invalid bsdiff patch: \(reason)"
            }
        }
    }

    /// Reconstruct the new file from `oldData` and `patchData`.
    static func apply(oldData: Data, patchData: Data) throws -> Data {
        guard patchData.count >= headerSize else {
            throw PatchError.malformed("patch is smaller than its header")
        }

        let header = [UInt8](patchData.prefix(headerSize))

        let foundMagic = String(bytes: header[0..<8], encoding: .ascii) ?? ""
        guard foundMagic == magic else {
            throw PatchError.malformed("bad magic: expected \(magic), got \(foundMagic)")
        }

        let ctrlLen = readOffset(header, 8)
        let diffLen = readOffset(header, 16)
        let newSize = readOffset(header, 24)

        guard ctrlLen >= 0, diffLen >= 0, newSize >= 0 else {
            throw PatchError.malformed("negative length in header")
        }
        guard newSize <= Int64(Int.max) else {
            throw PatchError.malformed("reconstructed size exceeds addressable range")
        }
        guard headerSize + Int(ctrlLen) + Int(diffLen) <= patchData.count else {
            throw PatchError.malformed("patch is truncated")
        }

        let ctrlStart = headerSize
        let diffStart = ctrlStart + Int(ctrlLen)
        let extraStart = diffStart + Int(diffLen)

        let ctrl = try inflate(patchData.subdata(in: ctrlStart..<diffStart), label: "control")
        let diff = try inflate(patchData.subdata(in: diffStart..<extraStart), label: "diff")
        let extra = try inflate(
            patchData.subdata(in: extraStart..<patchData.count), label: "extra"
        )

        let targetSize = Int(newSize)
        var newBytes = [UInt8](repeating: 0, count: targetSize)
        let oldBytes = [UInt8](oldData)
        let ctrlBytes = [UInt8](ctrl)
        let diffBytes = [UInt8](diff)
        let extraBytes = [UInt8](extra)

        var oldPos = 0
        var newPos = 0
        var ctrlPos = 0
        var diffPos = 0
        var extraPos = 0

        while newPos < targetSize {
            guard ctrlPos + 24 <= ctrlBytes.count else {
                throw PatchError.malformed("control block exhausted before output was full")
            }

            let addLen = Int(readOffset(ctrlBytes, ctrlPos))
            let extraLen = Int(readOffset(ctrlBytes, ctrlPos + 8))
            let oldSeek = Int(readOffset(ctrlBytes, ctrlPos + 16))
            ctrlPos += 24

            guard addLen >= 0, extraLen >= 0 else {
                throw PatchError.malformed("negative run length in control block")
            }
            guard newPos + addLen <= targetSize else {
                throw PatchError.malformed("add run overruns the output buffer")
            }
            guard diffPos + addLen <= diffBytes.count else {
                throw PatchError.malformed("diff block exhausted")
            }

            // Copy from old, adding the byte-wise delta. Positions outside the
            // old file contribute zero, matching the reference implementation.
            for i in 0..<addLen {
                let oldIndex = oldPos + i
                let oldByte: UInt8 = (oldIndex >= 0 && oldIndex < oldBytes.count)
                    ? oldBytes[oldIndex]
                    : 0
                newBytes[newPos + i] = diffBytes[diffPos + i] &+ oldByte
            }

            newPos += addLen
            oldPos += addLen
            diffPos += addLen

            guard newPos + extraLen <= targetSize else {
                throw PatchError.malformed("extra run overruns the output buffer")
            }
            guard extraPos + extraLen <= extraBytes.count else {
                throw PatchError.malformed("extra block exhausted")
            }

            if extraLen > 0 {
                newBytes.replaceSubrange(
                    newPos..<(newPos + extraLen),
                    with: extraBytes[extraPos..<(extraPos + extraLen)]
                )
            }
            newPos += extraLen
            extraPos += extraLen

            oldPos += oldSeek
        }

        return Data(newBytes)
    }

    /// Read a sign-magnitude 64-bit offset.
    private static func readOffset(_ buf: [UInt8], _ offset: Int) -> Int64 {
        var y = Int64(buf[offset + 7] & 0x7f)
        var i = 6
        while i >= 0 {
            y = y * 256 + Int64(buf[offset + i])
            i -= 1
        }
        return (buf[offset + 7] & 0x80) != 0 ? -y : y
    }

    /// Inflate a zlib stream.
    ///
    /// Apple's Compression framework speaks raw DEFLATE, not zlib, so the
    /// 2-byte zlib header and 4-byte Adler-32 trailer are stripped first.
    private static func inflate(_ data: Data, label: String) throws -> Data {
        guard data.count > 6 else {
            // Nothing but header and trailer — an empty block is legitimate
            // (e.g. no deletions), so return empty rather than failing.
            return Data()
        }

        // An empty block is common, not exceptional: a patch with no "extra"
        // bytes (every byte matched the base) compresses to an 8-byte stream
        // that inflates to nothing. compression_decode_buffer returns 0 for
        // both "produced no output" and "failed", so the empty case has to be
        // recognised up front or every such patch is rejected as corrupt.
        //
        // Adler-32 of an empty input is exactly 1, so a legitimately empty
        // stream always ends with 00 00 00 01.
        let trailer = data.suffix(4)
        if trailer.elementsEqual([0x00, 0x00, 0x00, 0x01]) {
            return Data()
        }

        let raw = data.subdata(in: 2..<(data.count - 4))

        // Output size is unknown up front. Grow the buffer until decompression
        // stops filling it completely.
        var capacity = max(raw.count * 4, 64 * 1024)

        for _ in 0..<8 {
            var output = Data(count: capacity)
            let written: Int = output.withUnsafeMutableBytes { outPtr -> Int in
                guard let outBase = outPtr.bindMemory(to: UInt8.self).baseAddress else {
                    return 0
                }
                return raw.withUnsafeBytes { inPtr -> Int in
                    guard let inBase = inPtr.bindMemory(to: UInt8.self).baseAddress else {
                        return 0
                    }
                    return compression_decode_buffer(
                        outBase, capacity,
                        inBase, raw.count,
                        nil, COMPRESSION_ZLIB
                    )
                }
            }

            if written == 0 {
                throw PatchError.malformed("\(label) block failed to decompress")
            }
            if written < capacity {
                return output.prefix(written)
            }
            // Filled the buffer exactly — output may have been truncated.
            capacity *= 4
        }

        throw PatchError.malformed("\(label) block is implausibly large")
    }
}
