package com.increxa.blescanner.data

import kotlinx.coroutines.flow.Flow

class BleRepository(private val dao: BleDao) {

    companion object {
        // Device considered "active" if seen in last 30 seconds
        const val ACTIVE_THRESHOLD_MS = 30_000L
        // Device considered "gone" if not seen for 60 seconds
        const val GONE_THRESHOLD_MS = 60_000L
    }

    // Track when each device was last "seen" for permanence calculation
    private val devicePresenceStart = mutableMapOf<String, Long>()

    fun getActiveDevices(): Flow<List<BleDevice>> {
        val since = System.currentTimeMillis() - ACTIVE_THRESHOLD_MS
        return dao.getActiveDevices(since)
    }

    fun getActiveDeviceCount(): Flow<Int> {
        val since = System.currentTimeMillis() - ACTIVE_THRESHOLD_MS
        return dao.getActiveDeviceCount(since)
    }

    fun getAllDevices(): Flow<List<BleDevice>> = dao.getAllDevices()

    fun getTotalUniqueDevices(): Flow<Int> = dao.getTotalUniqueDevices()

    fun getAvgDwellTimeMs(): Flow<Long?> = dao.getAvgDwellTimeMs()

    fun getTotalScanResults(): Flow<Int> = dao.getTotalScanResults()

    fun getAllSessions(): Flow<List<BleSession>> = dao.getAllSessions()

    suspend fun startSession(sessionId: String, notes: String? = null) {
        dao.insertSession(
            BleSession(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                notes = notes
            )
        )
    }

    suspend fun endSession(sessionId: String) {
        dao.endSession(sessionId, System.currentTimeMillis())
        // Flush permanence for all tracked devices
        flushAllPermanence()
    }

    suspend fun recordScanBatch(
        results: List<ScanData>,
        sessionId: String
    ) {
        val now = System.currentTimeMillis()

        for (scan in results) {
            // Upsert device
            dao.insertDeviceIfNew(
                BleDevice(
                    macAddress = scan.macAddress,
                    deviceName = scan.deviceName,
                    deviceType = scan.deviceType,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    totalDurationMs = 0,
                    scanCount = 1
                )
            )
            dao.updateDeviceSeen(scan.macAddress, now, scan.deviceName)

            // Track permanence: if device was already present, accumulate
            val presenceStart = devicePresenceStart[scan.macAddress]
            if (presenceStart != null) {
                val elapsed = now - presenceStart
                if (elapsed > GONE_THRESHOLD_MS) {
                    // Device was gone and came back — start new permanence window
                    devicePresenceStart[scan.macAddress] = now
                } else {
                    dao.addDeviceDuration(scan.macAddress, elapsed)
                    devicePresenceStart[scan.macAddress] = now
                }
            } else {
                devicePresenceStart[scan.macAddress] = now
            }
        }

        // Batch insert scan results
        val scanResults = results.map { scan ->
            BleScanResult(
                macAddress = scan.macAddress,
                deviceName = scan.deviceName,
                rssi = scan.rssi,
                estimatedDistanceM = scan.estimatedDistanceM,
                txPower = scan.txPower,
                timestamp = now,
                sessionId = sessionId
            )
        }
        if (scanResults.isNotEmpty()) {
            dao.insertScanResults(scanResults)
        }
    }

    private suspend fun flushAllPermanence() {
        devicePresenceStart.clear()
    }

    suspend fun getExportData(sessionId: String? = null): List<BleScanResult> {
        return if (sessionId != null) {
            dao.getExportDataForSession(sessionId)
        } else {
            dao.getAllExportData()
        }
    }

    suspend fun cleanup(olderThanDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        dao.deleteScanResultsBefore(cutoff)
        dao.deleteDevicesBefore(cutoff)
    }
}

data class ScanData(
    val macAddress: String,
    val deviceName: String?,
    val deviceType: String?,
    val rssi: Int,
    val estimatedDistanceM: Double,
    val txPower: Int?
)
