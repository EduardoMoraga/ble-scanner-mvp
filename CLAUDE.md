# BleScanner - Android BLE Proximity Detection App

## Project Overview
Android native app (Kotlin + Jetpack Compose) that scans for BLE devices within ~1.5m range, identifies smartphones using multi-layer brand detection, and tracks dwell time/proximity metrics.

## Tech Stack
- **Language:** Kotlin 1.9.24
- **UI:** Jetpack Compose + Material Design 3
- **Database:** Room 2.6.1 (SQLite ORM)
- **Async:** Kotlin Coroutines 1.8.1
- **Build:** Gradle Kotlin DSL, AGP 8.5.2
- **Min SDK:** 31 (Android 12) | **Target SDK:** 34 (Android 14)
- **Java:** JDK 17
- **CI/CD:** GitHub Actions (build-apk.yml)

## Architecture
- **MVVM:** ViewModel + StateFlow for UI state
- **Repository Pattern:** BleRepository abstracts Room DAO
- **Foreground Service:** BleScanService for background BLE scanning
- **Singleton DI:** BleApplication holds database/repository instances

## Project Structure
```
app/src/main/java/com/increxa/blescanner/
  ble/
    BleScanner.kt         # Core scanning logic, brand detection, RSSI processing
    BleScanService.kt     # Foreground service lifecycle
  data/
    BleDao.kt             # Room DAO queries
    BleDatabase.kt        # Room database definition
    BleEntities.kt        # Entity models (Session, Device, ScanResult)
    BleRepository.kt      # Repository abstraction
  export/
    DataExporter.kt       # CSV export
  ui/
    BleViewModel.kt       # UI state management
    MainScreen.kt         # Compose UI
  MainActivity.kt         # Entry point
  BleApplication.kt       # Application class
```

## Key Technical Details
- RSSI threshold: -62 dBm (~1.5m open air)
- Min detections per device: 2
- RSSI smoothing: last 5 readings average
- Distance model: log-distance path loss (exponent 2.5)
- Stationary detection: 20+ min with RSSI stddev < 4
- Scan batch interval: 3 seconds
- Thread safety via synchronized locks on shared maps

## Conventions
- UI strings in Spanish (target market: Argentina/LATAM)
- Package: com.increxa.blescanner
- Version: 1.0-mvp
- CSV export with 13 columns for analytics

## Build Commands
```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Run lint
./gradlew lint

# Run tests
./gradlew test
```

## Custom Skills Available
- `/android-review` - Android/Kotlin code review with BLE-specific checks
- `/ble-debug` - BLE scanning troubleshooting assistant
- `/android-test` - Generate tests for Android components
- `/build-apk` - Build, lint, and prepare APK for deployment
