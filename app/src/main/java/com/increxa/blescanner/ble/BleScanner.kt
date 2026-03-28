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
import kotlin.math.abs
import kotlin.math.pow

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val DEFAULT_TX_POWER = -59
        const val BATCH_INTERVAL_MS = 3000L
        // RSSI threshold: -70 dBm ≈ 2-3 meters in open space
        const val RSSI_THRESHOLD = -70

        // Known non-phone manufacturer IDs to exclude
        private val IOT_MANUFACTURER_IDS = setOf(
            0x0059, // Nordic Semiconductor (beacons, sensors)
            0x004D, // Logitech
            0x0157, // Govee
            0x0822, // Govee alt
        )

        // Manufacturer IDs that indicate phones/tablets
        private val PHONE_MANUFACTURER_IDS = setOf(
            0x004C, // Apple (iPhone, iPad)
            0x0075, // Samsung
            0x00E0, // Google (Pixel)
            0x010F, // Xiaomi
            0x0157, // Huawei — some overlap, but mostly phones
            0x001D, // Qualcomm (many Android OEMs)
            0x0046, // Sony
            0x038F, // Motorola
            0x0006, // Microsoft (Surface)
            0x0087, // Garmin — wearable but keep
            0x02D5, // Oppo
            0x0310, // Realme
            0x03DA, // OnePlus
        )

        // Name patterns that indicate NOT a phone
        private val NON_PHONE_NAME_PATTERNS = listOf(
            "tv", "[tv]", "series", "frame", "serif", "cu8000", "q60",
            "jbl", "bose", "buds", "airpod", "beats", "earbuds", "speaker",
            "soundlink", "soundbar", "headphone",
            "govee", "hue", "bulb", "light", "lamp",
            "watch", "band", "fit", "ring",
            "washer", "dryer", "fridge", "oven", "a/c", "ac ", "room a",
            "tile", "airtag", "smarttag", "tracker",
            "printer", "epson", "canon", "hp ",
            "mouse", "keyboard", "logitech", "mx master",
            "car", "obd", "fmb", "gps",
            "comedor", "living", "cocina", "dormitorio",
            "net", "router", "mesh", "extender", "wifi",
            "odyssey", "monitor",
        )

        // OUI prefixes (first 3 bytes of MAC) for known phone manufacturers
        // Only works for non-randomized MACs (real device MACs)
        private val OUI_BRANDS = mapOf(
            "3C:4F:06" to "Samsung",
            "BC:7E:8B" to "Samsung",
            "A0:D7:F3" to "Samsung",
            "D0:03:DF" to "Samsung",
            "98:06:3C" to "Samsung",
            "1C:AF:4A" to "Samsung",
            "BC:45:5B" to "Samsung",
            "A0:D0:5B" to "Samsung",
            "1C:E8:9E" to "Samsung",
            "C8:79:F7" to "Samsung",
            "64:E7:D8" to "Samsung",
            "B0:52:16" to "Samsung",
            "68:FC:CA" to "Samsung",
            "E8:AA:CB" to "Samsung",
            "F8:17:2D" to "Samsung",
            "54:B8:74" to "Samsung",
            "78:6D:67" to "Bose",
            "20:64:DE" to "IoT",
            "BC:10:2F" to "IoT",
            "EC:E8:80" to "Teltonika",
        )
    }

    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val scanBuffer = mutableMapOf<String, ScanData>()
    private val bufferLock = Any()

    var onScanError: ((Int) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val macAddress = device.address ?: return
            val rssi = result.rssi

            // Filter 1: RSSI threshold (distance ~2-3m)
            if (rssi < RSSI_THRESHOLD) return

            val deviceName = try { device.name } catch (_: SecurityException) { null }
            val record = result.scanRecord

            // Detect brand from manufacturer data or MAC OUI
            val brand = detectBrand(result)

            // Detect device category
            val category = categorizeDevice(result, deviceName, brand)

            // Filter 2: Only phones and tablets
            if (category != "phone" && category != "tablet") return

            val txPower = record?.txPowerLevel ?: DEFAULT_TX_POWER
            val effectiveTxPower = if (txPower == Int.MIN_VALUE) DEFAULT_TX_POWER else txPower
            val distance = estimateDistance(rssi, effectiveTxPower)

            synchronized(bufferLock) {
                scanBuffer[macAddress] = ScanData(
                    macAddress = macAddress,
                    deviceName = deviceName,
                    deviceType = category,
                    brand = brand,
                    rssi = rssi,
                    estimatedDistanceM = distance,
                    txPower = effectiveTxPower
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

        scanner = adapter.bluetoothLeScanner ?: return false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
        Log.i(TAG, "BLE scan started (phones only, RSSI >= $RSSI_THRESHOLD)")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
    }

    fun isScanning(): Boolean = isScanning

    fun flushBuffer(): List<ScanData> {
        synchronized(bufferLock) {
            val results = scanBuffer.values.toList()
            scanBuffer.clear()
            return results
        }
    }

    private fun estimateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0 || txPower == 0) return -1.0
        val ratio = rssi.toDouble() / txPower
        val distance = if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            0.89976 * ratio.pow(7.7095) + 0.111
        }
        // Clamp to reasonable range
        return distance.coerceIn(0.1, 30.0)
    }

    private fun detectBrand(result: ScanResult): String {
        val record = result.scanRecord
        val mac = result.device.address

        // 1. Try manufacturer specific data (most reliable)
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                when (mfgId) {
                    0x004C -> return "Apple"
                    0x0075 -> return "Samsung"
                    0x00E0 -> return "Google"
                    0x010F -> return "Xiaomi"
                    0x02D5 -> return "Oppo"
                    0x0310 -> return "Realme"
                    0x03DA -> return "OnePlus"
                    0x038F -> return "Motorola"
                    0x0046 -> return "Sony"
                    0x001D -> return "Qualcomm/Android"
                    0x0006 -> return "Microsoft"
                }
            }
        }

        // 2. Try MAC OUI prefix (only non-randomized MACs)
        val oui = mac.take(8).uppercase()
        OUI_BRANDS[oui]?.let { return it }

        // 3. Try device name
        val name = try { result.device.name?.lowercase() ?: "" } catch (_: SecurityException) { "" }
        return when {
            name.contains("iphone") || name.contains("ipad") -> "Apple"
            name.contains("galaxy") || name.contains("samsung") -> "Samsung"
            name.contains("pixel") -> "Google"
            name.contains("huawei") || name.contains("honor") -> "Huawei"
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") -> "Xiaomi"
            name.contains("motorola") || name.contains("moto ") -> "Motorola"
            name.contains("oppo") -> "Oppo"
            name.contains("realme") -> "Realme"
            name.contains("oneplus") -> "OnePlus"
            name.contains("sony") || name.contains("xperia") -> "Sony"
            name.contains("lg ") -> "LG"
            name.contains("nokia") -> "Nokia"
            name.contains("tab ") || name.contains("tablet") -> "Samsung" // likely Samsung tab
            else -> "Desconocida"
        }
    }

    private fun categorizeDevice(result: ScanResult, name: String?, brand: String): String {
        val lowerName = name?.lowercase() ?: ""

        // Exclude known non-phone devices by name
        for (pattern in NON_PHONE_NAME_PATTERNS) {
            if (lowerName.contains(pattern)) return "other"
        }

        // Exclude by brand detection
        if (brand == "Bose" || brand == "IoT" || brand == "Teltonika") return "other"

        // Check manufacturer data for known IoT IDs
        val record = result.scanRecord
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                if (mfgId in IOT_MANUFACTURER_IDS) return "other"
                if (mfgId in PHONE_MANUFACTURER_IDS) {
                    // Check if it's a tablet based on name
                    if (lowerName.contains("tab") || lowerName.contains("ipad")) return "tablet"
                    return "phone"
                }
            }
        }

        // MAC analysis: randomized MACs (second hex char is odd: x2, x6, xA, xE)
        // indicate a phone doing BLE scanning (typical phone behavior)
        val mac = result.device.address
        val secondChar = mac[1].digitToIntOrNull(16) ?: 0
        val isRandomized = (secondChar and 0x02) != 0

        // Randomized MAC + no name + reasonable RSSI = likely a phone
        if (isRandomized && lowerName.isEmpty()) return "phone"

        // Named device with phone brand = phone
        if (brand in listOf("Apple", "Samsung", "Google", "Xiaomi", "Oppo",
                "Realme", "OnePlus", "Motorola", "Huawei", "Sony", "Nokia", "LG")) {
            if (lowerName.contains("tab") || lowerName.contains("ipad")) return "tablet"
            return "phone"
        }

        // If we can't determine, but it has a randomized MAC, assume phone
        if (isRandomized) return "phone"

        return "other"
    }
}
