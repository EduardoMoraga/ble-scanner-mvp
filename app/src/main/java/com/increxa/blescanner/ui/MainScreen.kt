package com.increxa.blescanner.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storefront
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

// === BRAND COLORS ===
private val brandColors = mapOf(
    "Apple" to Color(0xFF333333),
    "Samsung" to Color(0xFF1428A0),
    "Google" to Color(0xFF4285F4),
    "Google/Android" to Color(0xFF4285F4),
    "Xiaomi" to Color(0xFFFF6900),
    "Motorola" to Color(0xFF5C5C5C),
    "Huawei" to Color(0xFFCF0A2C),
    "Oppo" to Color(0xFF1A8450),
    "OnePlus" to Color(0xFFEB0028),
    "Realme" to Color(0xFFF5C900),
    "Sony" to Color(0xFF000000),
    "Nokia" to Color(0xFF124191),
    "Nothing" to Color(0xFF000000),
    "Vivo" to Color(0xFF415FFF),
)

// === PROXIMITY COLORS ===
private val proximityVeryClose = Color(0xFF43A047) // < 0.5m green
private val proximityClose = Color(0xFF1E88E5)     // 0.5-1.0m blue
private val proximityInRange = Color(0xFFFB8C00)   // 1.0-1.5m orange
private val exhibitionRed = Color(0xFFE53935)

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
    val avgConfidence by viewModel.avgConfidence.collectAsState()

    val fabColor by animateColorAsState(
        if (isScanning) Color(0xFFE53935) else Color(0xFF1E88E5),
        label = "fabColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BLE Scanner Pro", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Solo celulares | Radio 1.5m | Conf. + Exhib.",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D47A1),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.Download, contentDescription = "Exportar CSV", tint = Color.White)
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
            StatsRow(
                activeCount = activeDevices.size,
                totalUnique = totalUnique,
                avgDwellMs = avgDwell,
                avgConfidence = avgConfidence,
                isScanning = isScanning
            )

            Text(
                "Celulares detectados (1.5m)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (activeDevices.isEmpty() && !isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.BluetoothSearching, null, Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text("Presiona Play para escanear", color = Color.Gray, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Radio: 1.5m | Solo celulares", color = Color.LightGray, fontSize = 12.sp)
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
fun StatsRow(activeCount: Int, totalUnique: Int, avgDwellMs: Long?, avgConfidence: Int?, isScanning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("Cercanos", "$activeCount", if (isScanning) Color(0xFF43A047) else Color.Gray, Modifier.weight(1f))
        StatCard("Unicos", "$totalUnique", Color(0xFF1E88E5), Modifier.weight(1f))
        StatCard("Perm.", formatDuration(avgDwellMs), Color(0xFFFB8C00), Modifier.weight(1f))
        StatCard("Conf.", "${avgConfidence ?: 0}%", Color(0xFF7B1FA2), Modifier.weight(1f))
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 10.sp, color = Color.Gray)
        }
    }
}

@Composable
fun DeviceCard(device: BleDevice) {
    val brandColor = brandColors[device.brand] ?: Color(0xFF757575)
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Proximity color based on avgRssi
    val proximityColor = when {
        device.avgRssi >= -50 -> proximityVeryClose   // < 0.5m
        device.avgRssi >= -56 -> proximityClose        // 0.5-1.0m
        else -> proximityInRange                       // 1.0-1.5m
    }

    val proximityLabel = when {
        device.avgRssi >= -50 -> "< 0.5m"
        device.avgRssi >= -56 -> "~ 1m"
        else -> "~ 1.5m"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Proximity color bar (left edge)
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(proximityColor)
            )

            Spacer(Modifier.width(8.dp))

            // Phone icon with brand color
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(brandColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (device.isStationary) {
                    Icon(Icons.Default.Storefront, null, tint = exhibitionRed, modifier = Modifier.size(22.dp))
                } else {
                    Icon(Icons.Default.PhoneAndroid, null, tint = brandColor, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.width(10.dp))

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                // Brand + Model
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(device.brand ?: "Desconocida")
                            if (!device.model.isNullOrEmpty() && device.model != device.brand) {
                                append(" ")
                                append(device.model)
                            }
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = brandColor,
                        maxLines = 1
                    )

                    if (device.isStationary) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "EXHIB",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .background(exhibitionRed, RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                // Second line: MAC + proximity + confidence
                Text(
                    buildString {
                        append(device.macAddress.takeLast(8))
                        append(" | ")
                        append(proximityLabel)
                        if (device.confidenceScore > 0) {
                            append(" | ${device.confidenceScore}%")
                        }
                    },
                    fontSize = 10.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }

            // Stats column (right)
            Column(
                modifier = Modifier.padding(end = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (device.totalDurationMs > 0) {
                    Text(
                        formatDuration(device.totalDurationMs),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFB8C00)
                    )
                }
                Text("x${device.scanCount}", fontSize = 10.sp, color = Color.Gray)
                Text(
                    timeFormat.format(Date(device.lastSeenAt)),
                    fontSize = 9.sp,
                    color = Color.Gray
                )
            }
        }
    }
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
