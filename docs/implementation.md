# S400 Bridge

Private native Android helper for Xiaomi Body Composition Scale S400 EU (`yunmai.scales.ms104`) → Xiaomi Home cloud → Health Connect. Android 14 or newer. English interface and documentation. Includes the supplied scale/sync logo, Material 3 Expressive theme, dynamic color, and light/dark support.

## Install and set up

Install `S400-Bridge-debug.apk` on your Android phone. Allow installation from your chosen file manager when Android asks.

1. Open Settings in S400 Bridge. Choose the same Xiaomi server region shown in Xiaomi Home; Europe defaults to `de`; Taiwan is available as a separate option routed to the Singapore server (`sg`). Phone locale is not a reliable account-region signal.
2. For a Google-linked account, tap **Sign in with Google via Xiaomi**, then **Open Xiaomi sign-in · choose Google**. Sign in on Xiaomi’s page using Google and approve the session, then return to S400 Bridge. Alternatively scan the displayed QR with Xiaomi Home on another device. Xiaomi password sign-in remains available; passwords are used only in memory and never saved.
3. Select your own profile explicitly. Profiles come from real cloud history, not guessed names or weight matching. If no history exists, take and claim a measurement in Xiaomi Home, then refresh profiles.
4. Grant Health Connect write access for weight and body fat.
5. Tap **Sync now**, or enable automatic synchronization. New measurements are eligible from the moment you select a profile. Enable history import explicitly to include earlier readings.
6. Separately allow your preferred Google health app to read weight and body fat from Health Connect. Availability and consumption of those records are controlled by that app.

**This is a debug build. Real account sign-in, verification challenges, and Health Connect writes have not been tested with your account or phone.** A build passing does not establish live Xiaomi compatibility.

## Implemented

- Real Xiaomi username/password and browser/QR login, service-token acquisition, refresh using passToken, regional encrypted requests, and paginated EU S400 history retrieval.
- Profiles identified by region, account owner, and cloud accountId. Unexpected identity, timestamp, or source formats stop processing safely.
- Source formats 1/2 use `createTime`; source 3 uses its original `data.time`. Values are read from `weight` and `bfp`, including nested `bodyResData` for source 3. Missing/zero/invalid fat is not synthesized.
- Separate WeightRecord and BodyFatRecord writes at the source instant. No Health Connect read permission.
- Durable pending queue and acknowledgement after successful writes. Stable, distinct client record IDs for weight and fat; increasing versions permit correction and replay after a crash without creating another record.
- AES-GCM encryption of the entire DataStore state using a non-exportable Android Keystore key. Passwords are not persisted. Tokens, profiles, and cached readings are encrypted. No request logging, analytics, intermediary server, or advertising.
- Manual **Sync now**, one-time historical import, automatic 06:00–07:30 default window, and 15/30/60-minute choices. No Shizuku dependency or sub-15-minute option.
- Stops further automatic polling for the current window after a newly acknowledged complete measurement from that window. Weight-only and historical readings do not stop the morning search. Optional keep-looking switch.
- WorkManager persistence, network constraint, scheduling after each attempt, overnight windows, local timezone/DST handling, clock/zone/boot receivers, settings changes, encrypted diagnostic status, and permission-safe queue retention.

## Xiaomi protocol research and license

