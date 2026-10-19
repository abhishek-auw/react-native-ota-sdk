# CrashGuard redesign

> **Status:** Phase 1 and Phase 2 implemented. As built, Phase 2 differs from the plan below in two ways:
> - Fatal JS errors are caught by the native crash handlers (`JavascriptException` / `RCTFatalException`), not an `ErrorUtils` hook. In release builds React Native turns a fatal JS error into that native crash anyway, and the native handlers write synchronously.
> - A bundle that never confirms but never crashes is caught by `maxUnconfirmedLaunches` (default 5).

## Context
Today a bundle counts as "good" if JS survives `stableAfterMs` (5000 ms, `src/OTAProvider.tsx:183`) and then calls `markStable()`. The counter goes up in `CrashGuard.onAppStart()`, which is only called from JS `configure()` (`OtaSdkModule.kt:66`). Problems with this:

1. **Bundles that crash early are never counted.** If the bundle crashes during module evaluation or before `<OTAProvider>` mounts, `configure()` never runs, so the counter never goes up. That leaves the app in an endless crash loop, which is the exact case CrashGuard is meant to catch.
2. **Rollback happens too late.** It runs after the bad bundle is already loaded. `rollback()` only clears prefs, so the fix only shows up on a later launch.
3. **A timer is not a health check.** If the user closes or backgrounds the app within 5 s three times, a good bundle gets rolled back. If the bundle crashes at 6 s, or renders a blank or broken screen, it is still marked stable.
4. **Counting never stops.** Every launch increments the counter, even on a bundle that was confirmed long ago.
5. **Rollback always goes to the embedded bundle**, not to the previous good OTA bundle (`BundleManager.rollback()`).
6. **No blocklist.** The next `checkForUpdate` downloads the same broken bundle again.
7. **The `crashThreshold` config is ignored.** It is parsed, but `CrashGuard(prefs)` always uses 3.
8. **Writes can be lost.** `SharedPreferences.apply()` is async, so a native crash right after it can drop the write.
9. **Off-by-one:** the first launch of a new bundle resets the counter without counting itself.

## Rollout: two phases
- **Phase 1 (bug fixes, keeps the timer):** steps 1–2 below, plus `commit()`, honoring the threshold, keeping the previous bundle, and the blocklist (step 5). This fixes problems 1, 2, 5, 6, 7, 8 and 9.
  - **No backend changes needed.** The blocklist is applied on the device, and rollbacks are reported through the existing `/v1/analytics/event` with the `"crash"` event type.
  - Also fix: `runCrashGuardCheck` reads `activeBundleHash` after `rollback()` has cleared it, so crash events are sent with no bundle. Save the failed `activeBundleId` before rolling back and send that.
  - Optional server work later: `failedHashes` in `/v1/update/check`, a `"rollback"` event with a reason, and an automatic rollout pause when the rollback rate is too high.
- **Phase 2 (real health check):** steps 3–4. This adds `notifyAppReady`, the foreground-session flag and exit-reason detection, and fixes problems 3 and 4.

## Edge cases to handle
- **Background starts must not count as launches.** Headless JS or a push can start the process with no UI, and `notifyAppReady()` never runs then. Only count a launch once an Activity or scene comes to the foreground; on Android, mark it in `onActivityResumed` through `ActivityLifecycleCallbacks`.
- **Native crashes (SIGSEGV in Hermes or a native module) skip the Java crash handler.** The launch counter and `ApplicationExitInfo` (API 30+) are the safety net.
- **iOS has no exit-reason record** (MetricKit reports arrive about 24 h later). Use a "session active" flag instead: set it on entering the foreground, clear it on entering the background. If the next launch finds it still set, the app crashed while in use. A user swipe-kill always happens from the background, so it never counts. Use the same flag on Android below API 30.
- **`cleanOldBundles()` must keep the previous good bundle on disk,** or rollback has nothing to go back to.
- **Confirming after the first render misses later crashes.** The default is first render + a short foreground time (for example 3 s) + no fatal JS error. Apps can pass `autoConfirm={false}` and confirm at their own point.
- A server-side kill switch (crash rate per bundleId from Sentry or Crashlytics) can be added later, but only on top of the device check: a device stuck in a crash loop can never reach the server.

## Recommended design (the "confirm or revert" model used by CodePush and expo-updates)

### 1. Bundle state machine (persisted in prefs)
- `currentBundle` and `previousGoodBundle` (each has hash, path, id)
- `currentStatus`: `PENDING_VERIFICATION` | `CONFIRMED`
- `launchAttempts`: counted only while the status is `PENDING_VERIFICATION`
- `failedBundleHashes`: a set used as a blocklist

