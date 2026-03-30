package com.increxa.blescanner.data

import kotlinx.coroutines.flow.Flow

class BleRepository(private val dao: BleDao) {

    companion object {
        const val ACTIVE_THRESHOLD_MS = 30_000L
        const val GONE_THRESHOLD_MS = 60_000L
    }

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
    fun getAvgConfidence(): Flow<Int?> = dao.getAvgConfidence()
    fun getAppleDeviceCount(): Flow<Int> = dao.getAppleDeviceCount()
    fun getAllSessions(): Flow<List<BleSession>> = dao.getAllSessions()

    suspend fun startSession(sessionId: String, lat: Double?, lng: Double?, notes: String? = null) {
        dao.insertSession(
            BleSession(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                locationLat = lat,
                locationLng = lng,
                notes = notes
            )
        )
    }

    suspend fun endSession(sessionId: String) {
        dao.endSession(sessionId, System.currentTimeMillis())
        devicePresenceStart.clear()
    }

    suspend fun recordScanBatch(results: List<ScanData>, sessionId: String) {
        val now = System.currentTimeMillis()

        for (scan in results) {
            dao.insertDeviceIfNew(
                BleDevice(
                    macAddress = scan.macAddress,
                    deviceName = scan.deviceName,
                    deviceType = scan.deviceType,
                    brand = scan.brand,
                    model = scan.model,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    totalDurationMs = 0,
                    scanCount = 1,
                    avgRssi = scan.avgRssi,
                    confidenceScore = scan.confidenceScore,
                    isStationary = scan.isStationary
                )
            )
            dao.updateDeviceSeen(
                macAddress = scan.macAddress,
                lastSeenAt = now,
                deviceName = scan.deviceName,
                brand = scan.brand,
                model = scan.model,
                avgRssi = scan.avgRssi,
                confidenceScore = scan.confidenceScore,
                isStationary = scan.isStationary
            )

            val presenceStart = devicePresenceStart[scan.macAddress]
            if (presenceStart != null) {
                val elapsed = now - presenceStart
                if (elapsed > GONE_THRESHOLD_MS) {
                    devicePresenceStart[scan.macAddress] = now
                } else {
                    dao.addDeviceDuration(scan.macAddress, elapsed)
                    devicePresenceStart[scan.macAddress] = now
                }
            } else {
                devicePresenceStart[scan.macAddress] = now
            }
        }

        val scanResults = results.map { scan ->
            BleScanResult(
                macAddress = scan.macAddress,
                deviceName = scan.deviceName,
                rssi = scan.rssi,
                estimatedDistanceM = scan.estimatedDistanceM,
                txPower = scan.txPower,
                timestamp = now,
                sessionId = sessionId,
                model = scan.model
            )
        }
        if (scanResults.isNotEmpty()) {
            dao.insertScanResults(scanResults)
        }
    }

    suspend fun getExportData(sessionId: String? = null): List<BleScanResult> {
        return if (sessionId != null) dao.getExportDataForSession(sessionId)
        else dao.getAllExportData()
    }

    suspend fun getDevicesForExport(): List<BleDevice> = dao.getAllDevicesForExport()

    suspend fun getSessionForExport(sessionId: String): BleSession? = dao.getSessionById(sessionId)

    suspend fun getLatestSession(): BleSession? = dao.getLatestSession()

    suspend fun cleanup(olderThanDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        dao.deleteScanResultsBefore(cutoff)
        dao.deleteDevicesBefore(cutoff)
    }
}
