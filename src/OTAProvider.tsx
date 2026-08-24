/**
 * OTAProvider — optional React context wrapper.
 * Gives any component access to OTA state via useOTA().
 *
 * Usage:
 *   // index.js or App.tsx
 *   import { OTAProvider } from 'react-native-ota-sdk';
 *
 *   export default function App() {
 *     return (
 *       <OTAProvider
 *         config={{ appId: 'xxx', serverUrl: 'https://ota.company.com' }}
 *         checkOnMount
 *       >
 *         <RootNavigator />
 *       </OTAProvider>
 *     );
 *   }
 */
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  configure,
  checkForUpdate,
  downloadBundle,
  applyPendingBundle,
  getStatus,
  markStable,
  onDownloadProgress,
  onOTAEvent,
  restartApp,
  type ActiveBundleInfo,
  type OTAConfig,
  type UpdateInfo,
} from './index';

// ── Context ───────────────────────────────────────────────────────────

interface OTAState {
  status:
    | 'idle'
    | 'checking'
    | 'update_available'
    | 'downloading'
    | 'ready_to_install'
    | 'up_to_date'
    | 'error';
  updateInfo: UpdateInfo | null;
  downloadProgress: number;       // 0–1
  error: string | null;
  /**
   * Which bundle the running JS came from. null until the native layer has
   * answered (one tick after mount), and on platforms where the native module
   * is missing.
   */
  activeBundle: ActiveBundleInfo | null;
  checkForUpdate: () => Promise<void>;
  /** Download + apply. Leaves status at 'ready_to_install'. */
  applyUpdate: () => Promise<void>;
  /**
   * Restart now, loading the bundle that applyUpdate() staged.
   *
   * Only meaningful once status is 'ready_to_install'. Calling it earlier
   * reloads the bundle already running, which looks like the update failing.
   * Nothing after the await is guaranteed to run.
   */
  restartApp: () => Promise<void>;
}

const OTAContext = createContext<OTAState | null>(null);

// ── Provider ──────────────────────────────────────────────────────────

interface OTAProviderProps {
  config: OTAConfig;
  children: React.ReactNode;
  /** Auto-check for updates when component mounts. Default: true */
  checkOnMount?: boolean;
  /** Mark app as stable after this many ms. Default: 5000 */
  stableAfterMs?: number;
  /**
   * Called once on mount with the bundle the running JS was loaded from —
   * the OTA bundle id, or `isEmbedded: true` for the JS shipped in the binary.
   *
   * Use it to tag crash reports and analytics with the JS actually executing.
   *
   * It fires once per mount and deliberately does not fire again after an
   * update is applied: `applyPendingBundle()` only repoints the bundle for the
   * *next* launch, so re-reporting then would name JS that isn't running. On
   * the reload that follows, the provider remounts and this fires with the
   * new id.
   */
  onActiveBundle?: (bundle: ActiveBundleInfo) => void;
  /**
   * Run just before the process is killed by `restartApp()`.
   *
   * The restart is a real relaunch — `System.exit(0)` after starting the
   * launcher activity — so anything the app has buffered but not sent goes
   * with it. Firebase Analytics batches events on its own schedule and will
   * not have flushed; the same is true of any queue you maintain yourself.
   *
   * Awaited, then capped at `beforeRestartTimeoutMs` so a hung flush cannot
   * strand the user on a button that does nothing. A rejection is logged and
   * ignored: failing to flush analytics is not a reason to block an update.
   */
  onBeforeRestart?: () => void | Promise<void>;
  /** Ceiling on onBeforeRestart. Default: 2000ms. */
  beforeRestartTimeoutMs?: number;
}