`applyPendingBundle()` moves the old current bundle to `previousGoodBundle` (only if it was CONFIRMED), sets the new one as current with status PENDING and `launchAttempts = 0`.

### 2. Check natively before JS loads
Move the check into `OtaSdkHelper.getActiveBundlePath()` (Android) and the matching iOS bundle-URL helper. This runs before React Native loads any JS:
- If the status is PENDING: `launchAttempts++` saved with **`commit()`** (synchronous). If `launchAttempts > threshold`, add the hash to the blocklist, restore `previousGoodBundle` (or the embedded bundle), and **return that path for this same launch**. Record a `rollback_pending_report` flag.
- If the status is CONFIRMED: no counting, return the path.
- Save the threshold in prefs from `configure()` so the native side can read it on later launches.

### 3. Explicit health signal instead of a timer
- Add `notifyAppReady()` / `confirmBundle()`, which sets CONFIRMED, resets `launchAttempts`, and deletes old bundle dirs except current and previous.
- Keep `markStable()` as a deprecated alias.
- `OTAProvider` calls it by default after the first committed render. A new `autoConfirm={false}` prop lets apps call it themselves after their real "app is usable" point (login screen shown, first API call OK).
- The timer becomes optional (`stableAfterMs` as an extra minimum), not the definition of healthy.

### 4. Detect real crashes, not just short sessions
- **JS:** wrap `ErrorUtils.setGlobalHandler`. On a fatal error before confirmation, write a fatal crash for the current hash natively, then call the previous handler.
- **Android native:** a chained `Thread.setDefaultUncaughtExceptionHandler` does the same. On API 30+, read `ActivityManager.getHistoricalProcessExitReasons` on the next launch to tell `REASON_CRASH`, `CRASH_NATIVE` and `ANR` apart from `USER_REQUESTED`.
- **iOS:** an `NSSetUncaughtExceptionHandler` plus a "clean exit" flag set in `applicationWillTerminate` or on backgrounding.
- If a confirmed fatal crash is found before confirmation, roll back right away. Don't wait for 3 attempts.

### 5. Close the loop with the server
- `checkForUpdate` skips any bundle whose hash is in the blocklist (filter on the client, and send the hashes in the request).
- On the first launch after a rollback, `configure()` sees `rollback_pending_report` and sends `reportEvent("rollback", bundleId, reason)` (crash or launch-attempts). The server can then auto-pause the rollout when the rollback rate goes over a set level.

### 6. Make the counter testable
- Pull the decision logic into a pure function, `CrashGuard.evaluateLaunch(state) -> Decision`, so it can be unit tested without prefs.

## Files to change
- `android/src/main/java/com/otasdk/CrashGuard.kt`: rewrite as a state machine and `evaluateLaunch`
- `android/src/main/java/com/otasdk/OTAPrefs.kt`: new keys, `commit()` for crash-guard writes
- `android/src/main/java/com/otasdk/OtaSdkHelper.kt`: run the check before JS, return the rollback path
- `android/src/main/java/com/otasdk/BundleManager.kt`: track `previousGoodBundle` in apply and rollback; `cleanOldBundles` keeps the previous bundle
- `android/src/main/java/com/otasdk/OtaSdkModule.kt`: `notifyAppReady`, save the threshold, report pending rollbacks, blocklist filter, remove the `configure()` crash check
- iOS versions: `ios/CrashGuard.swift`, `ios/OTAPrefs.swift`, `ios/OtaSdkHelper.swift`, `ios/BundleManager.swift`, `ios/OtaSdk.swift`, `ios/OtaSdk.mm`
- JS: `src/NativeOtaSdk.ts`, `src/index.ts` (`notifyAppReady`, global error hook), `src/OTAProvider.tsx` (auto-confirm after first render, `autoConfirm` prop)
- Tests: `android/src/test/java/com/otasdk/CrashGuardTest.kt`, extended for every state change

## Verification
- `./gradlew :react-native-ota-sdk:testDebugUnitTest` for the CrashGuard state tests.
- Example app, release build, with a test bundle that throws at module top level: the app should load the previous bundle on launch N+1 (N = threshold), and the server should get a rollback event.
- A test bundle that throws 10 s in, before confirmation, should roll back after a single fatal crash.
- Close a good bundle 3 times within 2 s: there should be **no** rollback once it is confirmed, and no rollback from user kills on API 30+.
- After a rollback, `checkForUpdate` should not offer the blocklisted hash again.
