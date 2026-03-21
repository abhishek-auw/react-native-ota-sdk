import Foundation

struct DownloadResult {
    let fileURL: URL
    let computedHash: String
}

enum DownloadError: Error {
    case networkError(String)
    case serverError(Int)
    case hashMismatch(expected: String, got: String)
    case writeError(String)

    var message: String {
        switch self {
        case .networkError(let m):     return "Network error: \(m)"
        case .serverError(let code):  return "Server returned HTTP \(code)"
        case .hashMismatch(let e, let g): return "Hash mismatch: expected=\(e) got=\(g)"
        case .writeError(let m):      return "Write error: \(m)"
        }
    }
}

/// Downloads a bundle from a URL, streams to disk, and verifies SHA-256.
/// Uses URLSession with a synchronous pattern — always called from a background thread.
class BundleDownloader: NSObject {

    typealias ProgressHandler = (Double) -> Void

    /// Download bundle to destURL, verify hash, return result.
    func download(from urlString: String,
                  to destURL: URL,
                  expectedHash: String,
                  onProgress: ProgressHandler? = nil) throws -> DownloadResult {

        guard let url = URL(string: urlString) else {
            throw DownloadError.networkError("Invalid URL: \(urlString)")
        }

        // Ensure parent directory exists
        try FileManager.default.createDirectory(
            at: destURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        // Synchronous download with progress tracking
        let semaphore = DispatchSemaphore(value: 0)
        var downloadError: Error?

        let session = URLSession(configuration: .ephemeral)
        var totalBytes: Int64 = 0
        var receivedBytes: Int64 = 0

        let task = session.dataTask(with: url) { [weak self] data, response, error in
            defer { semaphore.signal() }

            if let error = error {
                downloadError = DownloadError.networkError(error.localizedDescription)
                return
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                downloadError = DownloadError.networkError("Invalid response")
                return
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                downloadError = DownloadError.serverError(httpResponse.statusCode)
                return
            }

            guard let data = data else {
                downloadError = DownloadError.networkError("Empty response body")
                return
            }

            do {
                try data.write(to: destURL, options: .atomic)
            } catch {
                downloadError = DownloadError.writeError(error.localizedDescription)
            }
        }

        // Observe progress
        let observation = task.progress.observe(\.fractionCompleted) { progress, _ in
            onProgress?(progress.fractionCompleted)
        }

        task.resume()
        semaphore.wait()
        observation.invalidate()

        if let err = downloadError {
            try? FileManager.default.removeItem(at: destURL)
            throw err
        }

        // Verify SHA-256 BEFORE returning
        let computedHash = try HashVerifier.computeFileHash(at: destURL)
        guard HashVerifier.verifyFile(at: destURL, expectedHash: expectedHash) else {
            try? FileManager.default.removeItem(at: destURL)
            throw DownloadError.hashMismatch(expected: expectedHash, got: computedHash)
        }

        return DownloadResult(fileURL: destURL, computedHash: computedHash)
    }
}
