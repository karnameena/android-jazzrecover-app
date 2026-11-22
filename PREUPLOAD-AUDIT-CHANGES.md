# Jazz Recovery — pre-upload preparation

## Changes made in this package
Only Android build configuration was updated:
- Android Gradle Plugin: 8.7.3 -> 8.9.1
- Gradle wrapper: 8.9 -> 8.11.1
- compileSdk: 35 -> 36
- targetSdk: 35 -> 36

## Protected functionality
No source files under app/src/main were edited. In particular, the camera and location implementation, permissions, RecoveryAgentService, MainActivity, Bluetooth helpers, and recovery workflow were preserved byte-for-byte.

## Important validation status
This is a prepared source package, NOT a verified release build.
The environment used to prepare it did not have Android SDK Platform 36, Android Build Tools, a connected Android device, or a usable Gradle dependency download. Therefore:
- No Gradle build, Android Lint, unit test, instrumentation test, release APK/AAB generation, signature verification, install test, or device regression test is claimed as passed.
- The existing debug APK from the input archive was intentionally excluded because it is stale and is not a release deliverable.
- targetSdk 36 activates the applicable Android 16 behavior and policy enforcement. Although the camera/location source and permission declarations are unchanged, only physical-device regression testing can confirm the existing workflow behaves as expected on target API 36.

## Manual steps still required
1. Open this project in Android Studio using JDK 17.
2. Install Android SDK Platform 36 and matching Build Tools.
3. Sync Gradle and run `:app:clean :app:lint :app:testDebugUnitTest :app:assembleDebug`.
4. Install the debug build on the actual recovery phone and test the protected camera/location workflows, including screen-off, app background, service restart, network loss and permission states.
5. Configure release signing locally. Do not send keystore files or passwords to anyone.
6. Run `:app:bundleRelease`, then inspect the generated AAB and verify signing before uploading.
7. Keep the R8 mapping file private for the exact release build.

## Release identity
- Application ID: com.gunakarna.jazzrecovery
- Version name: 1.0.0
- Version code: 1 (keep for first submission; increment for subsequent updates)
- Minimum SDK: 26
