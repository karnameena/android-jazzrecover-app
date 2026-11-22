Jazz Recovery — Find Phone UI patch

Apply only to:
android/app/src/main/java/com/gunakarna/jazzrecovery/MainActivity.kt

This patch updates the native Find Phone screen layout only. Existing callbacks are retained:
- configureWebsiteUrl()
- copyText(website)
- external browser launch with the configured HTTPS URL
- addStatusCard()
- existing screenHeader() and buildBottomNav()

Before applying, back up MainActivity.kt. No service, device ID, token, backend, or other screen files are included.
