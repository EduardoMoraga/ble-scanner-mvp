package com.increxa.blescanner.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.increxa.blescanner.data.ScanData
import kotlin.math.pow

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        // Default TX power at 1 meter (dBm) — typical BLE device
        private const val DEFAULT_TX_POWER = -59
        // How often to batch and report results (ms)
        const val BATCH_INTERVAL_MS = 3000L
    }

    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    // Buffer to collect scan results between batch intervals
    private val scanBuffer = mutableMapOf<String, ScanData>()
    private val bufferLock = Any()

    var onBatchReady: ((List<ScanData>) -> Unit)? = null
    var onScanError: ((Int) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val macAddress = device.address ?: return
            val deviceName = try { device.name } catch (_: SecurityException) { null }
            val rssi = result.rssi
            val txPower = result.scanRecord?.txPowerLevel ?: DEFAULT_TX_POWER
            val distance = estimateDistance(rssi, txPower)
            val deviceType = categorizeDevice(result)

            synchronized(bufferLock) {
                // Keep the most recent reading for each MAC
                scanBuffer[macAddress] = ScanData(
                    macAddress = macAddress,
                    deviceName = deviceName,
                    deviceType = deviceType,
                    rssi = rssi,
                    estimatedDistanceM = distance,
                    txPower = txPower
                )
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false
            onScanError?.invoke(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        if (isScanning) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return false
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            return false
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0) // Immediate callbacks
            .build()

        // No filters = scan for ALL BLE devices
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
        Log.i(TAG, "BLE scan started")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
        isScanning = false
        Log.i(TAG, "BLE scan stopped")
    }

    fun isScanning(): Boolean = isScanning

    /**
     * Flush the buffer and return all accumulated scan results since last flush.
     * Called by the service on a timer.
     */
    fun flushBuffer(): List<ScanData> {
        synchronized(bufferLock) {
            val results = scanBuffer.values.toList()
            scanBuffer.clear()
            return results
        }
    }

    /**
     * Estimate distance from RSSI using log-distance path loss model.
     * Returns distance in meters. Accuracy: rough zone (~1m, 1-3m, 3-10m).
     */
    private fun estimateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) return -1.0
        val ratio = rssi.toDouble() / txPower
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            0.89976 * ratio.pow(7.7095) + 0.111
        }
    }

    /**
     * Categorize device based on BLE advertisement data.
     */
    private fun categorizeDevice(result: ScanResult): String {
        val record = result.scanRecord ?: return "Unknown"

        // Check device name for hints
        val name = try { result.device.name?.lowercase() ?: "" } catch (_: SecurityException) { "" }
        return when {
            name.contains("iphone") || name.contains("ipad") -> "Apple"
            name.contains("galaxy") || name.contains("samsung") -> "Samsung"
            name.contains("pixel") -> "Google"
            name.contains("huawei") || name.contains("honor") -> "Huawei"
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") -> "Xiaomi"
            name.contains("motorola") || name.contains("moto") -> "Motorola"
            name.contains("airpod") || name.contains("beats") -> "Apple Audio"
            name.contains("watch") || name.contains("band") || name.contains("fit") -> "Wearable"
            name.contains("buds") || name.contains("earbuds") || name.contains("jbl") -> "Audio"
            name.contains("tile") || name.contains("airtag") || name.contains("smarttag") -> "Tracker"
            name.isNotEmpty() -> "Named: ${result.device.name}"
            else -> {
                // Try to categorize by manufacturer data
                val mfgData = record.manufacturerSpecificData
                if (mfgData != null && mfgData.size() > 0) {
                    val mfgId = mfgData.keyAt(0)
                    when (mfgId) {
                        0x004C -> "Apple"         // Apple Inc.
                        0x0075 -> "Samsung"       // Samsung
                        0x0006 -> "Microsoft"     // Microsoft
                        0x00E0 -> "Google"        // Google
                        0x010F -> "Xiaomi"        // Xiaomi
                        else -> "MFG:${String.format("%04X", mfgId)}"
                    }
                } else {
                    "Unknown"
                }
            }
        }
    }
}
