# S400 Bridge

A small Android app I use to copy weight and body fat from my Xiaomi S400 EU scale in Xiaomi Home to Health Connect. It exists because Xiaomi Home doesn't do that for my setup.

This is a personal workaround for `yunmai.scales.ms104`, not a general scale or fitness app. It uses Xiaomi's private cloud API, which may change.

## Install and use

Download the debug APK from [Releases](../../releases). Requires Android 14 or newer.

1. Choose the region used in Xiaomi Home. Taiwan is listed separately and uses the Singapore server.
2. Sign in with a Xiaomi password, or use **Sign in with Google via Xiaomi** and finish on Xiaomi's page in your browser. QR approval through Xiaomi Home is also available.
3. Select your own Xiaomi profile.
4. Allow S400 Bridge to write weight and body fat to Health Connect.
5. Tap **Sync now**. Use **Import existing measurements** if you want older readings.
6. In Health Connect, allow Google Health to **read** weight and body fat. Then connect/sync Health Connect inside Google Health. Giving S400 Bridge write access alone is not enough.

Automatic sync defaults to 06:00–07:30, with intervals of 15, 30, or 60 minutes. It stops polling for that window after transferring a new complete measurement. Android can delay background work; the interval is not exact. Manual sync works outside the window.

## Data and limitations

- Transfers source timestamps and measured values. Missing body fat stays missing.
- Stable record IDs prevent duplicate writes when retrying.
- Tokens and cached measurements are encrypted locally with Android Keystore. Passwords are not saved. No analytics or intermediary server.
- No Bluetooth scanning, Shizuku, or permanent foreground service.
- Google-linked sign-in uses Xiaomi's browser flow; Google credentials are not entered in this app.
- Password CAPTCHA and some verification flows are unsupported. The app reports failures rather than simulating a connection.
- This is a debug build for personal use. Device and cloud behavior still need real-world checking; automated tests do not establish end-to-end compatibility.

## Build

JDK 17+, Android SDK 37, and an Android Studio version supporting AGP 9.4.1.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

The current build passes 44 JVM tests and Android lint. Keep the same signing key for updates. Clearing app data or uninstalling removes the local session; it does not remove records already written to Health Connect.

## References

Cloud history is based on [SmartScaleConnect](https://github.com/AlexxIT/SmartScaleConnect). Browser/QR authentication is adapted from [Xiaomi Cloud Tokens Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor). Both are MIT-licensed; their notices are in [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) and included in the APK.

[Implementation details and test limitations](docs/implementation.md).
