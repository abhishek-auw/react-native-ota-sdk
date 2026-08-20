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
  markStable,
  onDownloadProgress,
  onOTAEvent,
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
  checkForUpdate: () => Promise<void>;
  applyUpdate: () => Promise<void>;
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
}

export function OTAProvider({
  config,
  children,
  checkOnMount = true,
  stableAfterMs = 5000,
}: OTAProviderProps) {
  const [status, setStatus]     = useState<OTAState['status']>('idle');
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null);
  const [progress, setProgress] = useState(0);
  const [error, setError]       = useState<string | null>(null);

  // Hold the config in a ref so the mount effect can read it without
  // re-running when the caller passes a fresh object literal each render.
  const configRef = useRef(config);
  configRef.current = config;

  const stableAfterMsRef = useRef(stableAfterMs);
  stableAfterMsRef.current = stableAfterMs;

  // Configure native SDK once
  useEffect(() => {
    configure(configRef.current);

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
        checkForUpdate:  doCheckForUpdate,
        applyUpdate:     () => doApplyUpdate(),
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
