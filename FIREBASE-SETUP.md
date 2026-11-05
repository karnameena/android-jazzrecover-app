# Firebase Cloud Messaging and automatic reconnect

## Intended behavior

1. The Android application initializes Firebase manually from the four Gradle `BuildConfig` values. The Google Services Gradle plugin and `google-services.json` are not required by this source.
2. On app process start, `JazzRecoveryApplication` queues a network-constrained one-time heartbeat and a persistent 15-minute WorkManager fallback.
3. After `BOOT_COMPLETED`, `USER_UNLOCKED`, or `MY_PACKAGE_REPLACED`, `RecoveryBootReceiver` queues an immediate network-constrained reconnect. It does not start the camera/location foreground service.
4. Firebase token creation/refresh is stored locally and registered at `/android/device/push-token` using the existing device bearer token.
5. The website's Enable Lost Mode action queues `set_recovery_mode` and sends a high-priority, data-only FCM wake-up. The push is a silent wake-up hint, not a visible notification. Android authenticates to the API, fetches only `set_recovery_mode`, applies it, and acknowledges the result.
6. Camera, location, ring, and other foreground-only commands remain in the existing owner-enabled `RecoveryAgentService`.

## Android Firebase properties

Create or update `%USERPROFILE%\.gradle\gradle.properties` locally (never commit it):

```properties
jazzFirebaseApiKey=YOUR_FIREBASE_WEB_API_KEY
jazzFirebaseAppId=YOUR_FIREBASE_ANDROID_APP_ID
jazzFirebaseProjectId=YOUR_FIREBASE_PROJECT_ID
jazzFirebaseSenderId=YOUR_FIREBASE_SENDER_ID
```

The four values must belong to the Firebase Android app whose package is `com.gunakarna.jazzrecovery`. Do not paste service-account private keys into the Android project.

The Firebase SDK's `FirebaseInitProvider` may log that default options are unavailable before `Application.onCreate`. This project intentionally initializes the default Firebase app manually in `JazzRecoveryApplication`; the later `JazzRecoveryApp` log is the meaningful initialization result. Inspect the subsequent `FirebaseMessaging` token and `JazzRecoverySync` registration logs.

## Cloudflare requirements

- Store the complete service-account JSON only as the Worker secret `FCM_SERVICE_ACCOUNT_JSON`. Never commit it or expose its private key.
- Apply `server/server/migrations/cloudflare/0002_device_push_tokens.sql` to the exact D1 database used by the Worker that serves the website. The endpoint `/android/device/push-token` requires this table.
- Confirm the deployed Worker has the `FCM_SERVICE_ACCOUNT_JSON` secret and that its service account belongs to the same Firebase project as the Android app.
- The Worker returns `pushNotified: false` if the FCM secret, token row, or token table is unavailable. The command remains queued; the Android background polling fallback can still retrieve it later.

## Deployment safety

**Do not run `wrangler deploy` from this archive's `wrangler.toml` without first confirming the target Worker name and D1 binding.** The archive config currently names `jazz-recovery-api`, while the known Pages route has used a staging Worker. Confirm the intended staging target and database before any remote migration or deployment. Back up the target D1 database first. No remote migration, secret change, or deployment is performed by this source patch.

## Android limitations

- WorkManager periodic work has a 15-minute minimum interval and is inexact. Android may delay it for Doze, battery management, network constraints, or OEM policies.
- FCM high-priority delivery is best-effort. Force-stop, disabled/restricted app state, connectivity loss, invalid tokens, or OEM restrictions can delay or prevent delivery until the app is allowed to run again.
- A reboot reconnect is queued after the boot/unlock broadcast and waits for a validated network. This is not a continuously open socket.
- The dashboard's current `online` indicator uses a 90-second heartbeat freshness window. A background heartbeat can succeed and the device can still later display Offline between WorkManager runs. `lastSeen` is the source of truth for the most recent successful contact. Offline status alone does not prove that FCM cannot wake the phone.

## Build

From `Android/JazzRecovery-Android-Source-Cleaned`, run the included single-command build script:

```powershell
.\build-jazz-recovery.ps1
```

It checks that all four local Firebase Gradle properties exist without printing their values, builds the debug APK, and copies it to `artifacts\JazzRecovery-FCM-Reconnect-Debug.apk`. Use the real four Gradle properties on the build machine. Do not put the service-account JSON into Gradle properties or the APK.
