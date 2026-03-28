package com.increxa.blescanner.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.increxa.blescanner.BleApplication
import com.increxa.blescanner.MainActivity
import com.increxa.blescanner.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BleScanService : Service() {

    companion object {
        private const val TAG = "BleScanService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ble_scan_channel"

        const val ACTION_START = "com.increxa.blescanner.START_SCAN"
        const val ACTION_STOP = "com.increxa.blescanner.STOP_SCAN"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var batchJob: Job? = null

    private lateinit var bleScanner: BleScanner

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _deviceCount = MutableStateFlow(0)
    val deviceCount: StateFlow<Int> = _deviceCount

    private var currentSessionId: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): BleScanService = this@BleScanService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        bleScanner = BleScanner(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra("session_id") ?: "session_${System.currentTimeMillis()}"
                val lat = intent.getDoubleExtra("lat", Double.NaN)
                val lng = intent.getDoubleExtra("lng", Double.NaN)
                startScanning(
                    sessionId,
                    if (lat.isNaN()) null else lat,
                    if (lng.isNaN()) null else lng
                )
            }
            ACTION_STOP -> stopScanning()
        }
        return START_STICKY
    }

    fun startScanning(sessionId: String? = null, lat: Double? = null, lng: Double? = null) {
        currentSessionId = sessionId ?: "session_${System.currentTimeMillis()}"

        startForeground(NOTIFICATION_ID, createNotification())

        val started = bleScanner.startScan()
        _isScanning.value = started

        if (started) {
            Log.i(TAG, "Scanning started, session: $currentSessionId")
            serviceScope.launch {
                val app = application as BleApplication
                app.repository.startSession(currentSessionId!!, lat, lng)
            }
            startBatchProcessor()
        } else {
            Log.e(TAG, "Failed to start BLE scan")
            stopSelf()
        }
    }

    fun stopScanning() {
        bleScanner.stopScan()
        _isScanning.value = false
        batchJob?.cancel()

        currentSessionId?.let { sid ->
            serviceScope.launch {
                processBatch()
                val app = application as BleApplication
                app.repository.endSession(sid)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startBatchProcessor() {
        batchJob?.cancel()
        batchJob = serviceScope.launch {
            while (true) {
                delay(BleScanner.BATCH_INTERVAL_MS)
                processBatch()
            }
        }
    }

    private suspend fun processBatch() {
        val results = bleScanner.flushBuffer()
        if (results.isEmpty()) return

        _deviceCount.value = results.size

        val sid = currentSessionId ?: return
        val app = application as BleApplication
        app.repository.recordScanBatch(results, sid)

        val notification = createNotification(results.size)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(deviceCount: Int = 0): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (deviceCount > 0) "$deviceCount celulares detectados (1.5m)"
        else getString(R.string.scan_notification_text)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.scan_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        bleScanner.stopScan()
        serviceScope.cancel()
        super.onDestroy()
    }
}