export function OTAProvider({
  config,
  children,
  checkOnMount = true,
  stableAfterMs = 5000,
  onActiveBundle,
  onBeforeRestart,
  beforeRestartTimeoutMs = 2000,
}: OTAProviderProps) {
  const [status, setStatus]     = useState<OTAState['status']>('idle');
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null);
  const [progress, setProgress] = useState(0);
  const [error, setError]       = useState<string | null>(null);
  const [activeBundle, setActiveBundle] = useState<ActiveBundleInfo | null>(null);

  // Hold the config in a ref so the mount effect can read it without
  // re-running when the caller passes a fresh object literal each render.
  const configRef = useRef(config);
  configRef.current = config;

  const stableAfterMsRef = useRef(stableAfterMs);
  stableAfterMsRef.current = stableAfterMs;

  const onActiveBundleRef = useRef(onActiveBundle);
  onActiveBundleRef.current = onActiveBundle;

  const onBeforeRestartRef = useRef(onBeforeRestart);
  onBeforeRestartRef.current = onBeforeRestart;

  const beforeRestartTimeoutMsRef = useRef(beforeRestartTimeoutMs);
  beforeRestartTimeoutMsRef.current = beforeRestartTimeoutMs;

  // Configure native SDK once
  useEffect(() => {
    configure(configRef.current);

    // Ask native which bundle this session booted from. Read here, before any
    // download or apply, so the answer describes the JS that is executing.
    let disposed = false;
    (async () => {
      try {
        const s = await getStatus();
        if (disposed) return;
        const bundle: ActiveBundleInfo = {
          bundleId:   s.activeBundleId || null,
          hash:       s.activeBundleHash,
          path:       s.activeBundlePath,
          isEmbedded: !s.activeBundlePath,
        };
        setActiveBundle(bundle);
        onActiveBundleRef.current?.(bundle);
      } catch (e) {
        // Best-effort telemetry — never let it break app startup.
        if (__DEV__) console.warn('[OTA] could not read active bundle', e);
      }
    })();

    // Subscribe to native events
    const progressSub = onDownloadProgress(({ progress: p }) => setProgress(p));
    const eventSub = onOTAEvent((event) => {
      if (event.type === 'download_complete') setStatus('ready_to_install');
      if (event.type === 'download_failed')  { setStatus('error'); setError(event.error); }
      if (event.type === 'rollback')         setStatus('idle');
    });

    // Mark stable after stableAfterMs
    const timer = setTimeout(() => markStable(), stableAfterMsRef.current);

    return () => {
      disposed = true;
      progressSub.remove();
      eventSub.remove();
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Only once

  const doApplyUpdate = useCallback(async (info?: UpdateInfo | null) => {
    const target = info ?? updateInfo;
    if (!target) return;

    setStatus('downloading');
    setProgress(0);
    try {
      // Forward the delta patch fields and the ECDSA signature so native can
      // apply a patch instead of a full download, and reject tampered bundles.
      await downloadBundle(target.bundleId, target.downloadUrl, target.hash, {
        patchUrl:  target.patchUrl,
        patchHash: target.patchHash,
        fromHash:  target.fromHash,
        signature: target.signature,
      });
      await applyPendingBundle();
      setStatus('ready_to_install');
    } catch (e: any) {
      setStatus('error');
      setError(e?.message ?? 'Download failed');
    }
  }, [updateInfo]);

  /**
   * Guarded so a double tap cannot start two teardowns. The first call takes
   * the process down mid-flight, so the second lands in an instance that is
   * already disappearing — which surfaces as an unhandled rejection rather
   * than anything useful.
   */
  const restartingRef = useRef(false);
  const doRestart = useCallback(async () => {
    if (restartingRef.current) return;
    if (status !== 'ready_to_install') {
      // Restarting without a staged bundle reloads what is already running.
      // Failing loudly here beats a button that looks like it did nothing.
      //
      // Warn as well as setting state: callers who only render the happy path
      // see a dead button and no error, which is the worst of both.
      console.warn(
        `[OTA] restartApp() ignored — status is '${status}', expected ` +
          `'ready_to_install'. Call applyUpdate() first and wait for it to resolve.`,
      );
      setError('Nothing staged to apply — call applyUpdate() first');
      setStatus('error');
      return;
    }
    restartingRef.current = true;
    try {
      const flush = onBeforeRestartRef.current;
      if (flush) {
        // Bounded: a flush that never settles must not become a dead button.
        await Promise.race([
          Promise.resolve(flush()).catch((e) => {
            console.warn('[OTA] onBeforeRestart threw, restarting anyway', e);
          }),
          new Promise((r) => setTimeout(r, beforeRestartTimeoutMsRef.current)),
        ]);
      }
      await restartApp();
    } catch (e: any) {
      restartingRef.current = false;
      setStatus('error');
      setError(e?.message ?? 'Restart failed');
    }
  }, [status]);

  const doCheckForUpdate = useCallback(async () => {
    setStatus('checking');
    setError(null);
    try {
      const result = await checkForUpdate();
      if (__DEV__) {
        console.log('[OTA] check result', JSON.stringify(result));
      }
      if (!result.updateAvailable) {
        setStatus('up_to_date');
        return;
      }
      setUpdateInfo(result);
      setStatus('update_available');

      // Auto-download mandatory updates immediately
      if (result.mandatory) {
        await doApplyUpdate(result);
      }
    } catch (e: any) {
      setStatus('error');
      setError(e?.message ?? 'Update check failed');
    }
  }, [doApplyUpdate]);

  // Auto-check on mount
  const checkOnMountRef = useRef(checkOnMount);
  useEffect(() => {
    if (checkOnMountRef.current) doCheckForUpdate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <OTAContext.Provider
      value={{
        status,
        updateInfo,
        downloadProgress: progress,
        error,
        activeBundle,
        checkForUpdate:  doCheckForUpdate,
        applyUpdate:     () => doApplyUpdate(),
        restartApp:      doRestart,
      }}
    >
      {children}
    </OTAContext.Provider>
  );
}

// ── Hook ──────────────────────────────────────────────────────────────

export function useOTA(): OTAState {
  const ctx = useContext(OTAContext);
  if (!ctx) throw new Error('useOTA must be used inside <OTAProvider>');
  return ctx;
}
