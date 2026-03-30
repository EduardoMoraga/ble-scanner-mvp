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

        // Stationary/exhibition detection thresholds
        // 10 minutes — within BLE MAC rotation window (Android rotates ~15min)
        const val STATIONARY_TIME_MS = 10 * 60 * 1000L
        const val STATIONARY_RSSI_STD_MAX = 6.0  // dBm — relaxed from 4.0 for real-world conditions
        private const val STATIONARY_WINDOW_SIZE = 40  // ~2 minutes of readings at 3s intervals

        // Indoor path-loss exponent for distance estimation
        private const val PATH_LOSS_N = 2.5

        // Apple market share in Chile (~35%). Used to estimate total foot traffic
        // from Apple-only BLE counts: estimatedPeople = appleCount / marketShare.
        // BLE scanning only reliably identifies Apple devices via Continuity protocol.
        // Android devices use randomized MACs and diverse BLE stacks, making
        // brand-level attribution less reliable. Source: Counterpoint Research 2025.
        const val APPLE_MARKET_SHARE_CL = 0.35f

        // === MANUFACTURER IDs (Bluetooth SIG) — PHONE brands only ===
        private val BRAND_BY_MFG_ID = mapOf(
            0x004C to "Apple",
            0x0075 to "Samsung",
            0x00E0 to "Google",
            0x010F to "Xiaomi",
            0x0157 to "Huawei",
            0x0046 to "Sony",
            0x038F to "Motorola",
            0x02D5 to "Oppo",
            0x0310 to "Realme",
            0x03DA to "OnePlus",
            0x0301 to "Vivo",
            0x0131 to "TCL",
            0x02E5 to "Transsion",
            0x0386 to "Nothing",
            0x039A to "ZTE",
            0x01B0 to "Lenovo",
        )

        // Manufacturer IDs that are definitively NOT phones
        private val NON_PHONE_MFG_IDS = setOf(
            0x0059, // Nordic Semiconductor (beacons, sensors)
            0x004D, // Logitech (mouse, keyboard)
            0x0822, // Govee
            0x0078, // Nike (shoes/bands)
            0x0087, // Garmin (watches)
            0x000D, // Texas Instruments (IoT sensors)
            0x0002, // Intel (laptops)
            0x0006, // Microsoft (Windows PCs, Xbox, Surface)
            0x0171, // Amazon (Fire tablets, Echo speakers)
            0x001D, // Qualcomm (reference boards, IoT, not phones)
            0x000F, // Broadcom (routers, IoT, not phones)
        )

        // === BLE Service UUIDs ===

        // Google Fast Pair — ACCESSORIES broadcast this, not phones
        private val GOOGLE_FAST_PAIR_UUID = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

        // Google Nearby Share / Quick Share — phones DO broadcast this
        private val GOOGLE_NEARBY_UUID = ParcelUuid.fromString("0000FEF3-0000-1000-8000-00805F9B34FB")

        // Samsung Galaxy ecosystem
        private val SAMSUNG_UUID = ParcelUuid.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")

        // Xiaomi MiShare
        private val XIAOMI_UUID = ParcelUuid.fromString("0000FE95-0000-1000-8000-00805F9B34FB")

        // Apple-related service UUIDs (used when manufacturer data is absent)
        private val APPLE_FINDMY_UUID = ParcelUuid.fromString("0000FD44-0000-1000-8000-00805F9B34FB")
        private val APPLE_NEARBY_INTERACTION_UUID = ParcelUuid.fromString("0000FE2F-0000-1000-8000-00805F9B34FB")
        private val APPLE_CONTINUITY_UUID = ParcelUuid.fromString("0000FE26-0000-1000-8000-00805F9B34FB")
        private val APPLE_HOMEKIT_UUID = ParcelUuid.fromString("0000FED8-0000-1000-8000-00805F9B34FB")

        // Map UUID -> brand (only phone-related UUIDs)
        private val PHONE_SERVICE_UUIDS = mapOf(
            GOOGLE_NEARBY_UUID to "Google/Android",
            SAMSUNG_UUID to "Samsung",
            XIAOMI_UUID to "Xiaomi",
            APPLE_FINDMY_UUID to "Apple",
            APPLE_NEARBY_INTERACTION_UUID to "Apple",
            APPLE_CONTINUITY_UUID to "Apple",
        )

        // UUIDs that indicate this is an ACCESSORY, not a phone
        private val ACCESSORY_SERVICE_UUIDS = setOf(
            GOOGLE_FAST_PAIR_UUID,
            APPLE_HOMEKIT_UUID,  // HomeKit accessories
        )

        // === Apple Nearby Info device types (lower nibble of status byte) ===
        // Source: reverse-engineering by Hexway (apple_bleee), Furiosa Security
        private val APPLE_NEARBY_DEVICE_TYPE = mapOf(
            0x01 to "MacBook",
            0x02 to "iPhone",
            0x03 to "iPad",
            0x04 to "Apple Watch",
            0x05 to "iPhone",  // Was iPod Touch (discontinued 2022). Modern 0x05 broadcasts are iPhones.
            0x06 to "Apple TV",
            0x07 to "HomePod",
            0x09 to "MacBook",
            0x0A to "Apple Watch",
            0x0B to "MacBook",
            0x0C to "MacBook",
            0x0E to "iMac",
        )

        // Apple device types that are phones (for categorizeAsPhone)
        private val APPLE_PHONE_TYPES = setOf("iPhone")

        // Apple device types that are definitely NOT phones
        private val APPLE_NON_PHONE_TYPES = setOf(
            "iPad", "MacBook", "Mac", "iMac",
            "Apple Watch", "Apple TV", "HomePod", "AirPods"
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
            "macbook", "imac", "laptop", "book",  // Samsung Galaxy Book, Lenovo IdeaPad Book, etc.
            "pencil", "magic",  // Apple Pencil, Magic Keyboard/Mouse
        )

        // Known phone brands for categorizeAsPhone
        private val KNOWN_PHONE_BRANDS = setOf(
            "Samsung", "Google", "Xiaomi", "Oppo", "Realme", "OnePlus",
            "Motorola", "Huawei", "Sony", "Nokia", "Vivo", "TCL", "ZTE",
            "Nothing", "Transsion", "Lenovo", "Google/Android",
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
    @Volatile private var isScanning = false
    private val scanBuffer = mutableMapOf<String, ScanData>()
    private val bufferLock = Any()
    private val deviceTrackers = mutableMapOf<String, DeviceTracker>()
    private val trackerLock = Any()
    var onScanError: ((Int) -> Unit)? = null

    // Debug: count rejected devices per reason (reset on flush)
    private var _debugRejectCounts = mutableMapOf<String, Int>()
    val debugRejectCounts: Map<String, Int> get() = synchronized(bufferLock) { _debugRejectCounts.toMap() }

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

        // Reject invalid RSSI readings (valid BLE RSSI range: -120 to -1)
        if (rssi >= 0 || rssi < -120) {
            logReject("RSSI_INVALID", macAddress)
            return
        }

        // --- Step 1: Track this reading regardless of threshold ---
        // Hold trackerLock for all tracker operations to prevent races
        val trackerSnapshot: TrackerSnapshot = synchronized(trackerLock) {
            val tracker = deviceTrackers.getOrPut(macAddress) {
                DeviceTracker(firstSeen = now)
            }
            tracker.rssiReadings.add(rssi)
            tracker.detectionCount++
            // Trim to prevent unbounded growth
            if (tracker.rssiReadings.size > MAX_RSSI_HISTORY) {
                tracker.rssiReadings.removeAt(0)
            }

            // Snapshot all needed values while still holding the lock
            TrackerSnapshot(
                firstSeen = tracker.firstSeen,
                recentRssi = tracker.rssiReadings.takeLast(5),
                allRssi = tracker.rssiReadings.toList(),
                detectionCount = tracker.detectionCount,
                lastBrand = tracker.lastBrand,
                lastModel = tracker.lastModel
            )
        }

        // --- Step 2: RSSI smoothing — use average of last 5 readings ---
        val avgRssi = trackerSnapshot.recentRssi.average().toInt()

        // Reject if smoothed RSSI below threshold (~1.5m)
        if (avgRssi < RSSI_THRESHOLD) {
            logReject("RSSI_TOO_WEAK", macAddress)
            return
        }

        // --- Step 3: Minimum detection gate — must be seen 2+ times ---
        if (trackerSnapshot.detectionCount < MIN_DETECTIONS) return

        // --- Step 4: Brand + Model detection ---
        val deviceName = try { device.name } catch (_: SecurityException) { null }
        val brandResult = detectBrand(result)
        val brand = brandResult.brand
        val model = brandResult.model

        // --- Step 5: Strict phone-only filter ---
        val isPhone = categorizeAsPhone(result, deviceName, brand, model)
        if (!isPhone) {
            logReject("NOT_PHONE:$brand", macAddress)
            return
        }

        // --- Step 6: Compute metrics ---
        val record = result.scanRecord
        val txPower = record?.txPowerLevel ?: DEFAULT_TX_POWER
        val effectiveTxPower = if (txPower == Int.MIN_VALUE) DEFAULT_TX_POWER else txPower
        val distance = estimateDistance(avgRssi, effectiveTxPower)

        // Use sliding window for RSSI stddev (last ~2 min, not all history)
        val recentWindow = trackerSnapshot.allRssi.takeLast(STATIONARY_WINDOW_SIZE)
        val rssiStdDev = stdDev(recentWindow)

        // Exhibition: 10 min with stable RSSI in recent window
        val isStationary = recentWindow.size >= 10 &&
                (now - trackerSnapshot.firstSeen > STATIONARY_TIME_MS) &&
                rssiStdDev < STATIONARY_RSSI_STD_MAX

        val confidence = calculateConfidence(
            detectionCount = trackerSnapshot.detectionCount,
            brand = brand,
            model = model,
            rssiStdDev = rssiStdDev,
            avgRssi = avgRssi
        )

        // Update tracker with latest brand/model
        synchronized(trackerLock) {
            val tracker = deviceTrackers[macAddress]
            if (tracker != null) {
                if (brand != "Desconocida") tracker.lastBrand = brand
                if (model != null) tracker.lastModel = model
            }
        }

        // Use best known brand/model (might have been identified in earlier reading)
        val bestBrand = if (brand != "Desconocida") brand else trackerSnapshot.lastBrand
        val bestModel = model ?: trackerSnapshot.lastModel
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
                detectionCount = trackerSnapshot.detectionCount,
                isStationary = isStationary
            )
        }
    }

    /** Immutable snapshot of tracker state, safe to use outside the lock. */
    private data class TrackerSnapshot(
        val firstSeen: Long,
        val recentRssi: List<Int>,
        val allRssi: List<Int>,
        val detectionCount: Int,
        val lastBrand: String,
        val lastModel: String?
    )

    private fun logReject(reason: String, mac: String) {
        synchronized(bufferLock) {
            _debugRejectCounts[reason] = (_debugRejectCounts[reason] ?: 0) + 1
        }
        // Log at most once per reason per MAC per minute to avoid spam
        Log.v(TAG, "REJECT $reason: $mac")
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

            // Log debug reject summary
            if (_debugRejectCounts.isNotEmpty()) {
                Log.d(TAG, "Reject summary: $_debugRejectCounts")
                _debugRejectCounts.clear()
            }

            return results
        }
    }

    fun resetTrackers() {
        synchronized(trackerLock) { deviceTrackers.clear() }
        synchronized(bufferLock) {
            scanBuffer.clear()
            _debugRejectCounts.clear()
        }
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
     * Layer 1: Apple Continuity TLV — parses Nearby Info for device type
     * Layer 2: Other manufacturer IDs (Samsung, Xiaomi, etc.)
     * Layer 3: BLE service UUIDs (Apple Find My, Nearby Share, Samsung Galaxy, etc.)
     * Layer 4: Device name keywords (Galaxy S24, Pixel 9, etc.)
     * Layer 5: Randomized MAC heuristic with payload analysis
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
                if (mfgId == 0x004C && mfgBytes != null) {
                    val appleModel = if (mfgBytes.size >= 2) parseAppleContinuity(mfgBytes) else null
                    return BrandResult("Apple", appleModel)
                }

                // Known non-phone manufacturers — reject early
                if (mfgId in NON_PHONE_MFG_IDS) {
                    return BrandResult("NonPhone", null)
                }

                // Samsung — try to extract model from device name
                if (mfgId == 0x0075) {
                    val samsungModel = extractModelFromName(name, "Samsung")
                    return BrandResult("Samsung", samsungModel)
                }

                // Other known phone brands
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

            if (serviceUuids != null && serviceUuids.isNotEmpty()) {
                // Check accessory UUIDs first — reject
                for (uuid in ACCESSORY_SERVICE_UUIDS) {
                    if (serviceUuids.contains(uuid)) {
                        return BrandResult("Accessory", null)
                    }
                }

                // Check known phone service UUIDs (includes Apple!)
                for ((uuid, brand) in PHONE_SERVICE_UUIDS) {
                    if (serviceUuids.contains(uuid)) {
                        val model = extractModelFromName(name, brand)
                        return BrandResult(brand, model)
                    }
                }

                // Unknown service UUIDs — do NOT blindly assume Android.
                // Could be Apple overflow advertisements, IoT devices, etc.
                // Fall through to Layer 4 and 5 for better classification.
            }

            // Service solicitation UUIDs — Apple uses these for overflow advertisements
            val solicitedUuids = record.serviceSolicitationUuids
            if (solicitedUuids != null && solicitedUuids.isNotEmpty()) {
                // Apple overflow advertisements use solicitation UUIDs
                // These indicate an Apple device that couldn't fit data in the main advertisement
                // Accept as Apple (80% probability iPhone in public)
                return BrandResult("Apple", null)
            }

            // Service data check — Apple Find My uses service data, not manufacturer data
            val serviceData = record.serviceData
            if (serviceData != null && serviceData.isNotEmpty()) {
                for (uuid in serviceData.keys) {
                    if (uuid != null && PHONE_SERVICE_UUIDS.containsKey(uuid)) {
                        return BrandResult(PHONE_SERVICE_UUIDS[uuid]!!, null)
                    }
                }
            }
        }

        // --- Layer 4: Device name keywords ---
        if (name.isNotEmpty()) {
            return detectBrandFromName(name)
        }

        // --- Layer 5: Randomized MAC heuristic ---
        // Modern phones (both Android and iOS) use randomized MACs.
        // The second hex char bit 1 (0x02) indicates locally administered (randomized).
        // Only accept if there is meaningful scan data (payload > 10 bytes).
        val secondChar = mac[1].digitToIntOrNull(16) ?: 0
        val isRandomized = (secondChar and 0x02) != 0

        if (isRandomized && record != null && record.bytes != null && record.bytes.size > 10) {
            // Cannot determine brand — return unknown.
            // categorizeAsPhone will decide based on randomized MAC.
            return BrandResult("Desconocida", null)
        }

        return BrandResult("Desconocida", null)
    }

    /**
     * Parse Apple Continuity TLV structure to extract device type.
     *
     * The manufacturer data after company ID 0x004C contains TLV entries:
     * type (1 byte) + length (1 byte) + data (N bytes), repeated.
     *
     * We look for:
     * - Nearby Info (0x10): status byte lower nibble = device type
     * - Proximity Pairing (0x07): AirPods/Beats
     * - AirDrop (0x05): iPhone/iPad/Mac sending AirDrop
     * - Find My (0x12): any Apple device with Find My enabled
     * - Nearby Action (0x0F): usually triggered by phones
     *
     * Returns model string ("iPhone", "iPad", "AirPods") or null if type unknown.
     * A null return does NOT mean it's not an iPhone — it means we couldn't determine type.
     */
    private fun parseAppleContinuity(mfgBytes: ByteArray): String? {
        var offset = 0
        var foundType: String? = null

        // Strategy 1: Parse TLV structure
        while (offset + 1 < mfgBytes.size) {
            val type = mfgBytes[offset].toInt() and 0xFF
            val length = mfgBytes[offset + 1].toInt() and 0xFF
            val dataStart = offset + 2

            // Bounds check: if length extends beyond data, stop parsing
            if (dataStart + length > mfgBytes.size) break

            when (type) {
                0x10 -> {
                    // Nearby Info — the most common Apple BLE advertisement
                    if (length >= 1) {
                        val statusByte = mfgBytes[dataStart].toInt() and 0xFF
                        val deviceType = statusByte and 0x0F
                        val detected = APPLE_NEARBY_DEVICE_TYPE[deviceType]
                        if (detected != null) return detected
                        // Unknown device type value — still Apple, just can't classify
                    }
                }
                0x12 -> {
                    // Find My — any Apple device with Find My enabled.
                    // This is extremely common. Cannot distinguish iPhone from iPad/Mac.
                    // Don't return "AirPods" here — Find My is not Proximity Pairing.
                    // Label as "iPhone (est.)" — ~80% of Apple BLE devices in public are iPhones.
                    foundType = foundType ?: "iPhone (est.)"
                }
                0x07 -> return "AirPods"   // Proximity Pairing (AirPods, Beats)
                0x05 -> foundType = "iPhone" // AirDrop — usually phone or tablet
                0x0C -> { /* Handoff — could be iPhone or Mac, don't override */ }
                0x0F -> foundType = foundType ?: "iPhone" // Nearby Action — usually phone
            }

            offset = dataStart + length
            if (length == 0) offset++ // prevent infinite loop on malformed data
        }

        if (foundType != null) return foundType

        // Strategy 2: Fallback — scan raw bytes for Nearby Info pattern (0x10)
        // Some Apple devices might not have clean TLV but still contain 0x10 somewhere
        for (i in 0 until mfgBytes.size - 2) {
            if ((mfgBytes[i].toInt() and 0xFF) == 0x10) {
                val possibleLength = mfgBytes[i + 1].toInt() and 0xFF
                if (possibleLength in 1..20 && i + 2 < mfgBytes.size) {
                    val statusByte = mfgBytes[i + 2].toInt() and 0xFF
                    val deviceType = statusByte and 0x0F
                    val detected = APPLE_NEARBY_DEVICE_TYPE[deviceType]
                    if (detected != null) return detected
                }
            }
        }

        // Could not determine device type — return null.
        // categorizeAsPhone will still accept Apple with null model (80% iPhone assumption).
        return null
    }

    /**
     * Detect brand and model from device broadcast name.
     */
    private fun detectBrandFromName(name: String): BrandResult {
        return when {
            name.contains("iphone") -> BrandResult("Apple", "iPhone")
            name.contains("ipad") -> BrandResult("Apple", "iPad")
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
            else -> BrandResult("Desconocida", name.trim())
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
     * Strict phone-only filter. Returns true ONLY for confirmed/probable phones.
     *
     * Strategy:
     * - Known non-phone brands/patterns: hard reject.
     * - Apple: accept if model is phone/unknown, reject if model is known non-phone.
     * - Known Android brands: accept.
     * - Randomized MAC with substantial payload: accept (high probability phone).
     * - Everything else: reject.
     */
    private fun categorizeAsPhone(result: ScanResult, name: String?, brand: String, model: String?): Boolean {
        val lowerName = name?.lowercase() ?: ""

        // --- Hard rejects: known NON-phone brands ---
        if (brand == "NonPhone" || brand == "Accessory" || brand == "IoT") return false

        // Reject by name patterns (TVs, speakers, tablets, laptops, watches, etc.)
        for (pattern in NON_PHONE_NAME_PATTERNS) {
            if (lowerName.contains(pattern)) return false
        }

        // Reject known non-phone manufacturer IDs (double-check in case detectBrand missed)
        val record = result.scanRecord
        if (record != null) {
            val mfgData = record.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                val mfgId = mfgData.keyAt(0)
                if (mfgId in NON_PHONE_MFG_IDS) return false
            }
        }

        // Reject unknown manufacturer prefixes (MFG:XXXX = unrecognized chip)
        if (brand.startsWith("MFG:")) return false

        // --- Apple handling ---
        // Apple devices detected via manufacturer ID 0x004C or Apple service UUIDs.
        // If TLV identified a specific device type, use it.
        // If type is unknown (null or "Apple Device"), ACCEPT — ~80% are iPhones in public.
        // The 1.5m RSSI threshold already filters most iPads/Macs (used at desk distance).
        if (brand == "Apple") {
            if (model != null && model in APPLE_NON_PHONE_TYPES) return false
            // model is "iPhone", "iPhone (est.)", null, or other → accept
            return true
        }

        // --- Known phone brands ---
        if (brand in KNOWN_PHONE_BRANDS) return true

        // --- Generic Android (from name-based detection) ---
        if (brand == "Android") return true

        // --- Randomized MAC heuristic (brand == "Desconocida") ---
        // Modern phones use randomized MACs. If the MAC is randomized and the device has
        // a meaningful BLE payload, it's very likely a phone.
        // This catches phones that don't advertise manufacturer data or known service UUIDs.
        if (brand == "Desconocida") {
            val mac = result.device.address
            val secondChar = mac[1].digitToIntOrNull(16) ?: 0
            val isRandomized = (secondChar and 0x02) != 0
            if (isRandomized && record != null && record.bytes != null && record.bytes.size > 10) {
                return true
            }
        }

        // Default: reject
        return false
    }

    // ===========================
    // CONFIDENCE SCORING
    // ===========================

    /**
     * Calculate a confidence score (0-100) for each detected phone.
     *
     * Redesigned to produce meaningful distribution:
     * - Detection frequency is the strongest signal (up to 50 pts)
     *   -- More detections = more certain this is a real, present phone
     * - RSSI strength provides proximity confidence (up to 20 pts)
     *   -- Stronger signal = closer = more reliable detection
     * - RSSI stability reflects consistent presence (up to 20 pts)
     *   -- Low variance = device is stationary, not just passing by
     * - Brand identification adds certainty (up to 10 pts)
     *   -- Known brand > unknown; specific model is a bonus
     */
    private fun calculateConfidence(
        detectionCount: Int,
        brand: String,
        model: String?,
        rssiStdDev: Double,
        avgRssi: Int
    ): Int {
        var score = 0

        // Detection frequency: up to 50 points
        // Gradual ramp: 2 det = 10, 5 = 25, 10 = 40, 15+ = 50
        score += when {
            detectionCount >= 15 -> 50
            detectionCount >= 10 -> 40
            detectionCount >= 7 -> 35
            detectionCount >= 5 -> 25
            detectionCount >= 3 -> 15
            else -> 10
        }

        // RSSI strength: up to 20 points
        // Stronger signal = closer = more confident it's a real detection
        score += when {
            avgRssi >= -45 -> 20  // very close, < 0.3m
            avgRssi >= -50 -> 17  // close, < 0.5m
            avgRssi >= -55 -> 13  // nearby, ~ 1m
            avgRssi >= -59 -> 8   // in range, ~ 1.2m
            else -> 3             // edge of range, ~ 1.5m
        }

        // RSSI stability: up to 20 points
        score += when {
            rssiStdDev < 2.0 -> 20
            rssiStdDev < 4.0 -> 15
            rssiStdDev < 6.0 -> 10
            rssiStdDev < 10.0 -> 5
            else -> 0
        }

        // Brand/model identification: up to 10 points
        val brandKnown = brand != "Desconocida" && !brand.startsWith("MFG:")
        if (brandKnown) score += 5
        if (model != null && !model.contains("est.")) score += 5

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
