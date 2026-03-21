/**
 * SDK unit tests using mock fetch and mock NativeModules.
 */
jest.mock('react-native', () => ({
  NativeModules: {
    OTANativeModule: {
      configure: jest.fn(async () => {}),
      downloadBundle: jest.fn(async () => {}),
      applyBundle: jest.fn(async () => {}),
      getDeviceHash: jest.fn(async () => 'mock-device-hash-12345678'),
      getAppVersion: jest.fn(async () => '1.0.0'),
      getCurrentBundleHash: jest.fn(async () => ''),
    },
  },
  Platform: { OS: 'ios' },
}));

import { OTAService } from '../src/OTAService';

const SERVER_URL = 'http://localhost:3000';

const mockFetch = (response: object, ok = true) => {
  global.fetch = jest.fn().mockResolvedValue({
    ok,
    status: ok ? 200 : 500,
    json: async () => response,
  }) as jest.Mock;
};

describe('OTAService.checkForUpdate', () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    await OTAService.configure({ appId: 'app-test-id', serverUrl: SERVER_URL });
  });

  it('returns null when no update is available', async () => {
    mockFetch({ updateAvailable: false });
    const result = await OTAService.checkForUpdate();
    expect(result).toBeNull();
  });

  it('returns UpdateInfo when update is available', async () => {
    mockFetch({
      updateAvailable: true,
      bundleId: 'bundle-123',
      downloadUrl: 'https://cdn.example.com/bundle.zip',
      hash: 'abc123hash',
      mandatory: false,
      releaseNotes: 'Bug fixes',
    });

    const result = await OTAService.checkForUpdate();
    expect(result).not.toBeNull();
    expect(result?.bundleId).toBe('bundle-123');
    expect(result?.hash).toBe('abc123hash');
  });

  it('sends correct payload to /v1/update/check', async () => {
    mockFetch({ updateAvailable: false });
    await OTAService.checkForUpdate();

    const call = (global.fetch as jest.Mock).mock.calls[0];
    expect(call[0]).toContain('/v1/update/check');
    const body = JSON.parse(call[1].body);
    expect(body.appId).toBe('app-test-id');
    expect(body.platform).toBe('ios');
    expect(body.deviceHash).toBe('mock-device-hash-12345678');
  });

  it('returns null and does not throw on network error', async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error('Network error')) as jest.Mock;
    const result = await OTAService.checkForUpdate();
    expect(result).toBeNull();
  });

  it('triggers install_failed event on error', async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error('Timeout')) as jest.Mock;
    const handler = jest.fn();
    OTAService.on('install_failed', handler);
    await OTAService.checkForUpdate();
    expect(handler).toHaveBeenCalled();
    OTAService.off('install_failed', handler);
  });
});

describe('OTAService event system', () => {
  it('emits update_available event when update exists', async () => {
    await OTAService.configure({ appId: 'app-id', serverUrl: SERVER_URL });
    mockFetch({
      updateAvailable: true,
      bundleId: 'b-1',
      downloadUrl: 'https://cdn.example.com/b.zip',
      hash: 'somehash',
      mandatory: false,
    });

    const handler = jest.fn();
    OTAService.on('update_available', handler);
    await OTAService.checkForUpdate();
    expect(handler).toHaveBeenCalledWith(
      expect.objectContaining({ bundleId: 'b-1' }),
    );
    OTAService.off('update_available', handler);
  });

  it('emits no_update when no update available', async () => {
    await OTAService.configure({ appId: 'app-id', serverUrl: SERVER_URL });
    mockFetch({ updateAvailable: false });

    const handler = jest.fn();
    OTAService.on('no_update', handler);
    await OTAService.checkForUpdate();
    expect(handler).toHaveBeenCalled();
    OTAService.off('no_update', handler);
  });
});
