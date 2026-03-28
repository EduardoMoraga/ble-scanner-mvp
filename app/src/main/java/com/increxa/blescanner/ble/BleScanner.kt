package com.increxa.blescanner.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.increxa.blescanner.data.ScanData
import kotlin.math.pow

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val DEFAULT_TX_POWER = -59
        const val BATCH_INTERVAL_MS = 3000L
        const val RSSI_THRESHOLD = -70

        // === MANUFACTURER IDs ===
        // Source: Bluetooth SIG assigned numbers
        private val BRAND_BY_MFG_ID = mapOf(
            0x004C to "Apple",
            0x0075 to "Samsung",
            0x00E0 to "Google",
            0x010F to "Xiaomi",
            0x0157 to "Huawei",
            0x001D to "Qualcomm",    // Many Android OEMs use Qualcomm's ID
            0x0046 to "Sony",
            0x038F to "Motorola",
            0x0006 to "Microsoft",
            0x02D5 to "Oppo",
            0x0310 to "Realme",
            0x03DA to "OnePlus",
            0x000F to "Broadcom",    // Some Android phones
            0x0059 to "Nordic",      // Beacons/IoT — will be filtered by category
            0x004D to "Logitech",    // Peripherals — filtered
            0x0078 to "Nike",
            0x0087 to "Garmin",
            0x0301 to "Vivo",
            0x0171 to "Amazon",      // Fire phones/tablets
            0x0131 to "TCL",
            0x02E5 to "Transsion",   // Tecno, Infinix, Itel
            0x0386 to "Nothing",
            0x039A to "ZTE",
            0x01B0 to "Lenovo",
            0x0002 to "Intel",       // Some laptops/tablets
        )

        // Manufacturer IDs that are definitively NOT phones
        private val IOT_MFG_IDS = setOf(
            0x0059, // Nordic Semiconductor (beacons, sensors)
            0x004D, // Logitech (mouse, keyboard)
            0x0822, // Govee
            0x0157, // Govee alt — note: also Huawei, handled in categorize
            0x0078, // Nike (shoes/bands)
            0x0087, // Garmin (watches)
            0x000D, // Texas Instruments (IoT sensors)
        )

        // === BLE Service UUIDs that indicate phone activity ===
        // Google Fast Pair service
        private val GOOGLE_FAST_PAIR_UUID = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
        // Google Nearby Share / Quick Share
        private val GOOGLE_NEARBY_UUID = ParcelUuid.fromString("0000FEF3-0000-1000-8000-00805F9B34FB")
        // Microsoft Swift Pair
        private val MS_SWIFT_PAIR_UUID = ParcelUuid.fromString("0000FE07-0000-1000-8000-00805F9B34FB")
        // Samsung SmartThings / Galaxy ecosystem
        private val SAMSUNG_UUID = ParcelUuid.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")
        // Apple continuity (Handoff, AirDrop, etc.)
        private val APPLE_CONTINUITY_UUID = ParcelUuid.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")
        // Xiaomi MiShare
        private val XIAOMI_UUID = ParcelUuid.fromString("0000FE95-0000-1000-8000-00805F9B34FB")

        // Map UUID → brand
        private val BRAND_BY_SERVICE_UUID = mapOf(
            GOOGLE_FAST_PAIR_UUID to "Google/Android",
            GOOGLE_NEARBY_UUID to "Google/Android",
            MS_SWIFT_PAIR_UUID to "Microsoft",
            SAMSUNG_UUID to "Samsung",
            APPLE_CONTINUITY_UUID to "Apple",
            XIAOMI_UUID to "Xiaomi",
        )

        // === Apple device type detection from Continuity bytes ===
        // Apple manufacturer data byte[1] = device type
        private val APPLE_DEVICE_TYPES = mapOf(
            0x01 to "iPhone",
            0x02 to "iPhone",
            0x03 to "iPad",
            0x04 to "MacBook",
            0x05 to "Apple Watch",
            0x06 to "Apple TV",
            0x07 to "iPhone",
            0x09 to "MacBook",
            0x0A to "Apple Watch",
            0x0B to "MacBook",
            0x0C to "MacBook",
            0x0E to "iPhone",
            0x0F to "iPhone",
            0x10 to "iPhone",
            0x14 to "AirPods",
        )

        // Name patterns that indicate NOT a phone
        private val NON_PHONE_NAME_PATTERNS = listOf(
            "tv", "[tv]", "series", "frame", "serif", "cu8000", "q60", "odyssey",
            "jbl", "bose", "buds", "airpod", "beats", "earbuds", "speaker",
            "soundlink", "soundbar", "headphone", "airdots",
            "govee", "hue", "bulb", "light", "lamp", "yeelight",
            "watch", "band", "fit", "ring", "mi band", "galaxy watch",
            "washer", "dryer", "fridge", "oven", "a/c", "ac ", "room a",
            "tile", "airtag", "smarttag", "tracker",
            "printer", "epson", "canon", "hp ",
            "mouse", "keyboard", "logitech", "mx master",
            "car", "obd", "fmb", "gps", "dashcam",
            "comedor", "living", "cocina", "dormitorio",
            "net", "router", "mesh", "extender", "wifi",
            "monitor", "projector",
            "scale", "toothbrush", "thermometer",
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

            if (rssi < RSSI_THRESHOLD) return

            val deviceName = try { device.name } catch (_: SecurityException) { null }
            val record = result.scanRecord

            // Multi-layer brand detection
            val brandResult = detectBrand(result)
            val brand = brandResult.first
            val model = brandResult.second

            val category = categorizeDevice(result, deviceName, brand)
            if (category != "phone" && category != "tablet") return

            val txPower = record?.txPowerLevel ?: DEFAULT_TX_POWER
            val effectiveTxPower = if (txPower == Int.MIN_VALUE) DEFAULT_TX_POWER else txPower
            val distance = estimateDistance(rssi, effectiveTxPower)

            // Use model as device name if we detected one and there's no broadcast name
            val displayName = deviceName ?: model

            synchronized(bufferLock) {
                scanBuffer[macAddress] = ScanData(
                    macAddress = macAddress,
                    deviceName = displayName,
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
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning = false
            onScanError?.invoke(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        if (isScanning) return true
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return false
        scanner = adapter.bluetoothLeScanner ?: return false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
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
        val distance = if (ratio < 1.0) ratio.pow(10.0)
        else 0.89976 * ratio.pow(7.7095) + 0.111
        return distance.coerceIn(0.1, 30.0)
    }

    /**
     * Multi-layer brand detection. Returns Pair(brand, model?).
     *
     * Layer 1: Manufacturer specific data (most reliable for Samsung, Apple)
     * Layer 2: BLE service UUIDs (catches Google/Android phones doing Fast Pair/Nearby)
     * Layer 3: Apple continuity bytes (iPhone vs iPad vs MacBook)
     * Layer 4: Device name keywords
     * Layer 5: Randomized MAC heuristic — classify as "Android" (generic)
     */
    private fun detectBrand(result: ScanResult): Pair<String, String?> {
        val record = result.scanRecord
        val mac = result.device.address
        val name = try { result.device.name?.lowercase() ?: "" } catch (_: SecurityException) { "" }

        // Layer 1: Manufacturer specific data
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                val mfgBytes = mfgData.valueAt(0)

                // Special: Apple — extract device type from continuity protocol
                if (mfgId == 0x004C && mfgBytes != null && mfgBytes.size >= 2) {
                    val deviceTypeByte = mfgBytes[0].toInt() and 0xFF
                    val appleModel = APPLE_DEVICE_TYPES[deviceTypeByte]
                    return Pair("Apple", appleModel)
                }

                // All other manufacturer IDs
                val brand = BRAND_BY_MFG_ID[mfgId]
                if (brand != null) return Pair(brand, null)

                // Unknown manufacturer ID but has data — likely a real device
                return Pair("MFG:${String.format("%04X", mfgId)}", null)
            }
        }

        // Layer 2: Service UUIDs
        if (record != null) {
            val serviceUuids = record.serviceUuids
            if (serviceUuids != null) {
                for ((uuid, brand) in BRAND_BY_SERVICE_UUID) {
                    if (serviceUuids.contains(uuid)) {
                        return Pair(brand, null)
                    }
                }
                // Has service UUIDs but unknown — still likely a phone/app
                if (serviceUuids.isNotEmpty()) {
                    return Pair("Android", null)
                }
            }

            // Also check solicited UUIDs (Android Nearby uses these)
            val solicitedUuids = record.serviceSolicitationUuids
            if (solicitedUuids != null && solicitedUuids.isNotEmpty()) {
                return Pair("Android", null)
            }
        }

        // Layer 3: Device name keywords
        if (name.isNotEmpty()) {
            return when {
                name.contains("iphone") || name.contains("ipad") -> Pair("Apple", name)
                name.contains("galaxy") || name.contains("samsung") -> Pair("Samsung", name)
                name.contains("pixel") -> Pair("Google", name)
                name.contains("huawei") || name.contains("honor") -> Pair("Huawei", name)
                name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") -> Pair("Xiaomi", name)
                name.contains("motorola") || name.contains("moto g") || name.contains("moto e") -> Pair("Motorola", name)
                name.contains("oppo") -> Pair("Oppo", name)
                name.contains("realme") -> Pair("Realme", name)
                name.contains("oneplus") -> Pair("OnePlus", name)
                name.contains("vivo") -> Pair("Vivo", name)
                name.contains("nokia") -> Pair("Nokia", name)
                name.contains("tcl") -> Pair("TCL", name)
                name.contains("zte") -> Pair("ZTE", name)
                name.contains("nothing") -> Pair("Nothing", name)
                name.contains("tecno") || name.contains("infinix") || name.contains("itel") -> Pair("Transsion", name)
                name.contains("lg ") || name.contains("lg-") -> Pair("LG", name)
                name.contains("sony") || name.contains("xperia") -> Pair("Sony", name)
                name.contains("tab ") -> Pair("Samsung", name)
                name.contains("surface") -> Pair("Microsoft", name)
                else -> Pair("Android", name) // Has a name, can't identify brand
            }
        }

        // Layer 4: Randomized MAC + no manufacturer data + no service UUIDs
        // This is a phone doing standard BLE scanning without advertising features.
        // Most modern Android phones do this. We know it's a phone (passed category filter)
        // but can't determine brand.
        val secondChar = mac[1].digitToIntOrNull(16) ?: 0
        val isRandomized = (secondChar and 0x02) != 0

        if (isRandomized) {
            // Check if there's ANY data in the advertisement that hints at Android
            if (record != null && record.bytes != null && record.bytes.size > 10) {
                return Pair("Android", null)
            }
            return Pair("Desconocida", null)
        }

        return Pair("Desconocida", null)
    }

    private fun categorizeDevice(result: ScanResult, name: String?, brand: String): String {
        val lowerName = name?.lowercase() ?: ""

        // Exclude by name patterns
        for (pattern in NON_PHONE_NAME_PATTERNS) {
            if (lowerName.contains(pattern)) return "other"
        }

        // Exclude known non-phone brands detected by manufacturer ID
        if (brand == "Nordic" || brand == "Logitech" || brand == "Garmin" || brand == "Nike") return "other"

        // Check manufacturer data for IoT
        val record = result.scanRecord
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                if (mfgId in IOT_MFG_IDS) return "other"
            }
        }

        // Tablet detection
        if (lowerName.contains("tab") || lowerName.contains("ipad") || lowerName.contains("tablet")) {
            return "tablet"
        }

        // Apple non-phone detection
        if (brand == "Apple") {
            val mfgData = record?.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0 && mfgData.keyAt(0) == 0x004C) {
                val bytes = mfgData.valueAt(0)
                if (bytes != null && bytes.isNotEmpty()) {
                    val typeByte = bytes[0].toInt() and 0xFF
                    val model = APPLE_DEVICE_TYPES[typeByte]
                    if (model != null) {
                        return when {
                            model.contains("Watch") || model.contains("AirPods") || model.contains("TV") -> "other"
                            model.contains("MacBook") -> "other"
                            model.contains("iPad") -> "tablet"
                            else -> "phone" // iPhone
                        }
                    }
                }
            }
            return "phone" // Default Apple = iPhone
        }

        // Randomized MAC = likely phone
        val mac = result.device.address
        val secondChar = mac[1].digitToIntOrNull(16) ?: 0
        val isRandomized = (secondChar and 0x02) != 0
        if (isRandomized) return "phone"

        // Known phone brands
        if (brand in listOf("Samsung", "Google", "Xiaomi", "Oppo", "Realme", "OnePlus",
                "Motorola", "Huawei", "Sony", "Nokia", "LG", "Vivo", "TCL", "ZTE",
                "Nothing", "Transsion", "Lenovo", "Android", "Google/Android",
                "Qualcomm", "Microsoft", "Broadcom", "Intel")) {
            return "phone"
        }

        if (isRandomized) return "phone"
        return "other"
    }
}
