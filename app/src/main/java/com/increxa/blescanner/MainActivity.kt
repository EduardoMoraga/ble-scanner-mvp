package com.increxa.blescanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.increxa.blescanner.ble.BleScanService
import com.increxa.blescanner.export.DataExporter
import com.increxa.blescanner.ui.BleViewModel
import com.increxa.blescanner.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: BleViewModel by viewModels()
    private var scanService: BleScanService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as BleScanService.LocalBinder).getService()
            scanService = service
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            serviceBound = false
        }
    }

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            checkBluetoothAndStartScan()
        } else {
            Toast.makeText(this, "Se necesitan todos los permisos para escanear", Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) doStartScan()
        else Toast.makeText(this, "Bluetooth debe estar activado", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStartScan = { requestPermissionsAndScan() },
                    onStopScan = { stopScan() },
                    onExport = { exportData() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BleScanService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun requestPermissionsAndScan() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) checkBluetoothAndStartScan()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun checkBluetoothAndStartScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Toast.makeText(this, "Este dispositivo no tiene Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        doStartScan()
    }

    @SuppressLint("MissingPermission")
    private fun doStartScan() {
        val sessionId = "session_${System.currentTimeMillis()}"

        // Capture GPS location for the session
        var lat: Double? = null
        var lng: Double? = null
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) {
                lat = location.latitude
                lng = location.longitude
            }
        } catch (_: Exception) {}

        viewModel.onScanStarted(sessionId)

        val intent = Intent(this, BleScanService::class.java).apply {
            action = BleScanService.ACTION_START
            putExtra("session_id", sessionId)
            putExtra("lat", lat ?: Double.NaN)
            putExtra("lng", lng ?: Double.NaN)
        }
        startForegroundService(intent)

        bindService(Intent(this, BleScanService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        Toast.makeText(this, "Escaneando telefonos cercanos (2-3m)", Toast.LENGTH_SHORT).show()
    }

    private fun stopScan() {
        scanService?.stopScanning()
        viewModel.onScanStopped()
        Toast.makeText(this, "Escaneo detenido", Toast.LENGTH_SHORT).show()
    }

    private fun exportData() {
        viewModel.getExportData { csvContent ->
            if (csvContent.lines().size <= 1) {
                runOnUiThread {
                    Toast.makeText(this, "No hay datos para exportar", Toast.LENGTH_SHORT).show()
                }
                return@getExportData
            }
            runOnUiThread { DataExporter.exportAndShare(this, csvContent) }
        }
    }
}
