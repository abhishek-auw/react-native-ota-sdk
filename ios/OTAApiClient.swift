import Foundation

struct OTAConfig {
    let appId: String
    let serverUrl: String
    let channel: String
    let crashThreshold: Int
    /// Optional ECDSA P-256 public key (PEM SPKI) embedded at build time.
    /// When set, every bundle must carry a valid signature from the server.
    let signingPublicKey: String?
    /// JSON-serialized arbitrary analytics params bag from JS, e.g.
    /// `{"userId":"u1"}`. Parsed and nested under `params` in the update-check
    /// and analytics request bodies. nil when the app passed no params.
    let paramsJson: String?
}

/// Parses the JS-supplied analytics params bag (a JSON object string) into a
/// dictionary. Returns nil for blank or malformed JSON — an analytics add-on
/// must never break an update check.
private func parseParams(_ paramsJson: String?) -> [String: Any]? {
    guard let paramsJson = paramsJson, !paramsJson.isEmpty,
          let data = paramsJson.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    return obj
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
    /// silent | soft | forced — nil from a server that predates update modes
    let updateMode: String?
    let releaseNotes: String?
    /// ECDSA-SHA256 signature (base64) — present when the bundle was signed by CI
    let signature: String?
    /// Delta patch fields — present when server has a patch for the device's current bundle
    let patchUrl: String?
    let patchHash: String?
    let fromHash: String?
}

/// Thin HTTP client for the OTA backend. Synchronous — always call from background thread.
class OTAApiClient {

    // MARK: - Update Check

    func checkForUpdate(serverUrl: String,
                        appId: String,
                        platform: String,
                        appVersion: String,
                        runtimeVersion: String,
                        currentHash: String,
                        channel: String,
                        deviceHash: String,
                        versionCode: String? = nil,
                        deviceModel: String? = nil,
                        deviceBrand: String? = nil,
                        osVersion: String? = nil,
                        paramsJson: String? = nil) throws -> UpdateCheckResult {

        guard let url = URL(string: "\(serverUrl)/v1/update/check") else {
            throw URLError(.badURL)
        }

        var payload: [String: Any] = [
            "appId":             appId,
            "platform":          platform,
            "appVersion":        appVersion,
            "runtimeVersion":    runtimeVersion,
            "currentBundleHash": currentHash,
            "channel":           channel,
            "deviceHash":        deviceHash,
        ]
        if let versionCode = versionCode { payload["versionCode"] = versionCode }
        // Listed under the app's Devices on the dashboard.
        if let deviceModel = deviceModel, !deviceModel.isEmpty { payload["deviceModel"] = deviceModel }
        if let deviceBrand = deviceBrand, !deviceBrand.isEmpty { payload["deviceBrand"] = deviceBrand }
        if let osVersion = osVersion, !osVersion.isEmpty { payload["osVersion"] = osVersion }
        // App-supplied analytics params, e.g. { "userId": "u1" }.
        if let params = parseParams(paramsJson) { payload["params"] = params }

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
                mandatory:    body["mandatory"]    as? Bool   ?? false,
                updateMode:   body["updateMode"]   as? String,
                releaseNotes: body["releaseNotes"] as? String,
                signature:    body["signature"]    as? String,
                patchUrl:     body["patchUrl"]     as? String,
                patchHash:    body["patchHash"]    as? String,
                fromHash:     body["fromHash"]     as? String
            )
        )
    }

    // MARK: - Analytics (fire and forget)

    func reportEvent(serverUrl: String, appId: String, bundleId: String?,
                     deviceHash: String, eventType: String, platform: String,
                     paramsJson: String? = nil) {
        guard let url = URL(string: "\(serverUrl)/v1/analytics/event") else { return }

        var payload: [String: Any] = [
            "appId":      appId,
            "deviceHash": deviceHash,
            "eventType":  eventType,
            "platform":   platform,
        ]
        if let bid = bundleId { payload["bundleId"] = bid }
        // App-supplied analytics params, e.g. { "userId": "u1" }.
        if let params = parseParams(paramsJson) { payload["params"] = params }

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
