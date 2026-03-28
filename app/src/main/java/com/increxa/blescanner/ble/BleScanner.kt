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
import kotlin.math.sqrt

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val DEFAULT_TX_POWER = -59
        const val BATCH_INTERVAL_MS = 3000L

        // ~1.5m in open air. Through a floor/ceiling, signal drops 10-15 dBm extra
        // so neighbor devices at -50 dBm would appear as -60 to -65, filtered out
        const val RSSI_THRESHOLD = -62

        // Device must be detected at least this many times before being reported
        const val MIN_DETECTIONS = 2

        // Max RSSI readings to keep per device (prevent unbounded memory)
        private const val MAX_RSSI_HISTORY = 200

        // Stationary detection thresholds
        const val STATIONARY_TIME_MS = 20 * 60 * 1000L  // 20 minutes
        const val STATIONARY_RSSI_STD_MAX = 4.0          // dBm

        // Indoor path-loss exponent for distance estimation
        private const val PATH_LOSS_N = 2.5

        // === MANUFACTURER IDs (Bluetooth SIG) ===
        private val BRAND_BY_MFG_ID = mapOf(
            0x004C to "Apple",
            0x0075 to "Samsung",
            0x00E0 to "Google",
            0x010F to "Xiaomi",
            0x0157 to "Huawei",
            0x001D to "Qualcomm",
            0x0046 to "Sony",
            0x038F to "Motorola",
            0x0006 to "Microsoft",
            0x02D5 to "Oppo",
            0x0310 to "Realme",
            0x03DA to "OnePlus",
            0x000F to "Broadcom",
            0x0301 to "Vivo",
            0x0171 to "Amazon",
            0x0131 to "TCL",
            0x02E5 to "Transsion",
            0x0386 to "Nothing",
            0x039A to "ZTE",
            0x01B0 to "Lenovo",
        )

        // Manufacturer IDs that are definitively NOT phones
        private val IOT_MFG_IDS = setOf(
            0x0059, // Nordic Semiconductor (beacons, sensors)
            0x004D, // Logitech (mouse, keyboard)
            0x0822, // Govee
            0x0078, // Nike (shoes/bands)
            0x0087, // Garmin (watches)
            0x000D, // Texas Instruments (IoT sensors)
            0x0002, // Intel (laptops)
        )

        // === BLE Service UUIDs ===

        // Google Fast Pair — ACCESSORIES broadcast this, not phones
        // If a device advertises this, it's earbuds/speaker/watch, not a phone
        private val GOOGLE_FAST_PAIR_UUID = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

        // Google Nearby Share / Quick Share — phones DO broadcast this
        private val GOOGLE_NEARBY_UUID = ParcelUuid.fromString("0000FEF3-0000-1000-8000-00805F9B34FB")

        // Samsung Galaxy ecosystem
        private val SAMSUNG_UUID = ParcelUuid.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")

        // Xiaomi MiShare
        private val XIAOMI_UUID = ParcelUuid.fromString("0000FE95-0000-1000-8000-00805F9B34FB")

        // Map UUID → brand (only phone-related UUIDs)
        private val PHONE_SERVICE_UUIDS = mapOf(
            GOOGLE_NEARBY_UUID to "Google/Android",
            SAMSUNG_UUID to "Samsung",
            XIAOMI_UUID to "Xiaomi",
        )

        // UUIDs that indicate this is an ACCESSORY, not a phone
        private val ACCESSORY_SERVICE_UUIDS = setOf(
            GOOGLE_FAST_PAIR_UUID,
        )

        // === Apple Nearby Info device types (lower nibble of status byte) ===
        // Source: reverse-engineering by Hexway (apple_bleee), Furiosa Security
        private val APPLE_NEARBY_DEVICE_TYPE = mapOf(
            0x01 to "MacBook",
            0x02 to "iPhone",
            0x03 to "iPad",
            0x04 to "Apple Watch",
            0x05 to "iPod Touch",
            0x06 to "Apple TV",
            0x07 to "HomePod",
            0x09 to "MacBook",
            0x0A to "Apple Watch",
            0x0B to "MacBook",
            0x0C to "MacBook",
            0x0E to "iMac",
        )

        // Name patterns that indicate NOT a phone
        private val NON_PHONE_NAME_PATTERNS = listOf(
            "tv", "[tv]", "series", "frame", "serif", "cu8000", "q60", "odyssey",
            "jbl", "bose", "buds", "airpod", "beats", "earbuds", "speaker",
            "soundlink", "soundbar", "headphone", "airdots", "pods",
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
            "tab ", "tab-", "ipad", "tablet", "surface",
            "macbook", "imac", "laptop",
        )
    }

    // === Per-device tracking for RSSI smoothing, confidence, and exhibition detection ===
    data class DeviceTracker(
        val firstSeen: Long,
        val rssiReadings: MutableList<Int> = mutableListOf(),
        var detectionCount: Int = 0,
        var lastBrand: String = "Desconocida",
        var lastModel: String? = null
    )

    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val scanBuffer = mutableMapOf<String, ScanData>()
    private val bufferLock = Any()
    private val deviceTrackers = mutableMapOf<String, DeviceTracker>()
    private val trackerLock = Any()
    var onScanError: ((Int) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { processResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning = false
            onScanError?.invoke(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processResult(result: ScanResult) {
        val device = result.device
        val macAddress = device.address ?: return
        val rssi = result.rssi
        val now = System.currentTimeMillis()

        // --- Step 1: Track this reading regardless of threshold ---
        val tracker = synchronized(trackerLock) {
            deviceTrackers.getOrPut(macAddress) {
                DeviceTracker(firstSeen = now)
            }.also { t ->
                t.rssiReadings.add(rssi)
                t.detectionCount++
                // Trim to prevent unbounded growth
                if (t.rssiReadings.size > MAX_RSSI_HISTORY) {
                    t.rssiReadings.removeAt(0)
                }
            }
        }

        // --- Step 2: RSSI smoothing — use average of last 5 readings ---
        val recentReadings = synchronized(trackerLock) {
            tracker.rssiReadings.takeLast(5)
        }
        val avgRssi = recentReadings.average().toInt()

        // Reject if smoothed RSSI below threshold (~1.5m)
        if (avgRssi < RSSI_THRESHOLD) return

        // --- Step 3: Minimum detection gate — must be seen 2+ times ---
        if (tracker.detectionCount < MIN_DETECTIONS) return

        // --- Step 4: Brand + Model detection ---
        val deviceName = try { device.name } catch (_: SecurityException) { null }
        val brandResult = detectBrand(result)
        val brand = brandResult.brand
        val model = brandResult.model

        // --- Step 5: Strict phone-only filter ---
        val isPhone = categorizeAsPhone(result, deviceName, brand, model)
        if (!isPhone) return

        // --- Step 6: Compute metrics ---
        val record = result.scanRecord
        val txPower = record?.txPowerLevel ?: DEFAULT_TX_POWER
        val effectiveTxPower = if (txPower == Int.MIN_VALUE) DEFAULT_TX_POWER else txPower
        val distance = estimateDistance(avgRssi, effectiveTxPower)

        val allReadings = synchronized(trackerLock) { tracker.rssiReadings.toList() }
        val rssiStdDev = stdDev(allReadings)

        val isStationary = allReadings.size >= 10 &&
                (now - tracker.firstSeen > STATIONARY_TIME_MS) &&
                rssiStdDev < STATIONARY_RSSI_STD_MAX

        val confidence = calculateConfidence(
            detectionCount = tracker.detectionCount,
            brandIdentified = brand != "Desconocida" && !brand.startsWith("MFG:"),
            modelIdentified = model != null,
            rssiStdDev = rssiStdDev
        )

        // Update tracker with latest brand/model
        synchronized(trackerLock) {
            if (brand != "Desconocida") tracker.lastBrand = brand
            if (model != null) tracker.lastModel = model
        }

        // Use best known brand/model (might have been identified in earlier reading)
        val bestBrand = if (brand != "Desconocida") brand else tracker.lastBrand
        val bestModel = model ?: tracker.lastModel
        val displayName = deviceName ?: bestModel

        synchronized(bufferLock) {
            scanBuffer[macAddress] = ScanData(
                macAddress = macAddress,
                deviceName = displayName,
                deviceType = "phone",
                brand = bestBrand,
                model = bestModel,
                rssi = rssi,
                avgRssi = avgRssi,
                rssiVariance = rssiStdDev,
                estimatedDistanceM = distance,
                txPower = effectiveTxPower,
                confidenceScore = confidence,
                detectionCount = tracker.detectionCount,
                isStationary = isStationary
            )
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

    fun resetTrackers() {
        synchronized(trackerLock) { deviceTrackers.clear() }
        synchronized(bufferLock) { scanBuffer.clear() }
    }

    // ===========================
    // DISTANCE ESTIMATION
    // ===========================

    private fun estimateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0 || txPower == 0) return -1.0
        // Log-distance path loss model: d = 10 ^ ((txPower - rssi) / (10 * n))
        val exponent = (txPower - rssi).toDouble() / (10.0 * PATH_LOSS_N)
        return 10.0.pow(exponent).coerceIn(0.05, 3.0)
    }

    // ===========================
    // BRAND + MODEL DETECTION
    // ===========================

    data class BrandResult(val brand: String, val model: String?)

    /**
     * Multi-layer brand and model detection.
     *
     * Layer 1: Apple Continuity TLV — parses Nearby Info for device type (iPhone/iPad/Mac/etc.)
     * Layer 2: Other manufacturer IDs (Samsung, Xiaomi, etc.)
     * Layer 3: BLE service UUIDs (Nearby Share, Samsung Galaxy, etc.)
     * Layer 4: Device name keywords (Galaxy S24, Pixel 9, etc.)
     * Layer 5: Randomized MAC heuristic — likely Android phone
     */
    private fun detectBrand(result: ScanResult): BrandResult {
        val record = result.scanRecord
        val mac = result.device.address
        val name = try { result.device.name?.lowercase() ?: "" } catch (_: SecurityException) { "" }

        // --- Layer 1 & 2: Manufacturer specific data ---
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                val mfgBytes = mfgData.valueAt(0)

                // Apple — parse Continuity TLV protocol
                if (mfgId == 0x004C && mfgBytes != null && mfgBytes.size >= 3) {
                    val appleInfo = parseAppleContinuity(mfgBytes)
                    return BrandResult("Apple", appleInfo)
                }

                // Known IoT — reject early
                if (mfgId in IOT_MFG_IDS) return BrandResult("IoT", null)

                // Samsung — try to extract model from device name
                if (mfgId == 0x0075) {
                    val samsungModel = extractModelFromName(name, "Samsung")
                    return BrandResult("Samsung", samsungModel)
                }

                // Other known brands
                val brand = BRAND_BY_MFG_ID[mfgId]
                if (brand != null) {
                    val model = extractModelFromName(name, brand)
                    return BrandResult(brand, model)
                }

                return BrandResult("MFG:${String.format("%04X", mfgId)}", null)
            }
        }

        // --- Layer 3: Service UUIDs ---
        if (record != null) {
            val serviceUuids = record.serviceUuids

            // Check accessory UUIDs first — reject
            if (serviceUuids != null) {
                for (uuid in ACCESSORY_SERVICE_UUIDS) {
                    if (serviceUuids.contains(uuid)) {
                        return BrandResult("Accessory", null)
                    }
                }
            }

            // Check phone service UUIDs
            if (serviceUuids != null) {
                for ((uuid, brand) in PHONE_SERVICE_UUIDS) {
                    if (serviceUuids.contains(uuid)) {
                        val model = extractModelFromName(name, brand)
                        return BrandResult(brand, model)
                    }
                }
                if (serviceUuids.isNotEmpty()) {
                    return BrandResult("Android", extractModelFromName(name, "Android"))
                }
            }

            val solicitedUuids = record.serviceSolicitationUuids
            if (solicitedUuids != null && solicitedUuids.isNotEmpty()) {
                return BrandResult("Android", extractModelFromName(name, "Android"))
            }
        }

        // --- Layer 4: Device name keywords ---
        if (name.isNotEmpty()) {
            return detectBrandFromName(name)
        }

        // --- Layer 5: Randomized MAC heuristic ---
        val secondChar = mac[1].digitToIntOrNull(16) ?: 0
        val isRandomized = (secondChar and 0x02) != 0

        if (isRandomized && record != null && record.bytes != null && record.bytes.size > 10) {
            return BrandResult("Android", null)
        }

        return BrandResult("Desconocida", null)
    }

    /**
     * Parse Apple Continuity TLV structure to extract device type.
     *
     * The manufacturer data after company ID 0x004C contains TLV entries:
     * type (1 byte) + length (1 byte) + data (N bytes), repeated.
     *
     * We look for Nearby Info (type 0x10). Its first data byte encodes:
     * - Upper nibble (bits 4-7): Activity/status flags
     * - Lower nibble (bits 0-3): Device type
     *
     * Returns model string ("iPhone", "iPad", "Mac") or null.
     */
    private fun parseAppleContinuity(mfgBytes: ByteArray): String? {
        var offset = 0

        while (offset + 2 < mfgBytes.size) {
            val type = mfgBytes[offset].toInt() and 0xFF
            val length = mfgBytes[offset + 1].toInt() and 0xFF
            val dataStart = offset + 2

            when (type) {
                0x10 -> {
                    // Nearby Info — the most common Apple BLE advertisement
                    if (length >= 1 && dataStart < mfgBytes.size) {
                        val statusByte = mfgBytes[dataStart].toInt() and 0xFF
                        val deviceType = statusByte and 0x0F  // lower nibble
                        return APPLE_NEARBY_DEVICE_TYPE[deviceType]
                    }
                }
                0x07 -> {
                    // Proximity Pairing — AirPods, Beats, etc.
                    return "AirPods"
                }
            }

            offset = dataStart + length
            if (length == 0) offset++ // safety: prevent infinite loop
        }

        // Could not determine device type from Continuity data
        return null
    }

    /**
     * Detect brand and model from device broadcast name.
     */
    private fun detectBrandFromName(name: String): BrandResult {
        return when {
            name.contains("iphone") -> BrandResult("Apple", "iPhone")
            name.contains("galaxy") && !name.contains("watch") && !name.contains("buds") && !name.contains("tab") ->
                BrandResult("Samsung", extractSamsungModel(name))
            name.contains("samsung") && !name.contains("tv") && !name.contains("tab") ->
                BrandResult("Samsung", extractSamsungModel(name))
            name.contains("pixel") && !name.contains("buds") && !name.contains("watch") ->
                BrandResult("Google", extractModelFromName(name, "Google"))
            name.contains("huawei") || name.contains("honor") ->
                BrandResult("Huawei", extractModelFromName(name, "Huawei"))
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                BrandResult("Xiaomi", extractModelFromName(name, "Xiaomi"))
            name.contains("motorola") || name.contains("moto g") || name.contains("moto e") || name.contains("moto edge") ->
                BrandResult("Motorola", extractModelFromName(name, "Motorola"))
            name.contains("oppo") -> BrandResult("Oppo", extractModelFromName(name, "Oppo"))
            name.contains("realme") -> BrandResult("Realme", extractModelFromName(name, "Realme"))
            name.contains("oneplus") -> BrandResult("OnePlus", extractModelFromName(name, "OnePlus"))
            name.contains("vivo") -> BrandResult("Vivo", extractModelFromName(name, "Vivo"))
            name.contains("nokia") -> BrandResult("Nokia", extractModelFromName(name, "Nokia"))
            name.contains("tcl") -> BrandResult("TCL", extractModelFromName(name, "TCL"))
            name.contains("zte") -> BrandResult("ZTE", extractModelFromName(name, "ZTE"))
            name.contains("nothing") -> BrandResult("Nothing", extractModelFromName(name, "Nothing"))
            name.contains("tecno") || name.contains("infinix") || name.contains("itel") ->
                BrandResult("Transsion", extractModelFromName(name, "Transsion"))
            name.contains("sony") || name.contains("xperia") ->
                BrandResult("Sony", extractModelFromName(name, "Sony"))
            else -> BrandResult("Android", name.trim())
        }
    }

    private fun extractSamsungModel(name: String): String? {
        // Try to extract model like "Galaxy S24 Ultra", "Galaxy A55", etc.
        val regex = Regex("(galaxy\\s*[a-z]\\d+[^\\s]*)", RegexOption.IGNORE_CASE)
        val match = regex.find(name)
        if (match != null) return match.value.trim()

        // Broader: anything after "galaxy" or "samsung"
        val parts = name.replace("samsung", "").replace("'s", "").trim()
        return if (parts.length > 2) parts else null
    }

    private fun extractModelFromName(name: String, brand: String): String? {
        if (name.isEmpty()) return null
        val cleaned = name.replace(brand.lowercase(), "").trim()
        return if (cleaned.length > 2) cleaned else null
    }

    // ===========================
    // PHONE-ONLY CATEGORIZATION
    // ===========================

    /**
     * Strict phone-only filter. Returns true ONLY for confirmed phones.
     *
     * Conservative approach: if we can't confirm it's a phone, reject it.
     * Better to miss a real phone than to count a tablet, laptop, or accessory.
     */
    private fun categorizeAsPhone(result: ScanResult, name: String?, brand: String, model: String?): Boolean {
        val lowerName = name?.lowercase() ?: ""

        // --- Hard rejects ---

        // Reject known non-phone brands
        if (brand in listOf("IoT", "Accessory", "Logitech", "Garmin", "Nike", "Nordic")) return false

        // Reject by name patterns (TVs, speakers, tablets, laptops, watches, etc.)
        for (pattern in NON_PHONE_NAME_PATTERNS) {
            if (lowerName.contains(pattern)) return false
        }

        // Reject known IoT manufacturer IDs
        val record = result.scanRecord
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                if (mfgId in IOT_MFG_IDS) return false
            }
        }

        // --- Apple: ONLY iPhone (and iPod Touch) passes ---
        if (brand == "Apple") {
            return model == "iPhone" || model == "iPod Touch"
        }

        // --- Known phone brands with no disqualifying name ---
        if (brand in listOf(
                "Samsung", "Google", "Xiaomi", "Oppo", "Realme", "OnePlus",
                "Motorola", "Huawei", "Sony", "Nokia", "Vivo", "TCL", "ZTE",
                "Nothing", "Transsion", "Lenovo", "Google/Android"
            )) {
            return true
        }

        // --- Android (generic) or unknown manufacturer ---
        if (brand == "Android") return true

        // --- Randomized MAC with advertisement data = likely modern phone ---
        val mac = result.device.address
        val secondChar = mac[1].digitToIntOrNull(16) ?: 0
        val isRandomized = (secondChar and 0x02) != 0
        if (isRandomized) return true

        // --- Unknown manufacturer with prefix "MFG:" — uncertain, reject ---
        if (brand.startsWith("MFG:")) return false

        // Default: reject (conservative)
        return false
    }

    // ===========================
    // CONFIDENCE SCORING
    // ===========================

    /**
     * Calculate a confidence score (0-100) for each detected phone.
     *
     * Based on:
     * - Detection count (consistency of presence)
     * - Brand identification (known vs unknown)
     * - Model identification (specific model detected)
     * - RSSI stability (lower variance = more reliable reading)
     */
    private fun calculateConfidence(
        detectionCount: Int,
        brandIdentified: Boolean,
        modelIdentified: Boolean,
        rssiStdDev: Double
    ): Int {
        var score = 0

        // Detection count: up to 40 points (1 det = 10, capped at 4+)
        score += (detectionCount * 10).coerceAtMost(40)

        // Brand identification: 20 points
        if (brandIdentified) score += 20

        // Model identification: 20 points
        if (modelIdentified) score += 20

        // RSSI stability: up to 20 points
        score += when {
            rssiStdDev < 2.0 -> 20
            rssiStdDev < 4.0 -> 15
            rssiStdDev < 6.0 -> 10
            rssiStdDev < 10.0 -> 5
            else -> 0
        }

        return score.coerceIn(0, 100)
    }

    // ===========================
    // MATH HELPERS
    // ===========================

    private fun stdDev(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val sumSquares = values.fold(0.0) { acc, v ->
            val diff = v.toDouble() - mean
            acc + diff * diff
        }
        return sqrt(sumSquares / (values.size - 1))
    }
}
