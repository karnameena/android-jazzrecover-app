# Jazz Recovery Android — Build and Release Notes

## Current project configuration

- Application ID: `com.gunakarna.jazzrecovery`
- Version name: `1.0.0`
- Version code: `1`
- Minimum SDK: 26
- Compile SDK: 36
- Target SDK: 36
- Android Gradle Plugin: 8.9.1
- Gradle Wrapper: 8.11.1 (wrapper files are included)
- Kotlin Android plugin: 2.0.21
- Java toolchain: 17

The previous version of this document incorrectly said the wrapper was missing and that the target SDK was 35. The current project files include `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties`, and the app targets API 36.

## Build a debug APK (Windows PowerShell)

1. Install JDK 17 and Android SDK Platform 36 plus the matching Build Tools.
2. Add the four Firebase values to `%USERPROFILE%\.gradle\gradle.properties` as described in `FIREBASE-SETUP.md`.
3. From this project directory, run:

```powershell
.\build-jazz-recovery.ps1
```

Or run the wrapper directly:

```powershell
.\gradlew.bat :app:clean :app:lint :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

The helper script copies the debug APK to `artifacts\JazzRecovery-FCM-Reconnect-Debug.apk` and prints its SHA-256 hash.

## Release build

The release build enables R8 minification and resource shrinking. Configure all four environment variables locally or leave all four unset for an unsigned release build:

- `JAZZ_RELEASE_STORE_FILE`
- `JAZZ_RELEASE_STORE_PASSWORD`
- `JAZZ_RELEASE_KEY_ALIAS`
- `JAZZ_RELEASE_KEY_PASSWORD`

Never include the keystore, passwords, `local.properties`, Firebase service-account JSON, or Gradle user properties in a source ZIP. After `:app:bundleRelease`, inspect the AAB signature and keep the exact R8 `mapping.txt` privately. Do not upload an unsigned AAB to Play Console.

## Validation status for this handoff

The Gradle build was attempted in the review environment. The wrapper could not download Gradle 8.11.1 because outbound DNS/network access was unavailable (`services.gradle.org` could not be resolved). No Android build success is claimed. No Android SDK, emulator, ADB-connected device, `apkanalyzer`, `aapt`, or `apksigner` was available here. Physical reboot, FCM delivery, camera, location, Bluetooth, and foreground-service regression tests remain owner/device tests.

## Release safety

Do not change target SDK, permissions, the existing recovery service, camera/location workflow, or device pairing flow as part of this heartbeat/FCM task. Test all existing recovery features on the actual Android 16/API 36 device before release.
