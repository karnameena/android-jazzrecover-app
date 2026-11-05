# Automatic reconnect and FCM validation

## Background behavior

- `JazzRecoveryApplication` manually initializes Firebase from Gradle BuildConfig values, queues a one-time network-constrained startup sync, and maintains a 15-minute WorkManager fallback.
- `RecoveryBootReceiver` queues a one-time reconnect after boot, unlock, and package replacement. It does not start `RecoveryAgentService`.
- `RecoveryPushMessagingService` stores refreshed FCM tokens, handles `command_wakeup`, handles deleted-message reconciliation, and queues expedited WorkManager work with a non-expedited fallback.
- `RecoveryHeartbeatWorker` authenticates with the existing encrypted device token, registers the FCM token, posts a heartbeat, fetches only the background-safe Lost Mode command, persists the mode, and checks the acknowledgement HTTP status.
- The Worker emits diagnostic logs without logging device tokens, FCM tokens, passwords, or service-account data.

## Command behavior

The FCM message is a high-priority, data-only wake-up hint containing an event and command ID. It is not trusted as the command itself. Android fetches the command over HTTPS using the existing device bearer token. Background WorkManager processes only `set_recovery_mode`. Existing camera, location, ring and other commands remain in `RecoveryAgentService`.

## Server behavior

- `sendFcmWake` looks up the registered device token and sends the data-only message through FCM HTTP v1.
- If migration 0002 is missing, the Worker logs the missing push-token table and returns a queued action response with `pushNotified: false` rather than throwing an HTTP 500 after inserting the command.
- `0002_device_push_tokens.sql` must be applied to the correct D1 database before FCM token registration can succeed.
- The `device_state.last_seen` freshness window is still 90 seconds. A device that uses only the inexact 15-minute WorkManager fallback may display Offline between background heartbeats. This status window is not the same thing as FCM token validity or push reachability.

## Protected Android files

The patch intentionally leaves these files unchanged:
- `MainActivity.kt`
- `RecoveryAgentService.kt`
- `GlobalLocationController.kt`
- `BluetoothHelperReporting.kt`

The existing device pairing, Android Keystore token, location ordering, camera/recovery-photo flow, Bluetooth helper behavior, and foreground recovery-agent workflow are preserved.

## Real-device validation checklist

1. Complete pairing and save the HTTPS server URL/device token.
2. Launch the app once after installation and allow it to finish setup.
3. Confirm log `Firebase default app initialized from Gradle BuildConfig`.
4. Confirm log `FCM token registered with recovery server: HTTP 200`.
5. Reboot, unlock, and connect to Wi-Fi/mobile data. Confirm `Boot/unlock reconnect queued`.
6. Confirm `Authenticated heartbeat accepted by server: HTTP 200`.
7. From the website, enable Lost Mode. Confirm FCM delivery and `Received recovery command wake-up hint`.
8. Confirm `Lost Mode command applied and acknowledged: HTTP 200` and the website's mode refresh shows LOST_MODE.
9. Confirm camera, location, photo and Bluetooth helper workflows still behave as before.

Android/FCM/WorkManager delivery remains best-effort and must be validated on the actual device. This source patch does not itself apply remote D1 migrations, deploy a Worker, or send a real FCM push.
