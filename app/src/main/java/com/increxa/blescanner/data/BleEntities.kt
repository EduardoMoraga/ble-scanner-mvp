package com.increxa.blescanner.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ble_sessions")
data class BleSession(
    @PrimaryKey val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val notes: String? = null
)

@Entity(tableName = "ble_devices")
data class BleDevice(
    @PrimaryKey val macAddress: String,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val totalDurationMs: Long = 0,
    val scanCount: Int = 0
)

@Entity(
    tableName = "ble_scan_results",
    indices = [
        Index("macAddress"),
        Index("sessionId"),
        Index("timestamp")
    ],
    foreignKeys = [
        ForeignKey(
            entity = BleDevice::class,
            parentColumns = ["macAddress"],
            childColumns = ["macAddress"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BleScanResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val macAddress: String,
    val deviceName: String? = null,
    val rssi: Int,
    val estimatedDistanceM: Double,
    val txPower: Int? = null,
    val timestamp: Long,
    val sessionId: String
)
