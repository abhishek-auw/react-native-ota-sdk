export interface OTAConfig {
  /** Your app ID from the OTA dashboard */
  appId: string;
  /** Backend API base URL e.g. https://ota.yourcompany.com */
  serverUrl: string;
  /** Deployment channel to subscribe to. Defaults to "production" */
  channel?: string;
  /** Native app version, used for semver targeting. Defaults to app version */
  appVersion?: string;
  /** How often to check for updates (ms). 0 = only on app start. Default: 0 */
  checkInterval?: number;
  /** Number of consecutive crashes before auto-rollback. Default: 3 */
  crashThreshold?: number;
}

export interface UpdateInfo {
  bundleId: string;
  downloadUrl: string;
  hash: string;
  mandatory: boolean;
  releaseNotes?: string;
}

export type UpdateStatus =
  | 'idle'
  | 'checking'
  | 'update_available'
  | 'downloading'
  | 'ready_to_apply'
  | 'applying'
  | 'up_to_date'
  | 'error';

export type OTAEventType =
  | 'checking'
  | 'update_available'
  | 'no_update'
  | 'download_progress'
  | 'download_complete'
  | 'install_success'
  | 'install_failed'
  | 'rollback';

export type OTAEventHandler<T = unknown> = (data: T) => void;
