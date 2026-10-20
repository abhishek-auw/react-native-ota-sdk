/**
 * Singleton OTA store — the stateful core behind `useOTA`.
 *
 * All OTA state lives here, at module scope, so it is shared by every component
 * that calls `useOTA()` without a React context/provider. The hook subscribes
 * to it via `useSyncExternalStore`. Configuration, native event subscriptions,
 * the auto-confirm timer and the initial update check are each guarded to run
 * exactly once — no matter how many components mount the hook.
 *
 * These subscriptions are intentionally never torn down: an OTA manager is a
 * process-global concern that should outlive any single screen. (The old
 * OTAProvider cleaned up on unmount; a singleton does not need to.)
 */
import { AppState } from 'react-native';
import {
  configure as nativeConfigure,
  checkForUpdate as nativeCheckForUpdate,
  downloadBundle,
  applyPendingBundle,
  getStatus,
  notifyAppReady,
  onDownloadProgress,
  onOTAEvent,
  restartApp as nativeRestartApp,
} from './index';
import type { OTAConfig, UpdateInfo, ActiveBundleInfo } from './index';

export type OTAStatus =
  | 'idle'
  | 'checking'
  | 'update_available'
  | 'downloading'
  | 'ready_to_install'
  | 'up_to_date'
  | 'error';

export interface OTASnapshot {
  status: OTAStatus;
  updateInfo: UpdateInfo | null;
  /** 0–1 */
  downloadProgress: number;
  error: string | null;
  /**
   * Which bundle the running JS was loaded from. null until the native layer
   * answers (shortly after the first configure), and where native is missing.
   */
  activeBundle: ActiveBundleInfo | null;
  /**
   * false until a config carrying a real `serverUrl` has been handed to
   * `configure()`. Lets the UI tell "still fetching the OTA URL" apart from
   * "checked, nothing to do".
   */
  configured: boolean;
}

export interface OTAInitOptions {
  /** Run one update check as soon as the SDK is configured. Default: true. */
  autoCheck?: boolean;
  /**
   * Call `notifyAppReady()` automatically once the app has spent
   * `stableAfterMs` in the foreground after the first configure. Default: true.
   */
  autoConfirm?: boolean;
  /** With autoConfirm: foreground time before confirming. Default: 5000ms. */
  stableAfterMs?: number;
  /** Called once with the bundle the running JS booted from. */
  onActiveBundle?: (bundle: ActiveBundleInfo) => void;
  /** Run just before `restartApp()` takes the process down. */
  onBeforeRestart?: () => void | Promise<void>;
  /** Ceiling on onBeforeRestart. Default: 2000ms. */
  beforeRestartTimeoutMs?: number;
}

// ── Store ───────────────────────────────────────────────────────────────

let snapshot: OTASnapshot = {
  status: 'idle',
  updateInfo: null,
  downloadProgress: 0,
  error: null,
  activeBundle: null,
  configured: false,
};

const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setSnapshot(patch: Partial<OTASnapshot>): void {
  snapshot = { ...snapshot, ...patch };
  emit();
}

/** Subscribe to store changes. Returns an unsubscribe function. */
export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Current store snapshot. Stable reference between changes (for useSyncExternalStore). */
export function getSnapshot(): OTASnapshot {
  return snapshot;
}

// ── Init / configure ────────────────────────────────────────────────────

// Guards that must survive component remounts and multiple hook instances.
let configuredSignature: string | null = null;
let subscriptionsReady = false;
let autoConfirmStarted = false;

// Latest options win for callbacks read at action time (restart, activeBundle).
let currentOptions: OTAInitOptions = {};

/**
 * Configure the SDK with a config fetched (possibly asynchronously) by the app.
 *
 * - A null/urlless config is a no-op: the store stays `idle` and makes no
 *   native calls — this is the "still fetching the OTA URL" state.
 * - The first config with a real `serverUrl` configures native, wires up the
 *   one-time subscriptions + auto-confirm timer, and (unless `autoCheck` is
 *   false) runs one update check.
 * - Calling it again with an identical config is a no-op; a changed config
 *   re-configures native (cheap) without repeating the one-time setup.
 */
export function configureOTA(
  config?: OTAConfig | null,
  options?: OTAInitOptions,
): void {
  if (options) currentOptions = { ...currentOptions, ...options };

  // Nothing to configure yet — the app hasn't fetched the URL.
  if (!config || !config.serverUrl) return;

  const signature = JSON.stringify(config);
  if (signature === configuredSignature) return; // already configured identically
  const isFirstConfigure = configuredSignature === null;
  configuredSignature = signature;

  nativeConfigure(config);
  if (!snapshot.configured) setSnapshot({ configured: true });

  if (!subscriptionsReady) {
    subscriptionsReady = true;
    setupSubscriptions();
    void readActiveBundle();
    startAutoConfirm();
  }

  if (isFirstConfigure && (currentOptions.autoCheck ?? true)) {
    void checkForUpdate();
  }
}

