# Jazz Recovery Android — Build and Release Notes

## Important status

This source archive includes a proposed R8 release configuration, a narrow keep rule for the persisted WorkManager worker class, environment-variable-based optional release signing, and a round launcher icon derived from the original logo. These changes passed static configuration/resource checks only. A Gradle build, R8 execution, APK inspection, signature check, install test, and device regression test were **not run** in the audit environment.

The project does not include `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`, or `gradle-wrapper.properties`. The original `android/local.properties` was excluded because it contains a machine-specific Windows SDK path.

## Toolchain to install

- JDK 17.
- Android Studio with Android SDK Platform 35 and Build Tools installed.
- Gradle 8.9 for the existing Android Gradle Plugin 8.7.3 configuration.
- For Google Play submission, plan a separately tested migration to `compileSdk = 36` and `targetSdk = 36`. As of August 31, 2026, new Play apps and updates must target Android 16 / API 36 or higher. Do not change target SDK without regression testing camera, location, Bluetooth, foreground services, and background behavior.

## Build using Android Studio

1. Extract the source ZIP.
2. Open the folder containing `settings.gradle.kts` (the root of this Android project).
3. Configure Android Studio to use JDK 17 and a local Gradle 8.9 installation because this archive does not include a wrapper.
4. Install Android SDK Platform 35 and required Build Tools.
5. Sync the project and resolve any dependency downloads.
6. Run `:app:assembleDebug` first to establish a fresh build baseline.
7. Run `:app:lint` and available tests.
8. Run `:app:assembleRelease` to produce a release variant with R8 shrinking/obfuscation and resource shrinking enabled.
9. With no signing environment variables configured, the release artifact should remain unsigned. Confirm the exact output name and signature using Android SDK tools; do not assume it is unsigned without checking.
10. Keep `app/build/outputs/mapping/release/mapping.txt` privately for the exact build.

## Optional Gradle Wrapper generation

After installing Gradle 8.9, run from the Android project root:

```bash
gradle wrapper --gradle-version 8.9
```

This creates wrapper files in your local working copy. Review the generated files and commit them only after verifying them. Then use `./gradlew` on macOS/Linux or `gradlew.bat` on Windows.

## Static limitations

- No Android SDK, emulator, ADB, `apkanalyzer`, `aapt`, or `apksigner` was available during this audit.
- The existing debug APK in the uploaded archive is not used as a release deliverable because it predates the proposed R8 and round-icon changes and its signature/runtime status could not be verified.
- The target API level remains 35 in this cleaned source. This is a known Google Play submission blocker for a new app/update on the current date unless the app qualifies for an applicable extension or exception.
