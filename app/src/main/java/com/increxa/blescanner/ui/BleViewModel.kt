package com.increxa.blescanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.increxa.blescanner.BleApplication
import com.increxa.blescanner.data.BleDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as BleApplication).repository

    val isScanning = MutableStateFlow(false)
    val currentSessionId = MutableStateFlow<String?>(null)

    private val refreshTrigger = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            while (true) {
                delay(3000)
                refreshTrigger.value = System.currentTimeMillis()
            }
        }
    }

    val activeDevices: StateFlow<List<BleDevice>> = refreshTrigger.flatMapLatest {
        repository.getActiveDevices()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDevices: StateFlow<List<BleDevice>> = repository.getAllDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalUniqueDevices: StateFlow<Int> = repository.getTotalUniqueDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val avgDwellTimeMs: StateFlow<Long?> = repository.getAvgDwellTimeMs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val totalScanResults: StateFlow<Int> = repository.getTotalScanResults()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val avgConfidence: StateFlow<Int?> = repository.getAvgConfidence()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun onScanStarted(sessionId: String) {
        isScanning.value = true
        currentSessionId.value = sessionId
    }

    fun onScanStopped() {
        isScanning.value = false
    }

    fun getExportData(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            val devices = repository.getDevicesForExport()
            val session = repository.getLatestSession()

            val sep = ";"  // Semicolon separator — safe for LATAM locales and field values
            val csv = buildString {
                // Professional CSV header
                appendLine("dispositivo_id${sep}marca${sep}modelo${sep}nombre_dispositivo${sep}primera_deteccion${sep}ultima_deteccion${sep}permanencia_segundos${sep}veces_detectado${sep}rssi_promedio${sep}confianza_pct${sep}es_exhibicion${sep}sesion_latitud${sep}sesion_longitud")

                devices.forEach { d ->
                    val firstSeen = dateFormat.format(Date(d.firstSeenAt))
                    val lastSeen = dateFormat.format(Date(d.lastSeenAt))
                    val dwellSeconds = d.totalDurationMs / 1000
                    val safeName = (d.deviceName ?: "").replace(";", " ").replace("\"", "")
                    val brand = (d.brand ?: "Desconocida").replace(";", " ")
                    val model = (d.model ?: "").replace(";", " ")
                    val lat = session?.locationLat?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                    val lng = session?.locationLng?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                    val exhib = if (d.isStationary) "SI" else "NO"

                    appendLine("${d.macAddress}${sep}$brand${sep}$model${sep}$safeName${sep}$firstSeen${sep}$lastSeen${sep}$dwellSeconds${sep}${d.scanCount}${sep}${d.avgRssi}${sep}${d.confidenceScore}${sep}$exhib${sep}$lat${sep}$lng")
                }
            }
            onResult(csv)
        }
    }
}
