package com.increxa.blescanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.increxa.blescanner.BleApplication
import com.increxa.blescanner.ble.BleScanner
import com.increxa.blescanner.data.BleDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as BleApplication).repository

    val isScanning = MutableStateFlow(false)
    val currentSessionId = MutableStateFlow<String?>(null)
    val currentPdvName = MutableStateFlow("")
    val sessionStartTime = MutableStateFlow(0L)

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

    val avgConfidence: StateFlow<Int?> = repository.getAvgConfidence()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val appleDeviceCount: StateFlow<Int> = repository.getAppleDeviceCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val exhibitionCount: StateFlow<Int> = repository.getExhibitionCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Estimated people = non-exhibition Apple count / market share */
    val estimatedPeople: StateFlow<Int> = repository.getAppleNonExhibCount()
        .map { count ->
            if (count > 0) ceil(count / BleScanner.APPLE_MARKET_SHARE_CL.toDouble()).toInt()
            else 0
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun onScanStarted(sessionId: String, pdvName: String) {
        isScanning.value = true
        currentSessionId.value = sessionId
        currentPdvName.value = pdvName
        sessionStartTime.value = System.currentTimeMillis()
    }

    fun onScanStopped() {
        isScanning.value = false
    }

    fun getExportData(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
            val devices = repository.getDevicesForExport()
            val session = repository.getLatestSession()

            val sep = ";"
            val csv = buildString {
                // Session summary header
                appendLine("# RESUMEN DE SESION")
                appendLine("# PDV${sep}${session?.pdvName ?: currentPdvName.value.ifBlank { "Sin nombre" }}")
                appendLine("# Inicio${sep}${session?.let { dateFormat.format(Date(it.startTime)) } ?: ""}")
                appendLine("# Fin${sep}${session?.endTime?.let { dateFormat.format(Date(it)) } ?: "En curso"}")
                val durationMs = (session?.endTime ?: System.currentTimeMillis()) - (session?.startTime ?: 0)
                val durationMin = durationMs / 60000
                appendLine("# Duracion${sep}${durationMin / 60}h ${durationMin % 60}m")
                val appleCount = devices.count { it.brand == "Apple" && !it.isStationary }
                val estPeople = if (appleCount > 0) ceil(appleCount / BleScanner.APPLE_MARKET_SHARE_CL.toDouble()).toInt() else 0
                appendLine("# Personas estimadas${sep}$estPeople")
                appendLine("# Dispositivos detectados${sep}${devices.size}")
                appendLine("# Apple detectados${sep}${devices.count { it.brand == "Apple" }}")
                appendLine("# Exhibicion${sep}${devices.count { it.isStationary }}")
                val avgConf = devices.filter { it.confidenceScore > 0 }.map { it.confidenceScore }.average().let { if (it.isNaN()) 0 else it.toInt() }
                appendLine("# Confianza promedio${sep}${avgConf}%")
                val lat = session?.locationLat?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                val lng = session?.locationLng?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                appendLine("# GPS${sep}$lat${sep}$lng")
                appendLine("# Factor correccion${sep}Apple Chile ${BleScanner.APPLE_MARKET_SHARE_CL}")
                appendLine("#")

                // Column headers
                appendLine("dispositivo_id${sep}marca${sep}modelo${sep}nombre_dispositivo${sep}primera_deteccion${sep}ultima_deteccion${sep}permanencia_segundos${sep}veces_detectado${sep}rssi_promedio${sep}confianza_pct${sep}es_exhibicion${sep}sesion_latitud${sep}sesion_longitud")

                val detailDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                devices.forEach { d ->
                    val firstSeen = detailDateFormat.format(Date(d.firstSeenAt))
                    val lastSeen = detailDateFormat.format(Date(d.lastSeenAt))
                    val dwellSeconds = d.totalDurationMs / 1000
                    val safeName = (d.deviceName ?: "").replace(";", " ").replace("\"", "")
                    val brand = (d.brand ?: "Desconocida").replace(";", " ")
                    val model = (d.model ?: "").replace(";", " ")
                    val exhib = if (d.isStationary) "SI" else "NO"
                    appendLine("${d.macAddress}${sep}$brand${sep}$model${sep}$safeName${sep}$firstSeen${sep}$lastSeen${sep}$dwellSeconds${sep}${d.scanCount}${sep}${d.avgRssi}${sep}${d.confidenceScore}${sep}$exhib${sep}$lat${sep}$lng")
                }
            }
            onResult(csv)
        }
    }
}
