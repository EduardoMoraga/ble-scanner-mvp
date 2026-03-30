---
name: android-review
description: Android/Kotlin code review optimized for BLE Scanner project. Checks for BLE best practices, Compose patterns, Room usage, coroutine safety, and Android-specific pitfalls.
user_invocable: true
---

# Android & BLE Code Review

You are reviewing Kotlin code for an Android BLE Scanner app (Jetpack Compose + Room + Coroutines). Perform a thorough code review focusing on these areas:

## What to Review

1. **Read all changed files** using `git diff` or read the files the user specifies.

2. **BLE-Specific Checks:**
   - Scan lifecycle: are scans properly started/stopped? Is `stopScan()` always called?
   - RSSI handling: are values validated (typical range -30 to -100 dBm)?
   - Bluetooth permissions: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
   - ScanCallback error handling (SCAN_FAILED_* codes)
   - Battery impact: scan mode (LOW_LATENCY vs LOW_POWER) appropriate for use case?
   - Thread safety: concurrent access to device maps, RSSI buffers
   - MAC address randomization handling

3. **Android Lifecycle:**
   - Foreground service lifecycle (startForeground called within 5s?)
   - Activity/Fragment lifecycle leaks
   - ViewModel scope correctness
   - Context leaks (storing Activity context in long-lived objects)

4. **Jetpack Compose:**
   - Recomposition performance (unnecessary recompositions?)
   - State hoisting correctness
   - Side effects (LaunchedEffect, DisposableEffect) cleanup
   - remember/rememberSaveable usage

5. **Room Database:**
   - Queries on background thread (not main thread)
   - Missing indices on frequently queried columns
   - Migration strategy for schema changes
   - Transaction usage for multi-table operations

6. **Kotlin & Coroutines:**
   - Coroutine scope management (viewModelScope, lifecycleScope)
   - Exception handling in coroutines
   - Flow collection lifecycle awareness
   - Null safety and smart casts

7. **Security & Permissions:**
   - Runtime permission checks before BLE operations
   - No sensitive data in logs (MAC addresses in production)
   - ProGuard/R8 rules for release builds

## Output Format

For each issue found, report:
- **File:Line** - location
- **Severity** - Critical / Warning / Suggestion
- **Issue** - what's wrong
- **Fix** - how to fix it, with code snippet if helpful

End with a summary: X critical, Y warnings, Z suggestions.
