/**
 * Unit tests for the singleton OTA store — the stateful core behind useOTA.
 * The native layer (./index) is mocked so we exercise only the store's
 * guards: deferred config, one-time configure, dedupe, and autoCheck.
 */

// Mock the native bridge. The store imports these from '../src/index', which
// otherwise calls TurboModuleRegistry.getEnforcing and throws under jest.
jest.mock('../src/index', () => ({
  configure: jest.fn(),
  checkForUpdate: jest.fn().mockResolvedValue({ updateAvailable: false }),
  downloadBundle: jest.fn().mockResolvedValue({ bundlePath: '', hash: '', delta: false }),
  applyPendingBundle: jest.fn().mockResolvedValue(''),
  getStatus: jest.fn().mockResolvedValue({
    activeBundleId: '',
    activeBundleHash: '',
    activeBundlePath: '',
    hasPending: false,
    pendingBundleHash: '',
    crashCount: 0,
    bundleConfirmed: true,
    runtimeVersion: '1',
  }),
  notifyAppReady: jest.fn(),
  onDownloadProgress: jest.fn(() => ({ remove: jest.fn() })),
  onOTAEvent: jest.fn(() => ({ remove: jest.fn() })),
  restartApp: jest.fn().mockResolvedValue(undefined),
}));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
(globalThis as any).__DEV__ = false;

import * as native from '../src/index';
import {
  configureOTA,
  getSnapshot,
  subscribe,
  __resetForTests,
} from '../src/otaStore';

const CONFIG = { appId: 'app-1', serverUrl: 'https://ota.example.com', channel: 'production' };

const flush = () => new Promise((r) => setImmediate(r));

beforeEach(() => {
  __resetForTests();
  jest.clearAllMocks();
});

describe('otaStore', () => {
  it('starts idle and unconfigured', () => {
    const s = getSnapshot();
    expect(s.status).toBe('idle');
    expect(s.configured).toBe(false);
  });

  it('is a no-op when config is null (URL not fetched yet)', () => {
    configureOTA(null);
    expect(native.configure).not.toHaveBeenCalled();
    expect(getSnapshot().configured).toBe(false);
  });

  it('is a no-op when config has no serverUrl', () => {
    // @ts-expect-error — intentionally missing serverUrl
    configureOTA({ appId: 'app-1' });
    expect(native.configure).not.toHaveBeenCalled();
    expect(getSnapshot().configured).toBe(false);
  });

  it('configures native once and flips `configured` true', () => {
    configureOTA(CONFIG);
    expect(native.configure).toHaveBeenCalledTimes(1);
    expect(native.configure).toHaveBeenCalledWith(CONFIG);
    expect(getSnapshot().configured).toBe(true);
  });

  it('runs an update check after the first configure (autoCheck default)', () => {
    configureOTA(CONFIG);
    expect(native.checkForUpdate).toHaveBeenCalledTimes(1);
  });

  it('does not auto-check when autoCheck is false', () => {
    configureOTA(CONFIG, { autoCheck: false });
    expect(native.configure).toHaveBeenCalledTimes(1);
    expect(native.checkForUpdate).not.toHaveBeenCalled();
  });

  it('dedupes an identical config (no re-configure)', () => {
    configureOTA(CONFIG);
    configureOTA({ ...CONFIG }); // fresh object, identical content
    expect(native.configure).toHaveBeenCalledTimes(1);
  });

  it('re-configures native when the config changes, without re-checking', () => {
    configureOTA(CONFIG, { autoCheck: false });
    configureOTA({ ...CONFIG, channel: 'beta' }, { autoCheck: false });
    expect(native.configure).toHaveBeenCalledTimes(2);
    // autoCheck only fires on the FIRST configure
    expect(native.checkForUpdate).not.toHaveBeenCalled();
  });

  it('settles to up_to_date when no update is available', async () => {
    configureOTA(CONFIG);
    await flush();
    expect(getSnapshot().status).toBe('up_to_date');
  });

  it('exposes update info when the server reports one', async () => {
    (native.checkForUpdate as jest.Mock).mockResolvedValueOnce({
      updateAvailable: true,
      bundleId: 'b1',
      downloadUrl: 'https://cdn/b1',
      hash: 'h1',
      mandatory: false,
    });
    configureOTA(CONFIG);
    await flush();
    const s = getSnapshot();
    expect(s.status).toBe('update_available');
    expect(s.updateInfo?.bundleId).toBe('b1');
  });

  it('notifies subscribers on state change', () => {
    const listener = jest.fn();
    const unsub = subscribe(listener);
    configureOTA(CONFIG, { autoCheck: false });
    expect(listener).toHaveBeenCalled();
    unsub();
  });

  it('coalesces a flood of download-progress events into few store updates', async () => {
    configureOTA(CONFIG, { autoCheck: false });
    await flush();

    // The handler the store registered with onDownloadProgress during configure.
    const progressHandler = (native.onDownloadProgress as jest.Mock).mock
      .calls[0][0];
    expect(typeof progressHandler).toBe('function');

    const listener = jest.fn();
    const unsub = subscribe(listener);

    // Simulate the native per-8KB flood: a tight synchronous burst of events —
    // exactly what tripped useSyncExternalStore. Without coalescing this would
    // notify subscribers ~50 times; leading+trailing throttle collapses a
    // synchronous burst to a single immediate emit.
    for (let i = 1; i <= 50; i++) {
      progressHandler({ bundleId: 'b1', progress: i / 50 });
    }

    expect(listener.mock.calls.length).toBeLessThanOrEqual(2);
    expect(getSnapshot().downloadProgress).toBeGreaterThan(0);

    unsub();
  });
});
