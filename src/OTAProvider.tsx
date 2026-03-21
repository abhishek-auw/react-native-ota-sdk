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
import { Alert } from 'react-native';

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

  // Configure native SDK once
  useEffect(() => {
    configure(config);

    // Subscribe to native events
    const progressSub = onDownloadProgress(({ progress: p }) => setProgress(p));
    const eventSub = onOTAEvent((event) => {
      if (event.type === 'download_complete') setStatus('ready_to_install');
      if (event.type === 'download_failed')  { setStatus('error'); setError(event.error); }
      if (event.type === 'rollback')         setStatus('idle');
    });

    // Mark stable after stableAfterMs
    const timer = setTimeout(() => markStable(), stableAfterMs);

    return () => {
      progressSub.remove();
      eventSub.remove();
      clearTimeout(timer);
    };
  }, []); // Only once

  // Auto-check on mount
  useEffect(() => {
    if (checkOnMount) doCheckForUpdate();
  }, []);

  const doCheckForUpdate = useCallback(async () => {
    setStatus('checking');
    setError(null);
    try {
      const result = await checkForUpdate();
      Alert.alert('[OTA] Check result:', JSON.stringify(result));
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
  }, []);

  const doApplyUpdate = useCallback(async (info?: UpdateInfo | null) => {
    const target = info ?? updateInfo;
    if (!target) return;

    setStatus('downloading');
    setProgress(0);
    try {
      await downloadBundle(target.bundleId, target.downloadUrl, target.hash);
      await applyPendingBundle();
      setStatus('ready_to_install');
    } catch (e: any) {
      setStatus('error');
      setError(e?.message ?? 'Download failed');
    }
  }, [updateInfo]);

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
