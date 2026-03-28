package com.increxa.blescanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BleDao {

    // === Sessions ===
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: BleSession)

    @Query("UPDATE ble_sessions SET endTime = :endTime WHERE sessionId = :sessionId")
    suspend fun endSession(sessionId: String, endTime: Long)

    @Query("SELECT * FROM ble_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<BleSession>>

    // === Devices ===
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeviceIfNew(device: BleDevice)

    @Query("""
        UPDATE ble_devices SET
            lastSeenAt = :lastSeenAt,
            scanCount = scanCount + 1,
            deviceName = COALESCE(:deviceName, deviceName)
        WHERE macAddress = :macAddress
    """)
    suspend fun updateDeviceSeen(macAddress: String, lastSeenAt: Long, deviceName: String?)

    @Query("""
        UPDATE ble_devices SET
            totalDurationMs = totalDurationMs + :additionalMs
        WHERE macAddress = :macAddress
    """)
    suspend fun addDeviceDuration(macAddress: String, additionalMs: Long)

    @Query("SELECT * FROM ble_devices ORDER BY lastSeenAt DESC")
    fun getAllDevices(): Flow<List<BleDevice>>

    @Query("SELECT COUNT(*) FROM ble_devices WHERE lastSeenAt > :since")
    fun getActiveDeviceCount(since: Long): Flow<Int>

    @Query("SELECT * FROM ble_devices WHERE lastSeenAt > :since ORDER BY lastSeenAt DESC")
    fun getActiveDevices(since: Long): Flow<List<BleDevice>>

    // === Scan Results ===
    @Insert
    suspend fun insertScanResult(result: BleScanResult)

    @Insert
    suspend fun insertScanResults(results: List<BleScanResult>)

    @Query("SELECT * FROM ble_scan_results WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getScanResultsForSession(sessionId: String): Flow<List<BleScanResult>>

    @Query("SELECT COUNT(DISTINCT macAddress) FROM ble_scan_results WHERE sessionId = :sessionId")
    fun getUniqueDevicesInSession(sessionId: String): Flow<Int>

    // === Stats ===
    @Query("SELECT COUNT(DISTINCT macAddress) FROM ble_devices")
    fun getTotalUniqueDevices(): Flow<Int>

    @Query("SELECT AVG(totalDurationMs) FROM ble_devices WHERE totalDurationMs > 0")
    fun getAvgDwellTimeMs(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM ble_scan_results")
    fun getTotalScanResults(): Flow<Int>

    // === Export ===
    @Query("""
        SELECT r.*, d.deviceType
        FROM ble_scan_results r
        LEFT JOIN ble_devices d ON r.macAddress = d.macAddress
        WHERE r.sessionId = :sessionId
        ORDER BY r.timestamp ASC
    """)
    suspend fun getExportDataForSession(sessionId: String): List<BleScanResult>

    @Query("""
        SELECT r.*, d.deviceType
        FROM ble_scan_results r
        LEFT JOIN ble_devices d ON r.macAddress = d.macAddress
        ORDER BY r.timestamp ASC
    """)
    suspend fun getAllExportData(): List<BleScanResult>

    // === Cleanup ===
    @Query("DELETE FROM ble_scan_results WHERE timestamp < :before")
    suspend fun deleteScanResultsBefore(before: Long)

    @Query("DELETE FROM ble_devices WHERE lastSeenAt < :before")
    suspend fun deleteDevicesBefore(before: Long)
}
