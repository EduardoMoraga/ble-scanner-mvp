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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.increxa.blescanner.data.BleDevice
import kotlinx.coroutines.delay
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

// === THEME COLORS ===
private val darkBlue = Color(0xFF0D47A1)
private val tealAccent = Color(0xFF00897B)
private val greenFab = Color(0xFF43A047)
private val redFab = Color(0xFFE53935)
private val sessionGreenBg = Color(0xFFE8F5E9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: BleViewModel,
    onStartScan: (String) -> Unit,
    onStopScan: () -> Unit,
    onExport: () -> Unit
) {
    val isScanning by viewModel.isScanning.collectAsState()
    val activeDevices by viewModel.activeDevices.collectAsState()
    val totalUnique by viewModel.totalUniqueDevices.collectAsState()
    val avgDwell by viewModel.avgDwellTimeMs.collectAsState()
    val avgConfidence by viewModel.avgConfidence.collectAsState()
    val estimatedPeople by viewModel.estimatedPeople.collectAsState()
    val appleDeviceCount by viewModel.appleDeviceCount.collectAsState()
    val exhibitionCount by viewModel.exhibitionCount.collectAsState()
    val currentPdvName by viewModel.currentPdvName.collectAsState()
    val sessionStartTime by viewModel.sessionStartTime.collectAsState()

    var showStartDialog by remember { mutableStateOf(false) }
    var pdvNameInput by remember { mutableStateOf("") }

    val fabColor by animateColorAsState(
        if (isScanning) redFab else greenFab,
        label = "fabColor"
    )

    // Start scan dialog
    if (showStartDialog) {
        StartScanDialog(
            pdvNameInput = pdvNameInput,
            onPdvNameChange = { pdvNameInput = it },
            onConfirm = {
                val name = pdvNameInput.ifBlank { "Sin nombre" }
                showStartDialog = false
                pdvNameInput = ""
                onStartScan(name)
            },
            onDismiss = {
                showStartDialog = false
                pdvNameInput = ""
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Spectra", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Analisis de Trafico en PDV",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = darkBlue,
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
                onClick = {
                    if (isScanning) {
                        onStopScan()
                    } else {
                        showStartDialog = true
                    }
                },
                containerColor = fabColor
            ) {
                Icon(
                    if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
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
            // Session info bar (only when scanning)
            if (isScanning) {
                SessionInfoBar(
                    pdvName = currentPdvName,
                    sessionStartTime = sessionStartTime
                )
            }

            // Hero section — estimated people
            HeroSection(
                estimatedPeople = estimatedPeople,
                appleCount = appleDeviceCount - exhibitionCount,
                isScanning = isScanning
            )

            // Stats row — 3 cards
            StatsRow(
                totalUnique = totalUnique,
                avgDwellMs = avgDwell,
                avgConfidence = avgConfidence
            )

            // Device list section
            if (activeDevices.isEmpty() && !isScanning) {
                // Empty state
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BluetoothSearching,
                            null,
                            Modifier.size(64.dp),
                            tint = Color.Gray
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Spectra",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = darkBlue
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Presiona \u25B6 para analizar trafico",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Radio: 1.5m | Solo celulares",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }
            } else {
                Text(
                    "\uD83D\uDCF1 Dispositivos cercanos (${activeDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

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
fun StartScanDialog(
    pdvNameInput: String,
    onPdvNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo Escaneo", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = pdvNameInput,
                onValueChange = onPdvNameChange,
                label = { Text("Nombre del PDV") },
                placeholder = { Text("ej. Falabella Mall Plaza") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Iniciar", color = darkBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun SessionInfoBar(pdvName: String, sessionStartTime: Long) {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    // Update elapsed time every second
    LaunchedEffect(sessionStartTime) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
            delay(1000)
        }
    }

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    val timeStr = String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(sessionGreenBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                pdvName.ifBlank { "Sin nombre" },
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color(0xFF1B5E20)
            )
        }
        Text(
            timeStr,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = Color(0xFF2E7D32)
        )
    }
}

@Composable
fun HeroSection(estimatedPeople: Int, appleCount: Int, isScanning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val displayText = when {
            estimatedPeople > 0 -> "$estimatedPeople"
            isScanning -> "Analizando..."
            else -> "--"
        }

        Text(
            text = displayText,
            fontSize = if (displayText.length <= 5) 56.sp else 36.sp,
            fontWeight = FontWeight.Bold,
            color = tealAccent,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Personas estimadas",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF424242)
        )

        Spacer(Modifier.height(4.dp))

        Text(
            if (appleCount > 0) "$appleCount Apple detectados \u00D7 correccion Chile"
            else "Estimacion basada en deteccion Apple + factor mercado",
            fontSize = 11.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StatsRow(totalUnique: Int, avgDwellMs: Long?, avgConfidence: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("Dispositivos", "$totalUnique", Color(0xFF1E88E5), Modifier.weight(1f))
        StatCard("Permanencia", formatDuration(avgDwellMs), Color(0xFFFB8C00), Modifier.weight(1f))
        StatCard("Confianza", "${avgConfidence ?: 0}%", Color(0xFF7B1FA2), Modifier.weight(1f))
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
        device.avgRssi >= -50 -> "~0.5m"
        device.avgRssi >= -56 -> "~1m"
        else -> "~1.5m"
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

            // Phone/storefront icon with brand color
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (device.isStationary) exhibitionRed.copy(alpha = 0.12f)
                        else brandColor.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (device.isStationary) {
                    Icon(Icons.Default.Storefront, null, tint = exhibitionRed, modifier = Modifier.size(22.dp))
                } else {
                    Icon(Icons.Default.PhoneAndroid, null, tint = brandColor, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.width(10.dp))

            // Device info — NO MAC address
            Column(modifier = Modifier.weight(1f)) {
                // Line 1: Brand + Model + EXHIB badge
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

                // Line 2: Proximity + confidence — NO MAC
                Text(
                    buildString {
                        append(proximityLabel)
                        if (device.confidenceScore > 0) {
                            append(" | ${device.confidenceScore}%")
                        }
                    },
                    fontSize = 11.sp,
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
