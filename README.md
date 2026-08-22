# react-native-ota-sdk

Over-the-air updates for React Native. Ship JavaScript and asset changes to your
users without going through App Store or Play Store review.

Pairs with [ota-service](https://github.com/abhishek-auw/ota-service), the
self-hosted server that stores bundles, computes delta patches and answers
update checks.

---

## What it does

- **Checks for updates on app launch** and downloads them in the background
- **Delta patching** — downloads only the bytes that changed, not the whole bundle
- **SHA-256 verification** of every bundle and every file inside a patch
- **ECDSA P-256 signature verification**, so a compromised bucket cannot push code
- **Crash guard** — a bundle that crashes the app repeatedly is discarded automatically
- **Manual rollback** from JS at any time

All the heavy work — HTTP, file I/O, hashing, patching, crash detection — runs in
Kotlin and Swift. The JavaScript layer is a thin bridge.

---

## Requirements

| | |
|---|---|
| React Native | 0.83 (built and tested against this) |
| Android | minSdk 24 |
| iOS | React Native's minimum supported version |
| Architecture | New Architecture (TurboModules) and the old bridge both work |

---

## Installation

Not yet published to npm. Install from GitHub:

```sh
npm install github:abhishek-auw/react-native-ota-sdk
# or
yarn add github:abhishek-auw/react-native-ota-sdk
```

Then, for iOS:

```sh
cd ios && pod install
```

The pod pulls in [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) (~> 0.9)
to extract downloaded bundles. Android needs no extra dependencies — rebuild the
app after installing.

Autolinking handles registration on both platforms. You do **not** need to add
the package manually to `MainApplication` or `Podfile`.

---

## Native setup

**This step is required.** Without it the SDK will download and store bundles
correctly, and React Native will keep loading the bundle compiled into your app —
so updates appear to do nothing.

React Native decides which JS bundle to load before any JavaScript runs, so the
SDK cannot do this from JS. You have to point your app's bundle resolution at the
OTA bundle.

### Android — `MainApplication.kt`

**Which of the two below you need depends on your architecture.** Getting this
wrong is silent: updates download, verify and apply correctly, and React Native
carries on running the bundle from your APK.

#### New Architecture (bridgeless) — the default since React Native 0.76

If your `MainApplication` calls `loadReactNative(this)` and overrides
`reactHost`, this is you. In bridgeless mode React Native loads JS through
`ReactHost`, and **`reactNativeHost` is never consulted** — a `getJSBundleFile()`
override there is dead code. Pass the path to `getDefaultReactHost` instead:

```kotlin
import com.facebook.react.ReactHost
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.otasdk.OtaSdkHelper

class MainApplication : Application(), ReactApplication {
  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList = PackageList(this).packages,
      jsBundleFilePath = OtaSdkHelper.getJSBundleFile(applicationContext),
    )
  }
}
```

`getJSBundleFile` returns null when there is no OTA bundle yet, and
`getDefaultReactHost` then falls back to `assets://index.android.bundle`. It
also returns null on debuggable builds so Metro keeps working — it reads
`FLAG_DEBUGGABLE` from your app, so you do not need a `BuildConfig.DEBUG` check
of your own. To exercise OTA in a debuggable build, pass
`enableInDebuggableBuilds = true` and make sure Metro is not running.

> **Keep the call inside the `by lazy` block.** Hoisting it into a property —
> `val otaPath = OtaSdkHelper.getJSBundleFile(applicationContext)` — makes it run
> in the `Application` constructor, where `applicationContext` is still null
> because `attachBaseContext()` has not been called yet. The app then dies at
> startup with `NullPointerException ... getApplicationContext()` on a null
> reference. `by lazy` defers evaluation until first access, which is safely
> after the context exists.

#### Old Architecture (bridge)

If you have no `reactHost` override and rely on `reactNativeHost`:

```kotlin
import com.otasdk.OtaSdkHelper

override val reactNativeHost: ReactNativeHost =
  object : DefaultReactNativeHost(this) {

    // ── Add this ──────────────────────────────────────────────
    override fun getJSBundleFile(): String? =
      OtaSdkHelper.getJSBundleFile(applicationContext)
    // ──────────────────────────────────────────────────────────

    override fun getPackages(): List<ReactPackage> = PackageList(this).packages
    override fun getJSMainModuleName(): String = "index"
    override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG
  }
```

#### Declare a runtime version

**Also required.** The runtime version is the JS-to-native compatibility token.
The server serves a bundle only to binaries declaring exactly the same value, so
JavaScript built against a native contract this binary does not have can never
reach it.

`android/app/src/main/AndroidManifest.xml`, inside `<application>`:

```xml
<meta-data android:name="com.otasdk.RUNTIME_VERSION" android:value="1" />
```

Start at `1` and leave it alone. **Bump it only when the JS-to-native contract
breaks** — a native module added or removed, a bridge signature changed, a React
Native upgrade. A native release with no bridge changes keeps the same value and
inherits every bundle already published for it.

It is read from the manifest rather than passed to `configure()` on purpose. If
the token lived in JavaScript, an OTA update could raise its own runtime and
pull in bundles built for a native contract the installed binary lacks — which
is the exact failure the token exists to prevent.

#### Verifying the hook runs

`getJSBundleFile` logs on every call, so you can confirm it without adding
anything:

```bash
adb logcat -s OTA-Host:I
```

| Output | Meaning |
|---|---|
| `loading OTA bundle: /data/...` | Working — React Native is loading your update |
| `no OTA bundle yet — using the packaged bundle` | Hook runs, nothing downloaded and applied yet |
| `debuggable build — ... OTA skipped` | Expected on debug builds; use a release build |
| *nothing at all* | The hook is never called — most often `reactNativeHost` wired up in a bridgeless app |

### iOS — `AppDelegate.swift`

On React Native 0.74 and later, `AppDelegate` subclasses `RCTAppDelegate` and
you override `bundleURL()`:

```swift
import OtaSdk

override func bundleURL() -> URL? {
  #if DEBUG
    return RCTBundleURLProvider.sharedSettings()
      .jsBundleURL(forBundleRoot: "index")
  #else
    return OtaSdkHelper.activeBundleURL()
      ?? Bundle.main.url(forResource: "main", withExtension: "jsbundle")
  #endif
}
```

On older versions the equivalent hook is `sourceURL(for bridge:)` on your
`RCTBridgeDelegate`, with the same body.

Declare the runtime version in `Info.plist`, matching the Android value:

```xml
<key>OTARuntimeVersion</key>
<string>1</string>
```

Both helpers also promote a pending bundle to active, which is what makes an
update downloaded during one session take effect on the next launch.

---

## Usage

### Option A — `OTAProvider` (recommended)

Wrap your app. The provider configures the SDK, checks for updates on mount,
subscribes to native events, downloads mandatory updates automatically, and marks
the bundle stable after 5 seconds.

```tsx
import { OTAProvider } from 'react-native-ota-sdk';

export default function App() {
  return (
    <OTAProvider
      config={{
        appId: 'your-app-uuid',
        serverUrl: 'https://ota.yourcompany.com',
        channel: 'production',
      }}
    >
      <RootNavigator />
    </OTAProvider>
  );
}
```

**Props**

| Prop | Type | Default | Notes |
|---|---|---|---|
| `config` | `OTAConfig` | — | Required. See [Configuration](#configuration) |
| `checkOnMount` | `boolean` | `true` | Check for an update as soon as the provider mounts |
| `stableAfterMs` | `number` | `5000` | Call `markStable()` after this long without a crash |

Then read state anywhere below it with `useOTA()`:

```tsx
import { useOTA } from 'react-native-ota-sdk';

function UpdateBanner() {
  const { status, updateInfo, downloadProgress, error, applyUpdate } = useOTA();

  if (status === 'update_available') {
    return (
      <Banner
        text={updateInfo?.releaseNotes ?? 'An update is available'}
        onPress={applyUpdate}
      />
    );
  }

  if (status === 'downloading') {
    return <ProgressBar value={downloadProgress} />;
  }

  if (status === 'ready_to_install') {
    return <Banner text="Update ready — restart to apply" />;
  }

  if (status === 'error') {
    return <Banner text={error ?? 'Update failed'} />;
  }

  return null;
}
```

`status` is one of `idle`, `checking`, `update_available`, `downloading`,
`ready_to_install`, `up_to_date`, `error`.

### Option B — imperative

If you want to control when checks happen — on resume, on a timer, behind a
settings toggle — skip the provider and call the functions directly.

```ts
import {
  configure,
  checkAndApply,
  markStable,
} from 'react-native-ota-sdk';

// Once, at startup
configure({
  appId: 'your-app-uuid',
  serverUrl: 'https://ota.yourcompany.com',
  channel: 'production',
});

// After your app has rendered successfully
setTimeout(markStable, 5000);

// Whenever you want to check
const result = await checkAndApply({
  onProgress: (p) => console.log(`${Math.round(p * 100)}%`),
});

if (result.updateAvailable) {
  console.log('Update staged — will apply on next launch');
}
```

`checkAndApply()` does the whole flow: check, download (using a delta patch when
one is available), verify, and stage. It does not restart your app.

---

## Configuration

```ts
interface OTAConfig {
  appId: string;              // UUID from the OTA dashboard
  serverUrl: string;          // e.g. https://ota.yourcompany.com
  channel?: string;           // default: 'production'
  crashThreshold?: number;    // default: 3
  signingPublicKey?: string;  // PEM SPKI, ECDSA P-256
}
```

**`channel`** selects a release track. Point internal builds at `staging` or
`beta` to get changes before production users do.

**`crashThreshold`** is how many launches may crash before the SDK discards the
active bundle. See [Crash guard](#crash-guard).

**`signingPublicKey`**, when set, makes the SDK reject any bundle that is not
signed by the matching private key — including unsigned ones. Generate the pair
in the dashboard under **Apps → Signing Key**, embed the public half here, and
store the private half as `OTA_SIGNING_KEY` in CI so the CLI can sign on upload.

Leaving it unset means bundles are accepted on hash alone. Set it for production.

---

## API

### `configure(config: OTAConfig): void`

Initialises the native module and runs the crash-guard check for this launch.
Call once, before anything else.

### `checkForUpdate(): Promise<UpdateInfo | NoUpdate>`

Asks the server whether a newer bundle applies to this device, given its channel,
platform, app version and current bundle hash. Downloads nothing.

```ts
interface UpdateInfo {
  updateAvailable: true;
  bundleId: string;
  downloadUrl: string;
  hash: string;
  mandatory: boolean;
  releaseNotes?: string;
  signature?: string;   // present when the bundle was signed
  patchUrl?: string;    // present when a delta applies to this device
  patchHash?: string;
  fromHash?: string;
}
```

### `downloadBundle(bundleId, downloadUrl, expectedHash, options?)`

Downloads and verifies a bundle. Pass the `patchUrl` / `patchHash` / `fromHash` /
`signature` fields straight through from `checkForUpdate()` — with them, the SDK
downloads the patch instead of the full bundle.

Resolves to `{ bundlePath, hash, delta }`, where `delta` tells you whether a patch
was used.

### `applyPendingBundle(): Promise<string>`

Marks the downloaded bundle as the one to load next. **Takes effect on the next
app launch**, not immediately — a JS bundle can only be swapped when the runtime
restarts.

### `checkAndApply(options?): Promise<UpdateInfo | NoUpdate>`

Convenience wrapper: check → download → apply. Accepts
`{ onProgress?: (progress: number) => void }`.

### `getStatus(): Promise<SDKStatus>`

```ts
interface SDKStatus {
  activeBundleHash: string;
  activeBundlePath: string;
  hasPending: boolean;
  pendingBundleHash: string;
  crashCount: number;
}
```

Useful on a debug screen when you need to know what a device is actually running.

### `markStable(): void`

Resets the crash counter, marking the current bundle as good. Call it once your
app has rendered and reached a usable state. `OTAProvider` does this for you.

Forgetting to call it means every launch counts as a crash, and a perfectly good
bundle gets rolled back on the third one.

### `rollback(): Promise<void>`

Clears all OTA state, so the app loads the bundle compiled into the binary on its
next launch.

### Events

```ts
import { onDownloadProgress, onOTAEvent } from 'react-native-ota-sdk';

const p = onDownloadProgress(({ bundleId, progress }) => { /* 0–1 */ });

const e = onOTAEvent((event) => {
  switch (event.type) {
    case 'download_started':  break;
    case 'download_complete': break;  // { bundlePath, delta }
    case 'download_failed':   break;  // { error }
    case 'bundle_applied':    break;  // { path }
    case 'rollback':          break;  // { reason? }
  }
});

// Remember to clean up
p.remove();
e.remove();
```

---

## How an update reaches a device

```
app launches
    │
    ├─ OtaSdkHelper promotes any pending bundle → active
    ├─ React Native loads the active OTA bundle (or the embedded one)
    │
    ├─ configure()      → crash guard runs for this launch
    ├─ checkForUpdate() → server decides using channel, platform,
    │                     app version range and rollout percentage
    │
    ├─ downloadBundle() → patch if available, else full bundle
    │                     SHA-256 + signature verified natively
    ├─ applyPendingBundle()
    │
    └─ markStable()     → after ~5s without a crash

next launch: the new bundle is live
```

The update always applies on the **following** launch. There is no way around
this — the JavaScript runtime has to restart to pick up a different bundle.

---

## Crash guard

The SDK counts launches that end before `markStable()` is called. Reaching
`crashThreshold` (default 3) discards the active OTA bundle and clears OTA state,
so the next launch loads the bundle shipped in your binary.

This runs entirely on the device. It works even if the OTA server is unreachable,
and requires nothing from your code beyond calling `markStable()` at the right
moment.

Applying a new bundle resets the counter.

---

## Delta updates

When the server has a patch from the device's current bundle to the new one, the
update check includes `patchUrl`, `patchHash` and `fromHash`. Pass them to
`downloadBundle()` — `OTAProvider` and `checkAndApply()` already do — and the SDK
downloads the patch and reconstructs the new bundle locally.

Patches are byte-level (bsdiff), so a one-line JavaScript change produces a patch
of a few kilobytes rather than a multi-megabyte download. Every reconstructed file
is checked against the size and SHA-256 recorded in the patch manifest; any
mismatch aborts the update and leaves the current bundle untouched.

If anything about the patch fails, the SDK falls back to nothing — it does not
silently install a partially-correct bundle.

A device only receives a patch when it is running the bundle **immediately
before** the new one. The server computes exactly one patch per upload, against
the newest active bundle for that app, channel and platform, and offers it only
when the device's current hash matches the hash the patch was built from. A
fresh install, or a device two or more versions behind, downloads the full
bundle. Patches are not chained.

---

## Testing

### Use a release build

OTA cannot be tested against Metro. On a debug build React Native loads
JavaScript from the dev server, and the SDK deliberately returns null from
`getJSBundleFile` so it does not fight it. Updates will download, verify and
apply perfectly, and nothing on screen will change.

```sh
cd android && ./gradlew installRelease
```

Then confirm the host hook is actually running:

```sh
adb logcat -s OTA-Host:I
# loading OTA bundle: /data/user/0/<pkg>/files/ota/bundles/<hash>/index.android.bundle
```

Silence here means the hook is never called — see [Native setup](#native-setup).

### Verifying a delta download

Delta needs two uploads in order, because the first one has nothing to diff
against.

**1. Get the device onto bundle A.** Build and upload any bundle, let the device
download it, then fully restart the app:

```sh
adb shell am force-stop <your.package.name>
```

This first download is always full — the device sent an empty
`currentBundleHash`, so no patch was possible.

**2. Upload bundle B.** Change a line, rebuild, upload. The server logs:

```
[BundleService] Delta computed: 99.2% smaller (full=7200KB, patch=58KB)
```

If that line is missing, no patch was created and nothing downstream will use
one.

**3. Watch the device take the patch path:**

```sh
adb logcat -s OTA-SDK:D -s OTA-BundleManager:D
# Using delta download for <bundleId> (from=<hashA>)
# Delta applied: /data/.../files/ota/bundles/<hashB>/index.android.bundle
```

The reconstructed bundle's hash matching the server's hash is proof the patch
rebuilt the file byte for byte.

**4. Or assert it in JavaScript**, which survives release-build log stripping:

```js
onOTAEvent((e) => {
  if (e.type === 'download_complete') console.log('delta used:', e.delta);
});
```

Common reasons a delta does not happen: uploading B before the device has
applied A, a channel or platform mismatch between the two uploads, or a version
range on either bundle that excludes the device.

---

## Publishing bundles

Bundles are built and uploaded with the `ota` CLI from the server repo, not from
this SDK:

```sh
ota deploy --platform android --channel production
```

See the [ota-service docs](https://github.com/abhishek-auw/ota-service) for the
CLI, the REST API and CI pipeline templates.

---

## Troubleshooting

**"Native module not found"**
Autolinking has not run. `cd ios && pod install` for iOS; rebuild the app for
Android. A Metro reload is not enough — this is a native module.

**Updates download, verify and apply — but nothing changes on screen**
The download path and the load path are independent, so every log can say
success while React Native carries on running the bundle from your binary.
Check `adb logcat -s OTA-Host:I`. Three causes, in order of likelihood:

1. You are on a debug build. Metro serves the JS and OTA is skipped by design.
   Build release.
2. You wired `reactNativeHost` in a New Architecture app. If your
   `MainApplication` calls `loadReactNative(this)` and overrides `reactHost`,
   then `getJSBundleFile()` on `reactNativeHost` is dead code — the path has to
   go to `getDefaultReactHost(jsBundleFilePath = ...)`. Nothing warns about
   this; the override simply never runs.
3. The hook is missing entirely.

**App crashes at startup with `getApplicationContext()` on a null reference**
`OtaSdkHelper.getJSBundleFile(applicationContext)` is being called from a
property initializer, which runs in the `Application` constructor before
`attachBaseContext()`. Keep the call inside the `by lazy` block.

**Server returns 404 for `POST://v1/update/check`**
Note the double slash. Older versions of this SDK concatenated `serverUrl`
without normalising, so a trailing slash produced `//v1/...`. Update the SDK, or
drop the trailing slash from `serverUrl`.

**Device downloads but the URL points at `localhost`**
The server is presigning download URLs against its own address. Set
`S3_PUBLIC_ENDPOINT` to something the device can reach, or use
`adb reverse tcp:9000 tcp:9000` so `localhost` on the device means your machine.

**A good bundle keeps rolling back**
`markStable()` is not being reached — often because it sits behind a screen the
user has not opened, or the app crashes before it. Move it earlier.

**`checkForUpdate()` rejects with `NOT_CONFIGURED`**
`configure()` has not run yet. If you are using `OTAProvider`, make sure nothing
calls the SDK before it mounts.

**Update check returns nothing, server says `runtime_version_mismatch`**
The binary declares a runtime version that has no bundles published for it.
Check what the device reports with `getStatus().runtimeVersion` and compare it
against the runtime on the bundle. Usually either the manifest/plist value was
never added (it defaults to `"1"` and logs a warning), or a bundle was uploaded
with the wrong runtime.

**Update check returns nothing when a bundle exists**
The device is outside the bundle's version range, on a different channel, outside
the rollout percentage, or already running that hash. The server's audit log will
tell you which.

---

## License

MIT