function setupSubscriptions(): void {
  onDownloadProgress(({ progress }) => setSnapshot({ downloadProgress: progress }));
  onOTAEvent((event) => {
    if (event.type === 'download_complete') setSnapshot({ status: 'ready_to_install' });
    if (event.type === 'download_failed') setSnapshot({ status: 'error', error: event.error });
    if (event.type === 'rollback') setSnapshot({ status: 'idle' });
  });
}

async function readActiveBundle(): Promise<void> {
  try {
    const s = await getStatus();
    const bundle: ActiveBundleInfo = {
      bundleId: s.activeBundleId || null,
      hash: s.activeBundleHash,
      path: s.activeBundlePath,
      isEmbedded: !s.activeBundlePath,
    };
    setSnapshot({ activeBundle: bundle });
    currentOptions.onActiveBundle?.(bundle);
  } catch (e) {
    // Best-effort telemetry — never let it break startup.
    if (__DEV__) console.warn('[OTA] could not read active bundle', e);
  }
}

function startAutoConfirm(): void {
  if (autoConfirmStarted) return;
  if (!(currentOptions.autoConfirm ?? true)) return;
  autoConfirmStarted = true;

  // Confirm once the app has spent stableAfterMs in the foreground. The clock
  // stops in the background: a bundle the user never actually used is not proven.
  let remaining = currentOptions.stableAfterMs ?? 5000;
  let startedAt = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const resume = () => {
    if (timer || remaining <= 0) return;
    startedAt = Date.now();
    timer = setTimeout(() => {
      timer = null;
      remaining = 0;
      notifyAppReady();
    }, remaining);
  };
  const pause = () => {
    if (!timer) return;
    clearTimeout(timer);
    timer = null;
    remaining -= Date.now() - startedAt;
  };
  AppState.addEventListener('change', (state) => {
    if (state === 'active') resume();
    else if (state === 'background') pause();
  });
  if (AppState.currentState !== 'background') resume();
}

// ── Actions ─────────────────────────────────────────────────────────────

export async function checkForUpdate(): Promise<void> {
  if (!snapshot.configured) {
    if (__DEV__) console.warn('[OTA] checkForUpdate() called before configure()');
    return;
  }
  setSnapshot({ status: 'checking', error: null });
  try {
    const result = await nativeCheckForUpdate();
    if (__DEV__) console.log('[OTA] check result', JSON.stringify(result));
    if (!result.updateAvailable) {
      setSnapshot({ status: 'up_to_date' });
      return;
    }
    setSnapshot({ updateInfo: result, status: 'update_available' });
    // Auto-download mandatory updates immediately.
    if (result.mandatory) await applyUpdate(result);
  } catch (e: any) {
    setSnapshot({ status: 'error', error: e?.message ?? 'Update check failed' });
  }
}

export async function applyUpdate(info?: UpdateInfo | null): Promise<void> {
  const target = info ?? snapshot.updateInfo;
  if (!target) return;

  setSnapshot({ status: 'downloading', downloadProgress: 0 });
  try {
    // Forward the delta patch fields and the ECDSA signature so native can
    // apply a patch instead of a full download, and reject tampered bundles.
    await downloadBundle(target.bundleId, target.downloadUrl, target.hash, {
      patchUrl: target.patchUrl,
      patchHash: target.patchHash,
      fromHash: target.fromHash,
      signature: target.signature,
    });
    await applyPendingBundle();
    setSnapshot({ status: 'ready_to_install' });
  } catch (e: any) {
    setSnapshot({ status: 'error', error: e?.message ?? 'Download failed' });
  }
}

// Guard so a double tap cannot start two teardowns.
let restarting = false;

export async function restart(): Promise<void> {
  if (restarting) return;
  if (snapshot.status !== 'ready_to_install') {
    // Restarting without a staged bundle reloads what is already running.
    // Failing loudly beats a button that looks like it did nothing.
    console.warn(
      `[OTA] restartApp() ignored — status is '${snapshot.status}', expected ` +
        `'ready_to_install'. Call applyUpdate() first and wait for it to resolve.`,
    );
    setSnapshot({
      status: 'error',
      error: 'Nothing staged to apply — call applyUpdate() first',
    });
    return;
  }
  restarting = true;
  try {
    const flush = currentOptions.onBeforeRestart;
    if (flush) {
      // Bounded: a flush that never settles must not become a dead button.
      await Promise.race([
        Promise.resolve(flush()).catch((e) => {
          console.warn('[OTA] onBeforeRestart threw, restarting anyway', e);
        }),
        new Promise((r) => setTimeout(r, currentOptions.beforeRestartTimeoutMs ?? 2000)),
      ]);
    }
    await nativeRestartApp();
  } catch (e: any) {
    restarting = false;
    setSnapshot({ status: 'error', error: e?.message ?? 'Restart failed' });
  }
}

/**
 * Test-only: reset the singleton to its initial state. Not exported from the
 * package entry — intended for unit tests that need a clean module per case.
 */
export function __resetForTests(): void {
  snapshot = {
    status: 'idle',
    updateInfo: null,
    downloadProgress: 0,
    error: null,
    activeBundle: null,
    configured: false,
  };
  listeners.clear();
  configuredSignature = null;
  subscriptionsReady = false;
  autoConfirmStarted = false;
  currentOptions = {};
  restarting = false;
}
