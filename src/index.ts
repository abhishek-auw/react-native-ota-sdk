/**
 * react-native-ota-sdk
 *
 * JS side is intentionally thin — it just bridges to native.
 * All heavy work (HTTP, file I/O, hashing, crash guard) runs in Swift/Kotlin.
 *
 * New Architecture: uses TurboModuleRegistry via NativeOtaSdk spec.
 * Old Architecture: falls back to NativeModules bridge automatically.
 */
import {
  NativeModules,
  NativeEventEmitter,
} from 'react-native';
import type { EmitterSubscription } from 'react-native';
import NativeOtaSdk from './NativeOtaSdk';

// Prefer TurboModule (New Architecture); fall back to bridge (Old Architecture)
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const OtaSdk: any = NativeOtaSdk ?? NativeModules.OtaSdk;

// Warn at import time but don't throw — the app might import this module
// before native linking is complete (e.g. during a JS-only dev reload).
// Each function will throw if OtaSdk is still missing when actually called.
if (!OtaSdk) {
  console.warn(
    'react-native-ota-sdk: Native module not found. ' +
      'Run `cd ios && pod install` (iOS) or rebuild the app (Android).',
  );
}

// NativeEventEmitter requires a non-null native module — guard it
const emitter = OtaSdk ? new NativeEventEmitter(OtaSdk) : null;

/** Asserts the native module is available before any call */
function assertNative(): void {
  if (!OtaSdk) {
    throw new Error(
      'react-native-ota-sdk: Native module not found.\n' +
        'Run `cd ios && pod install` (iOS) or rebuild the app (Android).',
    );
  }
}

// ── Types ─────────────────────────────────────────────────────────────

export interface OTAConfig {
  appId: string;
  serverUrl: string;
  channel?: string;
  crashThreshold?: number;
  /**
   * Optional ECDSA P-256 public key (PEM SPKI format), embedded in the app at build time.
   * When provided, every bundle downloaded from the server must carry a valid signature.
   * Bundles without a signature, or with an invalid one, will be rejected.
   *
   * Generate a key pair in the dashboard (Apps → Signing Key). Store the private key as
   * OTA_SIGNING_KEY in CI so the CLI can sign bundles on upload.
   */
  signingPublicKey?: string;
}

export interface UpdateInfo {
  updateAvailable: true;
  bundleId: string;
  downloadUrl: string;
  hash: string;
  mandatory: boolean;
  releaseNotes?: string;
  /** ECDSA-SHA256 signature (base64) — present when the bundle was signed by CI */
  signature?: string;
  /** Delta patch — present when the server has a patch from the device's current bundle */
  patchUrl?: string;
  patchHash?: string;
  fromHash?: string;
}

export interface NoUpdate {
  updateAvailable: false;
}

export interface SDKStatus {
  activeBundleHash: string;
  activeBundlePath: string;
  hasPending: boolean;
  pendingBundleHash: string;
  crashCount: number;
}

export interface DownloadProgressEvent {
  bundleId: string;
  progress: number; // 0.0 – 1.0
}

export type OTANativeEvent =
  | { type: 'download_started'; bundleId: string }
  | { type: 'download_complete'; bundleId: string; bundlePath: string; delta: boolean }
  | { type: 'download_failed'; error: string }
  | { type: 'bundle_applied'; path: string }
  | { type: 'rollback'; reason?: string };

// ── Core API ──────────────────────────────────────────────────────────

/**
 * Configure the SDK. Call this once, before any other method.
 * Usually in your App.tsx or index.js.
 */
export function configure(config: OTAConfig): void {
  assertNative();
  OtaSdk.configure({
    appId:            config.appId,
    serverUrl:        config.serverUrl,
    channel:          config.channel ?? 'production',
    crashThreshold:   config.crashThreshold ?? 3,
    signingPublicKey: config.signingPublicKey ?? null,
  });
}

/**
 * Check for an available update. All network work happens natively.
 * Returns update info if available, or { updateAvailable: false }.
 */
export async function checkForUpdate(): Promise<UpdateInfo | NoUpdate> {
  assertNative();
  const result = await OtaSdk.checkForUpdate();
  if (!result.updateAvailable) {
    return { updateAvailable: false } as NoUpdate;
  }
  return result as UpdateInfo;
}

/**
 * Download a bundle to local device storage.
 * Native side streams the file, verifies SHA-256, and stores it.
 *
 * When `options` contains `patchUrl` / `patchHash` / `fromHash` (from the
 * checkForUpdate response), the native layer will download only the delta
 * patch and apply it over the current bundle — saving bandwidth.
 */
export function downloadBundle(
  bundleId: string,
  downloadUrl: string,
  expectedHash: string,
  options?: { patchUrl?: string; patchHash?: string; fromHash?: string; signature?: string },
): Promise<{ bundlePath: string; hash: string; delta: boolean }> {
  assertNative();
  return OtaSdk.downloadBundle(bundleId, downloadUrl, expectedHash, options ?? null);
}

/**
 * Mark the downloaded bundle as active. Takes effect on next JS reload.
 * Returns the bundle path.
 */
export function applyPendingBundle(): Promise<string> {
  return OtaSdk.applyPendingBundle();
}

/**
 * Get current SDK state from native layer.
 */
export function getStatus(): Promise<SDKStatus> {
  return OtaSdk.getStatus();
}

/**
 * Call this once your app has been running stably for ~5 seconds.
 * Resets the crash counter and reports install_ok to analytics.
 */
export function markStable(): void {
  OtaSdk.markStable();
}

/**
 * Manually trigger a rollback to the embedded (shipped) bundle.
 */
export function rollback(): Promise<void> {
  return OtaSdk.rollback();
}

// ── Events ────────────────────────────────────────────────────────────

export function onDownloadProgress(
  handler: (event: DownloadProgressEvent) => void,
): EmitterSubscription {
  assertNative();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return emitter!.addListener('OTA_DOWNLOAD_PROGRESS', handler as any);
}

export function onOTAEvent(
  handler: (event: OTANativeEvent) => void,
): EmitterSubscription {
  assertNative();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return emitter!.addListener('OTA_EVENT', handler as any);
}

// ── Convenience: check + auto-download ───────────────────────────────

/**
 * Full update flow in one call:
 *   1. Check for update
 *   2. If available, download + apply
 *   3. If mandatory, reload immediately; otherwise return the update info
 */
export async function checkAndApply(options?: {
  onProgress?: (progress: number) => void;
}): Promise<UpdateInfo | NoUpdate> {
  const result = await checkForUpdate();
  if (!result.updateAvailable) return result;

  const sub = options?.onProgress
    ? onDownloadProgress(({ progress }) => options.onProgress!(progress))
    : null;

  try {
    // Pass delta + signature options — native verifies signature and uses patch if applicable
    await downloadBundle(result.bundleId, result.downloadUrl, result.hash, {
      patchUrl:  result.patchUrl,
      patchHash: result.patchHash,
      fromHash:  result.fromHash,
      signature: result.signature,
    });
    await applyPendingBundle();
    if (result.mandatory) {
      // Native will reload the JS bundle on next resume
      // For immediate apply, call RCTReloadCommand from native (done in BundleManager)
    }
  } finally {
    sub?.remove();
  }

  return result;
}

// ── React integration ─────────────────────────────────────────────────
// OTAProvider and useOTA live in their own file to keep the core module
// importable in non-React environments (e.g. CLI / Node scripts).
export { OTAProvider, useOTA } from './OTAProvider';
