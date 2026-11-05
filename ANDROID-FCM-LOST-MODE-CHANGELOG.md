# Jazz Recovery Android — FCM / Lost Mode branch

- Branch created: `feature/fcm-cloudflare-workers-lost-mode-20261105-0000`
- Created: 2026-11-05 00:00 Asia/Kolkata (timestamp encoded in branch name; rounded branch label)
- Source package: `JazzRecovery-FCM-LostMode-Final-Source-04-1-26-19-26-PM.zip`
- Baseline: existing `main`; this branch adds the cleaned Android Gradle source tree under `android/`.

## What is added

- Complete supplied Android source project, manifest, Gradle build files/wrapper, resource assets, Firebase setup notes and reconnect implementation notes.
- FCM messaging service for token refresh and data-only `command_wakeup` messages.
- Application Firebase initialization through local Gradle properties.
- Heartbeat/background worker behavior to register the device FCM token and poll the safe Lost Mode command.

## Protected workflows

The ZIP handoff explicitly states that `RecoveryAgentService.kt`, `GlobalLocationController.kt`, and `MainActivity.kt` were compared byte-for-byte between the supplied archives and matched. The Android source changes described in the handoff preserve existing camera/location and foreground workflows. Do not change these flows casually.

## Firebase configuration and secrets

Populate the example Gradle properties locally, outside Git. Store the Google service-account JSON only as the Cloudflare Worker secret `FCM_SERVICE_ACCOUNT_JSON`. Never commit Firebase service-account JSON, private keys, production tokens, signing keystores, or credentials.

## Verification / limitations

The supplied handoff says the Gradle build could not complete in its inspection environment because it could not download Gradle 8.11.1. It does not claim a successful build. Physical-device FCM delivery, token registration, and end-to-end Lost Mode must still be verified with the configured Firebase project and deployed Worker.

## Previous vs current

The Android repository's existing main branch had a small Android stub/source snapshot and packaged APKs. This branch adds the cleaned Gradle Android source from the ZIP in a separate `android/` directory without altering the existing root content. APK binaries are not promoted as release artifacts in this branch.
