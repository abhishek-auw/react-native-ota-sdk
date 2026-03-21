import { NativeModules, Platform } from 'react-native';
import type {
  OTAConfig,
  UpdateInfo,
  UpdateStatus,
  OTAEventType,
  OTAEventHandler,
} from './types.js';

const { OTANativeModule } = NativeModules;

/**
 * Main OTA service class.
 *
 * Usage:
 *   await OTAService.configure({ appId: '...', serverUrl: '...' });
 *   await OTAService.checkForUpdate();
 */
export class OTAService {
  private static config: OTAConfig | null = null;
  private static status: UpdateStatus = 'idle';
  private static listeners: Map<OTAEventType, OTAEventHandler[]> = new Map();
  private static checkTimer: ReturnType<typeof setInterval> | null = null;

  // ── Configuration ────────────────────────────────────────────────

  static async configure(config: OTAConfig): Promise<void> {
    OTAService.config = {
      channel: 'production',
      checkInterval: 0,
      crashThreshold: 3,
      ...config,
    };

    // Pass config to native module for persistence
    if (OTANativeModule?.configure) {
      await OTANativeModule.configure({
        appId: config.appId,
        serverUrl: config.serverUrl,
        crashThreshold: OTAService.config.crashThreshold,
      });
    }

    // Start periodic check if interval > 0
    if (OTAService.config.checkInterval && OTAService.config.checkInterval > 0) {
      OTAService.startPeriodicCheck();
    }
  }

  // ── Update Check ─────────────────────────────────────────────────

  static async checkForUpdate(): Promise<UpdateInfo | null> {
    if (!OTAService.config) {
      throw new Error('OTAService not configured. Call configure() first.');
    }

    OTAService.setStatus('checking');
    OTAService.emit('checking', {});

    try {
      const deviceHash = await OTAService.getDeviceHash();
      const appVersion = OTAService.config.appVersion ?? await OTAService.getAppVersion();
      const currentBundleHash = await OTAService.getCurrentBundleHash();

      const response = await fetch(
        `${OTAService.config.serverUrl}/v1/update/check`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            appId: OTAService.config.appId,
            platform: Platform.OS as 'ios' | 'android',
            appVersion,
            currentBundleHash,
            channel: OTAService.config.channel,
            deviceHash,
          }),
        },
      );

      if (!response.ok) {
        throw new Error(`Update check failed: ${response.status}`);
      }

      const data = await response.json();

      if (!data.updateAvailable) {
        OTAService.setStatus('up_to_date');
        OTAService.emit('no_update', {});
        return null;
      }

      const updateInfo: UpdateInfo = {
        bundleId: data.bundleId,
        downloadUrl: data.downloadUrl,
        hash: data.hash,
        mandatory: data.mandatory ?? false,
        releaseNotes: data.releaseNotes,
      };

      OTAService.setStatus('update_available');
      OTAService.emit('update_available', updateInfo);

      // Auto-download and apply mandatory updates
      if (updateInfo.mandatory) {
        await OTAService.downloadAndApply(updateInfo);
      }

      return updateInfo;
    } catch (err) {
      OTAService.setStatus('error');
      OTAService.emit('install_failed', { error: err });
      return null;
    }
  }

  // ── Download + Apply ─────────────────────────────────────────────

  static async downloadAndApply(updateInfo: UpdateInfo): Promise<void> {
    OTAService.setStatus('downloading');
    try {
      await OTANativeModule.downloadBundle({
        bundleId: updateInfo.bundleId,
        downloadUrl: updateInfo.downloadUrl,
        expectedHash: updateInfo.hash,
        onProgress: (progress: number) => {
          OTAService.emit('download_progress', { progress });
        },
      });

      OTAService.setStatus('ready_to_apply');
      OTAService.emit('download_complete', { bundleId: updateInfo.bundleId });
      await OTAService.reportEvent('download', updateInfo.bundleId);
    } catch (err) {
      OTAService.setStatus('error');
      OTAService.emit('install_failed', { error: err });
      throw err;
    }
  }

  static async applyUpdate(): Promise<void> {
    OTAService.setStatus('applying');
    await OTANativeModule.applyBundle();
    // Native side will restart the JS engine
  }

  // ── Analytics Reporting ──────────────────────────────────────────

  static async reportEvent(
    eventType: 'check' | 'download' | 'install_ok' | 'crash',
    bundleId?: string,
  ): Promise<void> {
    if (!OTAService.config) return;
    try {
      const deviceHash = await OTAService.getDeviceHash();
      await fetch(`${OTAService.config.serverUrl}/v1/analytics/event`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          appId: OTAService.config.appId,
          bundleId,
          deviceHash,
          eventType,
          platform: Platform.OS,
        }),
      });
    } catch {
      // Analytics failures are non-fatal
    }
  }

  // ── Events ───────────────────────────────────────────────────────

  static on<T = unknown>(event: OTAEventType, handler: OTAEventHandler<T>): void {
    if (!OTAService.listeners.has(event)) {
      OTAService.listeners.set(event, []);
    }
    OTAService.listeners.get(event)!.push(handler as OTAEventHandler);
  }

  static off(event: OTAEventType, handler: OTAEventHandler): void {
    const handlers = OTAService.listeners.get(event) ?? [];
    OTAService.listeners.set(event, handlers.filter((h) => h !== handler));
  }

  static getStatus(): UpdateStatus {
    return OTAService.status;
  }

  // ── Internals ────────────────────────────────────────────────────

  private static setStatus(status: UpdateStatus) {
    OTAService.status = status;
  }

  private static emit<T>(event: OTAEventType, data: T) {
    (OTAService.listeners.get(event) ?? []).forEach((h) => h(data));
  }

  private static startPeriodicCheck() {
    if (OTAService.checkTimer) clearInterval(OTAService.checkTimer);
    OTAService.checkTimer = setInterval(
      () => OTAService.checkForUpdate(),
      OTAService.config!.checkInterval,
    );
  }

  private static async getDeviceHash(): Promise<string> {
    if (OTANativeModule?.getDeviceHash) {
      return OTANativeModule.getDeviceHash();
    }
    // Fallback for testing
    return 'dev-device-hash-00000000';
  }

  private static async getAppVersion(): Promise<string> {
    if (OTANativeModule?.getAppVersion) {
      return OTANativeModule.getAppVersion();
    }
    return '1.0.0';
  }

  private static async getCurrentBundleHash(): Promise<string> {
    if (OTANativeModule?.getCurrentBundleHash) {
      return OTANativeModule.getCurrentBundleHash();
    }
    return '';
  }
}
