Jazz Recovery FCM / Reboot Reconnect patch
Date: 2026-11-04

This source patch improves:
- Firebase manual initialization diagnostics and initial FCM token acquisition.
- Immediate network-constrained startup and post-boot synchronization.
- FCM wake-up queuing, token refresh and deleted-message reconciliation.
- Authenticated heartbeat, FCM registration, Lost Mode command processing and acknowledgement diagnostics.
- Cloudflare FCM send handling when the push-token D1 migration is not yet present.
- Integration tests for FCM token registration, high-priority data-only push, background-safe Lost Mode command acknowledgement and missing migration behavior.

Protected files not changed:
- MainActivity.kt
- RecoveryAgentService.kt
- GlobalLocationController.kt
- BluetoothHelperReporting.kt

See FCM-RECONNECT-PATCH-VALIDATION.md and Android/JazzRecovery-Android-Source-Cleaned/FIREBASE-SETUP.md before building or deploying. Run Android/JazzRecovery-Android-Source-Cleaned/build-jazz-recovery.ps1 for a single-command debug APK build.

Important:
- Android WorkManager and FCM are best-effort; this is not a guarantee of a permanently open connection.
- The FCM payload is a silent data-only wake-up hint, not a visible notification.
- Apply D1 migration 0002 to the correct Worker database before expecting FCM token registration.
- Do not deploy using the included wrangler.toml until the intended Worker name and D1 binding have been verified.
- No production migration, Worker deployment, secret change, or real-device FCM test was performed here.
