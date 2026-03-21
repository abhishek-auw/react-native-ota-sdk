import Foundation

struct OTAConfig {
    let appId: String
    let serverUrl: String
    let channel: String
    let crashThreshold: Int
}

struct UpdateCheckResult {
    let updateAvailable: Bool
    let update: UpdatePayload?
}

struct UpdatePayload {
    let bundleId: String
    let downloadUrl: String
    let hash: String
    let mandatory: Bool
    let releaseNotes: String?
}

/// Thin HTTP client for the OTA backend. Synchronous — always call from background thread.
class OTAApiClient {

    // MARK: - Update Check

    func checkForUpdate(serverUrl: String,
                        appId: String,
                        platform: String,
                        appVersion: String,
                        currentHash: String,
                        channel: String,
                        deviceHash: String) throws -> UpdateCheckResult {

        guard let url = URL(string: "\(serverUrl)/v1/update/check") else {
            throw URLError(.badURL)
        }

        let payload: [String: Any] = [
            "appId":             appId,
            "platform":          platform,
            "appVersion":        appVersion,
            "currentBundleHash": currentHash,
            "channel":           channel,
            "deviceHash":        deviceHash,
        ]

        let json = try JSONSerialization.data(withJSONObject: payload)
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = json

        let (data, response) = try URLSession.shared.synchronousDataTask(with: request)

        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        guard let body = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let updateAvailable = body["updateAvailable"] as? Bool
        else {
            throw URLError(.cannotParseResponse)
        }

        if !updateAvailable { return UpdateCheckResult(updateAvailable: false, update: nil) }

        guard let bundleId    = body["bundleId"]    as? String,
              let downloadUrl = body["downloadUrl"] as? String,
              let hash        = body["hash"]        as? String
        else {
            throw URLError(.cannotParseResponse)
        }

        return UpdateCheckResult(
            updateAvailable: true,
            update: UpdatePayload(
                bundleId:     bundleId,
                downloadUrl:  downloadUrl,
                hash:         hash,
                mandatory:    body["mandatory"] as? Bool ?? false,
                releaseNotes: body["releaseNotes"] as? String
            )
        )
    }

    // MARK: - Analytics (fire and forget)

    func reportEvent(serverUrl: String, appId: String, bundleId: String?,
                     deviceHash: String, eventType: String, platform: String) {
        guard let url = URL(string: "\(serverUrl)/v1/analytics/event") else { return }

        var payload: [String: Any] = [
            "appId":      appId,
            "deviceHash": deviceHash,
            "eventType":  eventType,
            "platform":   platform,
        ]
        if let bid = bundleId { payload["bundleId"] = bid }

        guard let json = try? JSONSerialization.data(withJSONObject: payload) else { return }
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = json

        URLSession.shared.dataTask(with: request).resume() // fire and forget
    }
}

// MARK: - URLSession synchronous helper

extension URLSession {
    func synchronousDataTask(with request: URLRequest) throws -> (Data, URLResponse) {
        var data: Data?
        var response: URLResponse?
        var error: Error?
        let semaphore = DispatchSemaphore(value: 0)
        dataTask(with: request) { d, r, e in
            data = d; response = r; error = e
            semaphore.signal()
        }.resume()
        semaphore.wait()
        if let error = error { throw error }
        return (data ?? Data(), response!)
    }
}
