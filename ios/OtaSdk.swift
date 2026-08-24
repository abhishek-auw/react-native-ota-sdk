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
            appId:            configDict["appId"]          as? String ?? "",
            // Trailing slashes are stripped here rather than at each call site.
            // "https://ota.example.com/" would otherwise build
            // "https://ota.example.com//v1/update/check", which the server
            // treats as a different route and answers with a 404.
            serverUrl:        (configDict["serverUrl"] as? String ?? "")
                                .replacingOccurrences(of: "/+$", with: "", options: .regularExpression),
            channel:          configDict["channel"]         as? String ?? "production",
            crashThreshold:   configDict["crashThreshold"]  as? Int    ?? 3,
            signingPublicKey: configDict["signingPublicKey"] as? String
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
                let runtimeVer  = DeviceInfo.runtimeVersion()
                let currentHash = self.prefs.activeBundleHash

                let result = try self.apiClient.checkForUpdate(
                    serverUrl:   cfg.serverUrl,
                    appId:       cfg.appId,
                    platform:    "ios",
                    appVersion:  appVersion,
                    runtimeVersion: runtimeVer,
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
                    if let notes      = u.releaseNotes { map["releaseNotes"] = notes }
                    // Signing — only present when the bundle was signed by CI
                    if let signature  = u.signature  { map["signature"]  = signature }
                    // Delta fields — only present when server has a patch
                    if let patchUrl  = u.patchUrl  { map["patchUrl"]  = patchUrl }
                    if let patchHash = u.patchHash { map["patchHash"] = patchHash }
                    if let fromHash  = u.fromHash  { map["fromHash"]  = fromHash }
                }
                resolve(map)

            } catch {
                NSLog("[OTA] checkForUpdate failed: %@", error.localizedDescription)
                reject("CHECK_FAILED", error.localizedDescription, error)
            }
        }
    }

    // ── downloadBundle ───────────────────────────────────────────────
    //
    // `options` (optional NSDictionary from JS):
    //   { patchUrl, patchHash, fromHash }
    // When all three are present and the device's active bundle hash matches
    // `fromHash`, we download only the patch and apply it as a delta.

    @objc func downloadBundle(_ bundleId: String,
                               downloadUrl: String,
                               expectedHash: String,
                               options: NSDictionary?,
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

            let patchUrl  = options?["patchUrl"]  as? String
            let patchHash = options?["patchHash"] as? String
            let fromHash  = options?["fromHash"]  as? String
            let signature = options?["signature"] as? String  // ECDSA signature from server
            let activeHash = self.prefs.activeBundleHash
            let useDelta = patchUrl != nil && patchHash != nil && fromHash != nil
                           && fromHash == activeHash

            do {
                let bundlePath: String
                let downloadedZipURL: URL

                if useDelta {
                    NSLog("[OTA] Using delta download for %@ (from=%@)", bundleId, fromHash!)

                    let patchZipURL = self.otaBundleManager.zipURL(forHash: "patch_\(expectedHash)")
                    let patchResult = try self.downloader.download(
                        from: patchUrl!,
                        to: patchZipURL,
                        expectedHash: patchHash!,
                        onProgress: { [weak self] progress in
                            self?.sendEvent(withName: "OTA_DOWNLOAD_PROGRESS",
                                            body: ["bundleId": bundleId, "progress": progress])
                        }
                    )
                    downloadedZipURL = patchResult.fileURL

                    bundlePath = try self.otaBundleManager.applyDeltaPatch(
                        patchZipURL: patchResult.fileURL,
                        fromHash: fromHash!,
                        toHash: expectedHash,
                        bundleId: bundleId
                    )

                } else {
                    NSLog("[OTA] Using full bundle download for %@", bundleId)

                    let zipURL = self.otaBundleManager.zipURL(forHash: expectedHash)
                    let result = try self.downloader.download(
                        from: downloadUrl,
                        to: zipURL,
                        expectedHash: expectedHash,
                        onProgress: { [weak self] progress in
                            self?.sendEvent(withName: "OTA_DOWNLOAD_PROGRESS",
                                            body: ["bundleId": bundleId, "progress": progress])
                        }
                    )
                    downloadedZipURL = result.fileURL
                    bundlePath = try self.otaBundleManager.storePendingBundle(
                        zipURL:   result.fileURL,
                        hash:     result.computedHash,
                        bundleId: bundleId
                    )
                }

                // ── ECDSA signature verification ─────────────────────────────
                if let publicKey = cfg.signingPublicKey, !publicKey.isEmpty {
                    guard let sig = signature, !sig.isEmpty else {
                        reject("SIGNING_REQUIRED",
                               "Bundle signing is enforced but this bundle carries no signature.", nil)
                        return
                    }
                    let valid = HashVerifier.verifyEcdsaSignature(
                        at: downloadedZipURL,
                        signatureB64: sig,
                        publicKeyPem: publicKey
                    )
                    guard valid else {
                        reject("SIGNATURE_INVALID",
                               "Bundle signature verification failed — bundle may be tampered.", nil)
                        return
                    }
                    NSLog("[OTA] Bundle signature verified OK for %@", bundleId)
                }

                // Analytics — fire and forget
                self.apiClient.reportEvent(serverUrl: cfg.serverUrl, appId: cfg.appId,
                                           bundleId: bundleId, deviceHash: DeviceInfo.deviceHash(),
                                           eventType: "download", platform: "ios")

                self.sendEvent(withName: "OTA_EVENT",
                               body: ["type": "download_complete",
                                      "bundleId": bundleId,
                                      "bundlePath": bundlePath,
                                      "delta": useDelta])

                resolve(["bundlePath": bundlePath, "hash": expectedHash, "delta": useDelta])

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
            "activeBundleId":    prefs.activeBundleId,
            "activeBundleHash":  prefs.activeBundleHash,
            "activeBundlePath":  prefs.activeBundlePath ?? "",
            "hasPending":        prefs.pendingBundlePath != nil,
            "pendingBundleHash": prefs.pendingBundleHash,
            "crashCount":        prefs.crashCount,
            // Surfaced so a device can be asked what runtime it declares.
            // Without it, a runtime_version_mismatch is invisible app-side.
            "runtimeVersion":    DeviceInfo.runtimeVersion(),
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
