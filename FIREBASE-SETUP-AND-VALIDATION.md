# Jazz Recovery — Firebase / FCM integration handoff

## What is already implemented in this source
- Android Firebase Messaging dependency is present (`com.google.firebase:firebase-messaging:24.1.2`).
- `JazzRecoveryApplication` initializes Firebase from four optional Gradle properties.
- `RecoveryPushMessagingService` handles token refresh and data-only `command_wakeup` messages.
- `RecoveryHeartbeatWorker` registers the FCM token with the authenticated device endpoint, sends heartbeat, and fetches only `set_recovery_mode` through `backgroundSafe=1`.
- Cloudflare Worker `android-routes.js` accepts `/android/device/push-token`, stores the token in D1, and sends FCM HTTP v1 data messages after a command is queued when `FCM_SERVICE_ACCOUNT_JSON` is configured.
- D1 migration `0002_device_push_tokens.sql` creates the push-token table.

## Protected recovery workflows
The following Android files were verified byte-for-byte identical between the two supplied archives:
- `RecoveryAgentService.kt`
- `GlobalLocationController.kt`
- `MainActivity.kt`

No changes were made to camera/location implementation or the existing setup/pairing screen.

## Remaining owner-only Firebase steps
1. In Firebase Console, create/open the project and register Android package `com.gunakarna.jazzrecovery`.
2. From Project settings > General > Your apps, obtain:
   - API key
   - Android App ID
   - Project ID
   - Cloud Messaging sender ID / project number
3. Copy `Android/gradle.properties.firebase.example` values into `%USERPROFILE%\\.gradle\\gradle.properties`, replacing placeholders. Keep that file outside Git.
4. In Google Cloud Console for that Firebase project, ensure Firebase Cloud Messaging API is enabled and create a service account with permission to send FCM messages.
5. Store the full service-account JSON locally as the Cloudflare Worker secret:
   `npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON`
   Paste the JSON only into the Wrangler secret prompt. Never put it in source control or chat.
6. Apply the D1 migration and deploy only after reviewing/approving:
   `npx wrangler d1 migrations apply jazz-recovery-main --remote`
   `npx wrangler deploy`
7. Build/install the Android app and open it once with the existing server URL and device pairing. Confirm the token is registered and test Lost Mode wake-up.

## Build
From `Android/JazzRecovery-Android-Source-Cleaned`:
`./gradlew.bat assembleDebug` (Windows PowerShell)

## Validation performed here
- Both uploaded archives were extracted and inspected.
- Protected files listed above were compared byte-for-byte and matched.
- Confirmed the Firebase dependency, config-property wiring, FCM service, worker token registration and background-safe command path exist.
- Confirmed the D1 migration and Cloudflare FCM sender exist.
- Android Gradle build was attempted, but this environment could not download Gradle 8.11.1 because external network access was unavailable. No build success is claimed.
- No Firebase project was created/changed, no service-account secret was set, no remote D1 migration was applied, and no Worker was deployed.

## Runtime limitations
FCM and WorkManager are best-effort. Android Doze, force-stop, battery optimization, network availability and OEM background restrictions can delay or prevent delivery. The background worker only handles the recovery-mode command; camera/location commands remain with the existing foreground recovery service.
