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

@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as BleApplication).repository

    val isScanning = MutableStateFlow(false)
    val currentSessionId = MutableStateFlow<String?>(null)

    // Refresh trigger — emits periodically to refresh "active" queries
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

    fun onScanStarted(sessionId: String) {
        isScanning.value = true
        currentSessionId.value = sessionId
    }

    fun onScanStopped() {
        isScanning.value = false
    }

    fun getExportData(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val data = repository.getExportData()
            val csv = buildString {
                appendLine("id,mac_address,device_name,rssi,estimated_distance_m,tx_power,timestamp,session_id")
                data.forEach { r ->
                    appendLine("${r.id},${r.macAddress},${r.deviceName ?: ""},${r.rssi},${String.format("%.2f", r.estimatedDistanceM)},${r.txPower ?: ""},${r.timestamp},${r.sessionId}")
                }
            }
            onResult(csv)
        }
    }
}
