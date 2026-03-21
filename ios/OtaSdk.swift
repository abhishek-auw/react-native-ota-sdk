import Foundation
import CommonCrypto
import React

// MARK: - OtaSdk (Main Native Module)

@objc(OtaSdk)
class OtaSdk: RCTEventEmitter {

    private var config: OTAConfig?
    private let prefs       = OTAPrefs()
    private let downloader  = BundleDownloader()
    private let apiClient   = OTAApiClient()
  internal lazy var otaBundleManager = BundleManager(prefs: prefs)
    private lazy var crashGuard    = CrashGuard(prefs: prefs)

    // ── RCTEventEmitter ──────────────────────────────────────────────

    override static func requiresMainQueueSetup() -> Bool { false }

    override func supportedEvents() -> [String]! {
        return ["OTA_DOWNLOAD_PROGRESS", "OTA_EVENT"]
    }

    // ── configure ────────────────────────────────────────────────────

    @objc func configure(_ configDict: NSDictionary) {
        config = OTAConfig(
            appId:          configDict["appId"]      as? String ?? "",
            serverUrl:      configDict["serverUrl"]  as? String ?? "",
            channel:        configDict["channel"]    as? String ?? "production",
            crashThreshold: configDict["crashThreshold"] as? Int ?? 3
        )
        NSLog("[OTA] Configured appId=%@ server=%@", config!.appId, config!.serverUrl)

        // Run crash guard check on every app start
        DispatchQueue.global(qos: .utility).async { [weak self] in
            self?.runCrashGuardCheck()
        }
    }

    // ── checkForUpdate ───────────────────────────────────────────────

    @objc func checkForUpdate(_ resolve: @escaping RCTPromiseResolveBlock,
                               rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard let cfg = config else {
            reject("NOT_CONFIGURED", "Call configure() first", nil)
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            do {
                let deviceHash  = DeviceInfo.deviceHash()
                let appVersion  = DeviceInfo.appVersion()
                let currentHash = self.prefs.activeBundleHash

                let result = try self.apiClient.checkForUpdate(
                    serverUrl:   cfg.serverUrl,
                    appId:       cfg.appId,
                    platform:    "ios",
                    appVersion:  appVersion,
                    currentHash: currentHash,
                    channel:     cfg.channel,
                    deviceHash:  deviceHash
                )

                var map: [String: Any] = ["updateAvailable": result.updateAvailable]
                if result.updateAvailable, let u = result.update {
                    map["bundleId"]    = u.bundleId
                    map["downloadUrl"] = u.downloadUrl
                    map["hash"]        = u.hash
                    map["mandatory"]   = u.mandatory
                    if let notes = u.releaseNotes { map["releaseNotes"] = notes }
                }
                resolve(map)

            } catch {
                NSLog("[OTA] checkForUpdate failed: %@", error.localizedDescription)
                reject("CHECK_FAILED", error.localizedDescription, error)
            }
        }
    }

    // ── downloadBundle ───────────────────────────────────────────────

    @objc func downloadBundle(_ bundleId: String,
                               downloadUrl: String,
                               expectedHash: String,
                               resolver resolve: @escaping RCTPromiseResolveBlock,
                               rejecter  reject: @escaping RCTPromiseRejectBlock) {
        guard let cfg = config else {
            reject("NOT_CONFIGURED", "Call configure() first", nil)
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }

            self.sendEvent(withName: "OTA_EVENT",
                           body: ["type": "download_started", "bundleId": bundleId])

            let zipURL = self.otaBundleManager.zipURL(forHash: expectedHash)

            do {
                let result = try self.downloader.download(
                    from: downloadUrl,
                    to: zipURL,
                    expectedHash: expectedHash,
                    onProgress: { [weak self] progress in
                        self?.sendEvent(withName: "OTA_DOWNLOAD_PROGRESS",
                                        body: ["bundleId": bundleId, "progress": progress])
                    }
                )

                let bundlePath = try self.otaBundleManager.storePendingBundle(
                    zipURL:   result.fileURL,
                    hash:     result.computedHash,
                    bundleId: bundleId
                )

                // Analytics — fire and forget
                self.apiClient.reportEvent(serverUrl: cfg.serverUrl, appId: cfg.appId,
                                           bundleId: bundleId, deviceHash: DeviceInfo.deviceHash(),
                                           eventType: "download", platform: "ios")

                self.sendEvent(withName: "OTA_EVENT",
                               body: ["type": "download_complete",
                                      "bundleId": bundleId,
                                      "bundlePath": bundlePath])

                resolve(["bundlePath": bundlePath, "hash": result.computedHash])

            } catch let e as DownloadError {
                NSLog("[OTA] Download failed: %@", e.message)
                self.sendEvent(withName: "OTA_EVENT",
                               body: ["type": "download_failed", "error": e.message])
                reject("DOWNLOAD_FAILED", e.message, nil)

            } catch {
                reject("DOWNLOAD_FAILED", error.localizedDescription, error)
            }
        }
    }

    // ── applyPendingBundle ───────────────────────────────────────────

    @objc func applyPendingBundle(_ resolve: @escaping RCTPromiseResolveBlock,
                                   rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard let pendingPath = prefs.pendingBundlePath else {
            reject("NO_PENDING", "No pending bundle to apply", nil)
            return
        }
        otaBundleManager.applyPendingBundle()
        sendEvent(withName: "OTA_EVENT",
                  body: ["type": "bundle_applied", "path": pendingPath])
        resolve(pendingPath)
    }

    // ── getStatus ────────────────────────────────────────────────────

    @objc func getStatus(_ resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve([
            "activeBundleHash":  prefs.activeBundleHash,
            "activeBundlePath":  prefs.activeBundlePath ?? "",
            "hasPending":        prefs.pendingBundlePath != nil,
            "pendingBundleHash": prefs.pendingBundleHash,
            "crashCount":        prefs.crashCount,
        ])
    }

    // ── markStable ───────────────────────────────────────────────────

    @objc func markStable() {
        crashGuard.markStable()
        if let cfg = config, !prefs.activeBundleHash.isEmpty {
            DispatchQueue.global(qos: .background).async { [weak self] in
                guard let self else { return }
                self.apiClient.reportEvent(
                    serverUrl: cfg.serverUrl, appId: cfg.appId,
                    bundleId: nil, deviceHash: DeviceInfo.deviceHash(),
                    eventType: "install_ok", platform: "ios"
                )
            }
        }
    }

    // ── rollback ─────────────────────────────────────────────────────

    @objc func rollback(_ resolve: @escaping RCTPromiseResolveBlock,
                         rejecter reject: @escaping RCTPromiseRejectBlock) {
        otaBundleManager.rollback()
        sendEvent(withName: "OTA_EVENT", body: ["type": "rollback"])
        resolve(true)
    }

    // ── Private ───────────────────────────────────────────────────────

    private func runCrashGuardCheck() {
        let shouldRollback = crashGuard.onAppStart()
        if shouldRollback {
            NSLog("[OTA] Crash guard triggered rollback")
            otaBundleManager.rollback()
            if let cfg = config {
                apiClient.reportEvent(
                    serverUrl: cfg.serverUrl, appId: cfg.appId,
                    bundleId: prefs.activeBundleHash.isEmpty ? nil : prefs.activeBundleHash,
                    deviceHash: DeviceInfo.deviceHash(),
                    eventType: "crash", platform: "ios"
                )
            }
            sendEvent(withName: "OTA_EVENT",
                      body: ["type": "rollback", "reason": "crash_threshold"])
        }
    }
}