Reference inspected: [AlexxIT/SmartScaleConnect](https://github.com/AlexxIT/SmartScaleConnect), commit `a9e5c04f1079b65d456c8a5fd296775a1ef29e8f`, specifically `pkg/xiaomi/auth.go` and `pkg/xiaomi/client.go`. Its MIT notice is retained in `THIRD_PARTY_NOTICES.txt`. Protocol code is adapted to Kotlin; the Go application is not bundled.

The reference explicitly supports the EU model through Xiaomi Home. This app uses the demonstrated endpoints:

- `https://account.xiaomi.com/pass/serviceLogin` with `sid=xiaomiio`
- `https://account.xiaomi.com/pass/serviceLoginAuth2`
- `https://{region}.api.io.mi.com/app/eco/common/scale/getUserDataByPage`

Requests use a random/time nonce, SHA-256 signed nonce, SHA-1 signatures, and RC4-drop1024 as required by Xiaomi's wire protocol. This legacy wire encryption is not used to protect local storage. Existing Xiaomi Home pairing is left intact; no Bluetooth permission or scanning is needed.

The [Xiaomi Home country-to-server table](https://www.openhab.org/addons/bindings/miio/#country-servers) maps Taiwan to the Singapore server (`sg`). Taiwan is stored as the explicit country selection `tw` in app settings, while requests go to `https://sg.api.io.mi.com/app/eco/common/scale/getUserDataByPage`. Actual S400 history availability still needs verification with your account.

This is an undocumented private API, not a verified public third-party OAuth service. Endpoints or account security requirements may change. The region is explicitly selected, never silently guessed. Initial discovery loads available history. Normal polling overlaps the last successful transfer by two days; explicit import requests a full scan.

## Browser / Google-linked sign-in

The browser/QR flow is adapted from the MIT-licensed [Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor), commit `c4db715dace9806e905153c2977608873e8ab7c9`, specifically `QrCodeXiaomiCloudConnector`. The MIT notice is included in the source and APK assets. Its Python runtime, token logging, broad device extraction, and plaintext exports are not bundled.

The app requests `https://account.xiaomi.com/longPolling/loginUrl` with `sid=xiaomiio`, opens the returned official Xiaomi login URL in your system browser, and polls the returned `lp` URL. Google sign-in is hosted by Xiaomi/Google; S400 Bridge does not collect Google passwords or embed Google sign-in in a WebView. The public session endpoint was probed without user credentials: it returned the expected fields and first-party hosts, and the returned browser page contained Google sign-in. Approval with a real account has not been tested.

The optional QR image and short-lived session URLs remain only in memory and are discarded on cancellation, expiry, completion, or process death. Polling checks cancellation and expiry, uses bounded network timeouts, and accepts only HTTPS account.xiaomi.com hosts and subdomains. The app marks the account connected only after receiving a real user ID, passToken, security key, and service token. Session tokens are then stored encrypted as before. A failed profile fetch preserves the valid session so **Refresh profiles** can be retried.

Use the same Google-linked Xiaomi account as Xiaomi Home. The QR alternative authorizes through an already signed-in Xiaomi Home account; this is not a separate native Google OAuth client. Keep the app available while authorizing; if Android kills the process or Xiaomi expires the session, start a new sign-in. The full browser approval, Google redirect, and token return still require testing on your phone.

## Password authentication limitations

Xiaomi `notificationUrl` challenges are detected and can be opened on Xiaomi's HTTPS site in the system browser. Retry sign-in after completing verification. End-to-end two-factor continuation is **not proven**: some challenges depend on cookies from the original session and cannot be completed through this browser handoff. Image CAPTCHA submission is not implemented; the app reports that concrete limitation. There is no simulated successful login or fabricated cloud data. Accounts requiring these flows may remain unable to connect until a compatible continuation is implemented.

BLE fallback has not been implemented because the reference provides a concrete cloud-history route. It would require separate verification of EU firmware behavior and bind keys; no unverified BLE protocol is presented as working.

## Scheduling and recovery

[WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) is best effort, including at 15 minutes. Delays and skipped windows are possible in Doze, force-stop, offline conditions, or restrictive HyperOS policies. After force-stop, open the app again. No exact alarms, permanent foreground service, app-managed permanent wake lock, privileged scheduler, or battery-exemption prompt is used. WorkManager can hold its own short execution wake locks.

Automatic jobs recheck the window before work and before record writes. No new cloud HTTP request starts after the window closes; an already-running request can finish after the boundary. Manual sync and explicit account/profile setup may run at any time. A manual job queued offline waits for connectivity.

Failed writes remain pending. Replay uses the same IDs even if the process dies after insertion but before acknowledgement. Permission failures retain cached readings. Transient failures schedule the next eligible window check. Full-window scheduling is recalculated in the local timezone; DST gap/overlap times follow java.time's resolution rules.

No optional notification feature is included; there is no notification permission. Battery-optimized behavior is always active. The selected profile's cache is shown on Home; transfer status distinguishes pending from acknowledged readings.

## Build

Open this directory in an Android Studio version supporting AGP 9.4.1. Use JDK 17 or newer, Android SDK platform 37 and Build Tools 36+, and set your SDK path in `local.properties` or via the Android environment. The wrapper uses Gradle 9.8.1. Dependencies are pinned stable versions, including Compose BOM 2026.09.00, Material 3 1.4.0, Health Connect 1.1.0, WorkManager 2.12.0, and DataStore 1.3.0. Expressive APIs require an explicit experimental API opt-in despite the stable Material library.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for local installation only. Keep your signing key if you want future APK updates to install over this build; a differently signed build requires uninstalling first. Uninstall/clear-storage deletes local tokens and cached state. Existing Health Connect records remain; disconnect does not delete those records either.

## Validation and remaining device checks

Build, 44 JVM unit tests, and Android lint completed successfully on 10 November 2026. Tests cover inclusive/exclusive window boundaries, overnight windows, DST, timezone changes, next-day stopping, source formats, missing fat, owner filtering, stable profile identity, timestamp selection, unknown-format failure, crypto vectors, profile/baseline filtering, permission denial, failed-write retry, insertion/acknowledgement crash replay, Taiwan server routing, browser-session parsing, trusted login hosts, failed/expired session handling, and error redaction. Test fixtures are used only in tests. These are not real Xiaomi captures from your account and do not test the Android Health Connect service itself.

Still required on your device:

- Verify real sign-in, any two-factor flow, selected region and profile IDs against Xiaomi Home.
- Compare weight, fat, and timestamps for actual EU S400 readings.
- Sync the same reading twice and confirm one weight and one fat record in Health Connect.
- Test revoked permissions, airplane mode, kill/restart, reboot, timezone/settings changes, and a full HyperOS morning window.
- Check light/dark appearance, accessibility font sizes, launcher icon masking, and Google app ingestion.

The launcher uses your supplied PNG. Replace `app/src/main/res/drawable-nodpi/brand_logo.png` for a new raster image, or replace its references with an Android VectorDrawable converted from a future SVG. The adaptive foreground preserves safe padding around the artwork.
