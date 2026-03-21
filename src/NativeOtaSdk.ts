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
}

export interface UpdateCheckResult {
  updateAvailable: boolean;
  bundleId?: string;
  downloadUrl?: string;
  hash?: string;
  mandatory?: boolean;
  releaseNotes?: string;
}

export interface DownloadResult {
  bundlePath: string;
  hash: string;
}

export interface SDKStatus {
  activeBundleHash: string;
  activeBundlePath: string;
  hasPending: boolean;
  pendingBundleHash: string;
  crashCount: number;
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
  ): Promise<DownloadResult>;
  applyPendingBundle(): Promise<string>;
  getStatus(): Promise<SDKStatus>;
  markStable(): void;
  rollback(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('OtaSdk');
