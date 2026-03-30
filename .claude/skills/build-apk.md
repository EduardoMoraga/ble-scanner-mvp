---
name: build-apk
description: Build, lint, and prepare APK for the BleScanner Android project. Runs Gradle tasks, checks for errors, and reports build status.
user_invocable: true
---

# Build & Deploy APK

Build the BleScanner Android app and report results.

## Build Process

1. **Pre-build checks:**
   - Verify Gradle wrapper exists (`gradlew` / `gradlew.bat`)
   - Check `local.properties` has valid SDK path
   - Verify JDK 17 is available

2. **Run lint first:**
   ```bash
   ./gradlew lint
   ```
   - Parse lint report at `app/build/reports/lint-results-debug.xml`
   - Report critical/warning issues
   - Ask user if they want to proceed despite warnings

3. **Build the APK:**
   - Debug: `./gradlew assembleDebug`
   - Release: `./gradlew assembleRelease`
   - Default to debug unless user specifies release

4. **Post-build:**
   - Report build success/failure
   - Show APK location and size
   - If errors: read build output, identify the root cause, suggest fixes

5. **Optional: Install on device:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Only if user requests and a device is connected.

## APK Locations
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Common Build Errors

- **SDK not found:** Check `local.properties` for `sdk.dir`
- **Kotlin version mismatch:** Verify Kotlin plugin version matches compose compiler
- **Room schema export:** May need `room.schemaLocation` in KSP arguments
- **JDK version:** Must be JDK 17 for this project

## Version Bump
If the user asks to bump the version:
- Edit `app/build.gradle.kts`: increment `versionCode` and update `versionName`
- Suggest semantic versioning for `versionName`
