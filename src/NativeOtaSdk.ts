/**
 * Codegen spec for the OTA SDK TurboModule.
 *
 * This file defines the native interface that Codegen uses to generate
 * the typed C++ bridge for the New Architecture (TurboModules).
 * On the Old Architecture it is ignored — the module works via
 * NativeModules bridge as usual.
 *
 * Keep this in sync with OtaSdkModule.kt and OtaSdk.swift.
 */
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface ConfigMap {
  appId: string;
  serverUrl: string;
  channel: string;
  crashThreshold: number;
  /** Optional ECDSA P-256 public key (PEM SPKI) embedded at build time for bundle signature verification */
  signingPublicKey?: string;
}

export interface UpdateCheckResult {
  updateAvailable: boolean;
  bundleId?: string;
  downloadUrl?: string;
  hash?: string;
  mandatory?: boolean;
  releaseNotes?: string;
  /** ECDSA-SHA256 signature (base64) — present when the bundle was signed by CI */
  signature?: string;
  /** Delta fields — present when the server has a patch for the device's current bundle */
  patchUrl?: string;
  patchHash?: string;
  fromHash?: string;
}

export interface DownloadOptions {
  patchUrl?: string;
  patchHash?: string;
  fromHash?: string;
  /** ECDSA-SHA256 signature (base64) — forwarded from the update check result */
  signature?: string;
}

export interface DownloadResult {
  bundlePath: string;
  hash: string;
  /** true when a delta patch was applied instead of a full download */
  delta: boolean;
}

export interface SDKStatus {
  /** Server bundle id of the active bundle. Empty when running the embedded bundle. */
  activeBundleId: string;
  activeBundleHash: string;
  activeBundlePath: string;
  hasPending: boolean;
  pendingBundleHash: string;
  crashCount: number;
  /** Compatibility token compiled into the binary. Read-only from JS. */
  runtimeVersion: string;
}

export interface Spec extends TurboModule {
  // Required by NativeEventEmitter (NativeModule contract)
  addListener(eventType: string): void;
  removeListeners(count: number): void;

  configure(config: ConfigMap): void;
  checkForUpdate(): Promise<UpdateCheckResult>;
  downloadBundle(
    bundleId: string,
    downloadUrl: string,
    expectedHash: string,
    options?: DownloadOptions | null,
  ): Promise<DownloadResult>;
  applyPendingBundle(): Promise<string>;
  getStatus(): Promise<SDKStatus>;
  markStable(): void;
  rollback(): Promise<void>;
  /**
   * Restart the React instance so the applied bundle is loaded.
   * Resolves just before the teardown begins — the JS calling it is about to
   * stop existing, so treat the resolution as "accepted", not "finished".
   */
  restartApp(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('OtaSdk');
