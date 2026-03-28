package com.increxa.blescanner.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.increxa.blescanner.data.BleDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: BleViewModel,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onExport: () -> Unit
) {
    val isScanning by viewModel.isScanning.collectAsState()
    val activeDevices by viewModel.activeDevices.collectAsState()
    val totalUnique by viewModel.totalUniqueDevices.collectAsState()
    val avgDwell by viewModel.avgDwellTimeMs.collectAsState()
    val totalScans by viewModel.totalScanResults.collectAsState()

    val fabColor by animateColorAsState(
        if (isScanning) Color(0xFFE53935) else Color(0xFF1E88E5),
        label = "fabColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Scanner", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1565C0),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Exportar CSV",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (isScanning) onStopScan() else onStartScan() },
                containerColor = fabColor
            ) {
                Icon(
                    if (isScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isScanning) "Detener" else "Iniciar",
                    tint = Color.White
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5))
        ) {
            // Stats cards
            StatsRow(
                activeCount = activeDevices.size,
                totalUnique = totalUnique,
                avgDwellMs = avgDwell,
                totalScans = totalScans,
                isScanning = isScanning
            )

            // Device list
            Text(
                "Dispositivos detectados",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (activeDevices.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Presiona Play para escanear",
                            color = Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(activeDevices, key = { it.macAddress }) { device ->
                        DeviceCard(device)
                    }
                }
            }
        }
    }
}

@Composable
fun StatsRow(
    activeCount: Int,
    totalUnique: Int,
    avgDwellMs: Long?,
    totalScans: Int,
    isScanning: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            label = "Activos",
            value = "$activeCount",
            color = if (isScanning) Color(0xFF43A047) else Color.Gray,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Unicos",
            value = "$totalUnique",
            color = Color(0xFF1E88E5),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Perm. Avg",
            value = formatDuration(avgDwellMs),
            color = Color(0xFFFB8C00),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Lecturas",
            value = if (totalScans > 1000) "${totalScans / 1000}K" else "$totalScans",
            color = Color(0xFF8E24AA),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                label,
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun DeviceCard(device: BleDevice) {
    val distanceColor = when {
        estimateCurrentDistance(device) < 1.0 -> Color(0xFF43A047)  // Green = very close
        estimateCurrentDistance(device) < 3.0 -> Color(0xFFFB8C00)  // Orange = medium
        else -> Color(0xFFE53935)  // Red = far
    }

    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(distanceColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    tint = distanceColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.deviceName ?: device.macAddress,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    buildString {
                        append(device.deviceType ?: "Unknown")
                        if (device.deviceName != null) {
                            append(" | ${device.macAddress.takeLast(8)}")
                        }
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // Stats
            Column(horizontalAlignment = Alignment.End) {
                // Permanence
                if (device.totalDurationMs > 0) {
                    Text(
                        formatDuration(device.totalDurationMs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFB8C00)
                    )
                }
                Text(
                    "x${device.scanCount}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Text(
                    timeFormat.format(Date(device.lastSeenAt)),
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

private fun estimateCurrentDistance(device: BleDevice): Double {
    // Rough estimate: use scan count and recency as proxy
    val ageMs = System.currentTimeMillis() - device.lastSeenAt
    return if (ageMs < 5000) 1.0 else if (ageMs < 15000) 3.0 else 10.0
}

private fun formatDuration(ms: Long?): String {
    if (ms == null || ms == 0L) return "--"
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
