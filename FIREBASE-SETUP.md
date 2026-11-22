# Firebase Cloud Messaging and automatic reconnect

## Implemented behavior

1. The Android application manually initializes Firebase from four Gradle `BuildConfig` values. The Google Services Gradle plugin and `google-services.json` are not required by this source.
2. On app process start, `JazzRecoveryApplication` queues a one-time network-constrained authenticated sync and a persistent 15-minute WorkManager fallback.
3. After `BOOT_COMPLETED`, `USER_UNLOCKED`, or `MY_PACKAGE_REPLACED`, `RecoveryBootReceiver` queues a network-constrained reconnect. The work waits until Android reports a connected network.
4. Firebase token creation/refresh is stored locally and registered at `/android/device/push-token` using the existing device bearer token.
5. The website's Enable Lost Mode action queues `set_recovery_mode` and sends a high-priority, data-only FCM wake-up hint. Android authenticates to the API, fetches only the background-safe Lost Mode command, persists the state and acknowledges the result. The data-only push is silent; it is not a visible notification.
6. Camera, location, ring, and other foreground-only commands remain in the existing owner-enabled `RecoveryAgentService`.

## Android Firebase properties

Create or update `%USERPROFILE%\.gradle\gradle.properties` locally (never commit it):

```properties
jazzFirebaseApiKey=YOUR_FIREBASE_WEB_API_KEY
jazzFirebaseAppId=YOUR_FIREBASE_ANDROID_APP_ID
jazzFirebaseProjectId=YOUR_FIREBASE_PROJECT_ID
jazzFirebaseSenderId=YOUR_FIREBASE_SENDER_ID
```

The four values must belong to the Firebase Android app whose package is `com.gunakarna.jazzrecovery`. Do not paste service-account private keys into the Android project. The included `build-jazz-recovery.ps1` checks that all four values exist without printing them.

## Cloudflare configuration verified in the connected account

- Pages staging project: `jazz-recovery-staging` (`jazz-recovery-staging.pages.dev`).
- Pages `JAZZ_API_ORIGIN` is configured for both production and preview environments to `https://jazz-recovery-api-staging.jazz-recovery.workers.dev`.
- Staging Worker: `jazz-recovery-api-staging`.
- The staging Worker is bound to D1 database `jazz-recovery-main` (database ID `d74d8661-0c9d-4fde-8c19-a5e56aafb51e`). It is NOT bound to `jazz-recovery-auth-staging`.
- Worker secret `FCM_SERVICE_ACCOUNT_JSON` is present. The secret value was not read or exposed, so its validity and Firebase project match are not verified.
- `device_push_tokens` already exists in `jazz-recovery-main`; do not blindly reapply the initial D1 migration.
- The live `device_push_tokens` table currently contains zero registered tokens. The phone must complete authenticated token registration before FCM can wake it.

## Owner-only configuration and deployment steps

1. Confirm the Firebase Android app package is exactly `com.gunakarna.jazzrecovery`.
2. Add the four Firebase Gradle properties above to the local user Gradle file. Never commit the real values.
3. Confirm the existing Worker service-account secret belongs to the same Firebase project. If it must be changed, use the Cloudflare secret prompt locally; never paste it into source control or chat.
4. Keep the Android device's server URL set to `https://jazz-recovery-api-staging.jazz-recovery.workers.dev` for staging. Preserve its existing paired device ID and token.
5. Build and install the debug APK, open the app once, and allow it to finish pairing/startup.
6. Confirm Logcat shows Firebase initialization, FCM token acquisition, `FCM token registered with recovery server: HTTP 200`, and `Authenticated heartbeat accepted by server: HTTP 200`.
7. Only after source review and explicit release approval, deploy the staging Worker from `server/server` with `npx wrangler deploy --config cloudflare/wrangler.toml`. The config targets `jazz-recovery-api-staging` and binds `jazz-recovery-main`; verify both before deploying. No production Worker or Pages deployment is authorized by this handoff.

## Heartbeat and website status semantics

- Android periodic WorkManager heartbeat: 15 minutes (inexact; Android may defer it).
- Server online freshness window: 30 minutes. This gives the 15-minute periodic worker scheduling/Doze jitter a reasonable grace period.
- Website label: `ONLINE • RECENT HEARTBEAT`. It does not claim that a permanent socket is open.
- Device status refresh on the website: every 5 seconds while the dashboard is active.
- The same 30-minute rule is used when the website reads the status of a pending command.

## Android limitations

WorkManager and FCM are best-effort. Doze, battery optimization, manufacturer background restrictions, connectivity loss, invalid FCM tokens, disabled notifications/app restrictions, and force-stop can delay or prevent execution. A force-stopped app generally cannot receive FCM or boot broadcasts until the user launches it again. This design is a resilient reconnect-and-wake pattern, not a permanently open server socket or guaranteed remote wake mechanism.

## Build

From this project directory on Windows PowerShell, run:

```powershell
.\build-jazz-recovery.ps1
```

The script builds `:app:assembleDebug` and copies the APK to `artifacts\JazzRecovery-FCM-Reconnect-Debug.apk`. It requires the four local Firebase Gradle properties. For release signing, configure the four `JAZZ_RELEASE_*` environment variables locally; never put keystores or signing passwords in the ZIP.
