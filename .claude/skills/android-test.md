---
name: android-test
description: Generate unit tests, instrumentation tests, and Compose UI tests for the BleScanner Android project. Covers ViewModel, Repository, Room DAO, BLE logic, and Compose screens.
user_invocable: true
---

# Android Test Generator

Generate tests for the BleScanner app. Read the source file first, then create appropriate tests.

## Test Strategy by Layer

### 1. Unit Tests (test/) - No Android framework needed

**BleScanner logic tests:**
- Brand detection from manufacturer data bytes
- Apple Continuity TLV parsing
- RSSI smoothing calculation
- Distance estimation formula
- Confidence score calculation
- Stationary detection algorithm
- Phone vs non-phone filtering
- MAC address categorization

**ViewModel tests:**
- State updates on scan start/stop
- Device list filtering
- Statistics calculation
- Export trigger

**Repository tests:**
- Data transformation between entities and domain models

### 2. Instrumented Tests (androidTest/) - Requires Android device/emulator

**Room Database tests:**
- DAO query correctness
- Insert/update/delete operations
- Foreign key constraints
- Migration tests
- Complex queries with joins

**Compose UI tests:**
- MainScreen renders device list
- Start/Stop button state changes
- Statistics display updates
- Permission request flow
- Empty state display

### 3. Integration Tests

**BLE Service tests:**
- Service starts/stops correctly
- Notification created properly
- Scan results flow to database

## Test Dependencies to Add

If not already present, suggest adding to `app/build.gradle.kts`:
```kotlin
// Unit Tests
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
testImplementation("io.mockk:mockk:1.13.12")
testImplementation("app.cash.turbine:turbine:1.1.0") // Flow testing

// Instrumented Tests
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation("androidx.room:room-testing:2.6.1")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

## Output Format

1. Read the source file the user wants tested
2. Create the test file in the correct directory:
   - Unit tests: `app/src/test/java/com/increxa/blescanner/`
   - Android tests: `app/src/androidTest/java/com/increxa/blescanner/`
3. Include clear test names describing the scenario
4. Use AAA pattern (Arrange, Act, Assert)
5. Cover happy path, edge cases, and error cases
