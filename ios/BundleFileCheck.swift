import Foundation

/// Checks, without loading it, whether a JS bundle file can be loaded by this
/// app — so a broken bundle is rolled back before React Native ever tries it,
/// instead of crashing the app.
///
/// Catches a file that is missing or empty, Hermes bytecode cut short, Hermes
/// bytecode built for a different Hermes than the app ships, and binary junk
/// that is neither bytecode nor JavaScript.
enum BundleFileCheck {

    /// Hermes bytecode files start with this, little-endian.
    private static let hermesMagic: UInt64 = 0x1F19_03C1_03BC_1FC6

    // BytecodeFileHeader: magic u64, version u32, sourceHash u8[20], fileLength u32
    private static let versionOffset = 8
    private static let fileLengthOffset = 32
    private static let headerBytes = 36

    /// How much of a file is read: enough for the header and the text sniff.
    static let headBytes = 512

    /// Hermes bytecode version of a bundle, or nil when it is not bytecode.
    static func hermesVersion(_ head: Data) -> UInt32? {
        let bytes = [UInt8](head)
        guard bytes.count >= headerBytes, readUInt64(bytes, 0) == hermesMagic else { return nil }
        return readUInt32(bytes, versionOffset)
    }

    /// Why the bundle cannot be loaded, or nil when it looks loadable.
    static func problem(head: Data, fileLength: UInt64, appHermesVersion: UInt32?) -> String? {
        if fileLength == 0 { return "the bundle file is empty" }
        let bytes = [UInt8](head)

        if let version = hermesVersion(head) {
            let declared = UInt64(readUInt32(bytes, fileLengthOffset))
            if declared != fileLength {
                return "Hermes bytecode is \(fileLength) bytes but its header says \(declared): the file is truncated or corrupt"
            }
            if let appVersion = appHermesVersion, version != appVersion {
                return "Hermes bytecode version \(version), but this app runs version \(appVersion)"
            }
            return nil
        }

        // Not bytecode, so it has to be JavaScript source, which never
        // contains a NUL byte.
        if bytes.prefix(headBytes).contains(0) {
            return "the bundle is neither Hermes bytecode nor JavaScript"
        }
        return nil
    }

    /// `problem` for a file on disk; a missing file is a problem too.
    static func problem(path: String, appHermesVersion: UInt32?) -> String? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: path),
              let size = attributes[.size] as? NSNumber else {
            return "the bundle file is missing"
        }
        guard let head = readHead(path: path) else {
            return "the bundle file cannot be read"
        }
        return problem(head: head, fileLength: size.uint64Value, appHermesVersion: appHermesVersion)
    }

    static func readHead(path: String) -> Data? {
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { handle.closeFile() }
        return handle.readData(ofLength: headBytes)
    }

    private static func readUInt32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        (0..<4).reduce(UInt32(0)) { $0 | UInt32(bytes[offset + $1]) << (8 * UInt32($1)) }
    }

    private static func readUInt64(_ bytes: [UInt8], _ offset: Int) -> UInt64 {
        (0..<8).reduce(UInt64(0)) { $0 | UInt64(bytes[offset + $1]) << (8 * UInt64($1)) }
    }
}
