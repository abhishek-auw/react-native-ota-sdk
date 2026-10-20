/**
 * useOTA — standalone hook for OTA updates, plus the optional OTAProvider.
 *
 * `useOTA(config)` configures the SDK and returns live OTA state and actions,
 * backed by a process-wide singleton store (./otaStore). It needs no provider
 * and can be called in any number of components — they all share one configure,
 * one update check, and one set of native subscriptions.
 *
 * Config can arrive asynchronously: pass `null` until you've fetched the OTA
 * server URL from your backend, then pass the real config. While unconfigured
 * the hook stays idle and makes no native calls.
 *
 * `OTAProvider` remains as a thin, backward-compatible wrapper — it just calls
 * `useOTA` and renders its children.
 *
 * Usage (no provider, deferred config):
 *   function App() {
 *     const [otaConfig, setOtaConfig] = useState(null);
 *     useEffect(() => {
 *       fetchBootstrap().then((cfg) =>
 *         setOtaConfig({ appId: cfg.appId, serverUrl: cfg.ota_url, channel: cfg.ota_channel }),
 *       );
 *     }, []);
 *     const ota = useOTA(otaConfig); // idle until otaConfig is set
 *     // ota.status, ota.updateInfo, ota.checkForUpdate(), ota.applyUpdate(), ota.restartApp()
 *   }
 *
 * Usage (imperative configure):
 *   const ota = useOTA();
 *   // later, inside your fetch:
 *   ota.configure({ appId, serverUrl, channel });
 *
 * Usage (provider, unchanged):
 *   <OTAProvider config={{ appId, serverUrl }} checkOnMount>
 *     <RootNavigator />
 *   </OTAProvider>
 */
import React, { useEffect, useSyncExternalStore } from 'react';
import {
  subscribe,
  getSnapshot,
  configureOTA,
  checkForUpdate,
  applyUpdate,
  restart,
} from './otaStore';
import type { OTASnapshot, OTAInitOptions } from './otaStore';
import type { OTAConfig, ActiveBundleInfo } from './index';

export type { OTAInitOptions, OTASnapshot, OTAStatus } from './otaStore';

export interface OTAState extends OTASnapshot {
  /** Check the server for an update now. */
  checkForUpdate: () => Promise<void>;
  /** Download + apply the available update. Leaves status at 'ready_to_install'. */
  applyUpdate: () => Promise<void>;
  /** Restart to load the staged bundle. Only valid once status is 'ready_to_install'. */
  restartApp: () => Promise<void>;
  /**
   * Imperatively configure the SDK with the config you fetched from your
   * backend. Equivalent to passing it as the first arg to `useOTA`, but handy
   * when you'd rather call it from inside your fetch's `.then()` than thread it
   * through state. Idempotent — deduped by content.
   */
  configure: (config: OTAConfig, options?: OTAInitOptions) => void;
}

/**
 * Subscribe to OTA state and (optionally) configure the SDK.
 *
 * Pass `config` once you have it; pass `null`/`undefined` until then (e.g. while
 * you fetch the OTA URL from your backend). Safe to call in multiple components
 * — state is a shared singleton, so configure/check/subscriptions run once.
 */
export function useOTA(
  config?: OTAConfig | null,
  options?: OTAInitOptions,
): OTAState {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  // Reactive configure: runs when a config with a real serverUrl first appears,
  // or when its contents change. The store dedupes identical configs, so a fresh
  // object literal with the same values each render does not re-configure.
  useEffect(() => {
    if (config && config.serverUrl) configureOTA(config, options);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config ? JSON.stringify(config) : null]);

  return {
    ...snapshot,
    checkForUpdate,
    applyUpdate,
    restartApp: restart,
    configure: configureOTA,
  };
}

// ── OTAProvider (optional, backward-compatible wrapper) ──────────────────

export interface OTAProviderProps {
  config: OTAConfig;
  children: React.ReactNode;
  /** Auto-check for updates once configured. Default: true. */
  checkOnMount?: boolean;
  /** Auto-confirm the bundle after `stableAfterMs` in the foreground. Default: true. */
  autoConfirm?: boolean;
  /** Foreground time before auto-confirm. Default: 5000ms. */
  stableAfterMs?: number;
  /** Called once with the bundle the running JS booted from. */
  onActiveBundle?: (bundle: ActiveBundleInfo) => void;
  /** Run just before `restartApp()` takes the process down. */
  onBeforeRestart?: () => void | Promise<void>;
  /** Ceiling on `onBeforeRestart`. Default: 2000ms. */
  beforeRestartTimeoutMs?: number;
}

/**
 * Optional wrapper, kept for backward compatibility. With the singleton store a
 * provider is no longer required — `useOTA` works anywhere — but this still lets
 * you configure the SDK declaratively at the top of your tree.
 */
export function OTAProvider({
  config,
  children,
  checkOnMount = true,
  autoConfirm = true,
  stableAfterMs = 5000,
  onActiveBundle,
  onBeforeRestart,
  beforeRestartTimeoutMs = 2000,
}: OTAProviderProps) {
  useOTA(config, {
    autoCheck: checkOnMount,
    autoConfirm,
    stableAfterMs,
    onActiveBundle,
    onBeforeRestart,
    beforeRestartTimeoutMs,
  });
  return <>{children}</>;
}
