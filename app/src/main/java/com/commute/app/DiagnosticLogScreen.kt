package com.commute.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.commute.app.data.DiagnosticEvent
import com.commute.app.data.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단 로그 뷰어: one day at a time, every poll [com.commute.app.wifi.WifiMonitorService] ran —
 * what it saw (Wi-Fi/BLE) and what it decided (or didn't). For tracing a bad recording (e.g. a
 * 퇴근 landing right next to its 출근) back to what detection actually reported at the time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogScreen(viewModel: CommuteViewModel = viewModel(), onBack: () -> Unit = {}) {
    val s = LocalStrings.current
    var selectedDay by remember { mutableLongStateOf(startOfDay(System.currentTimeMillis())) }
    val today = remember { startOfDay(System.currentTimeMillis()) }
    val events by remember(selectedDay) { viewModel.diagnosticEventsForDay(selectedDay) }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.diagnosticLogTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { selectedDay -= DAY_MILLIS_DIAGNOSTIC }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = s.back)
                }
                Text(s.dayHeader(selectedDay), style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = { selectedDay += DAY_MILLIS_DIAGNOSTIC },
                    enabled = selectedDay < today
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = s.back)
                }
            }

            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.diagnosticLogEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(events, key = { it.id }) { event -> DiagnosticEventRow(event) }
                }
            }
        }
    }
}

private const val DAY_MILLIS_DIAGNOSTIC = 24 * 60 * 60 * 1000L
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent) {
    val s = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(timeFormat.format(Date(event.timestamp)), style = MaterialTheme.typography.labelSmall)
            DetectionChip(
                icon = Icons.Filled.Router,
                label = if (event.wifiNearby) s.diagnosticWifiDetected else s.diagnosticWifiNotDetected,
                active = event.wifiNearby
            )
            DetectionChip(
                icon = Icons.Filled.Bluetooth,
                label = when {
                    event.bleSkipped -> s.diagnosticBleSkipped
                    event.bleNearby -> s.diagnosticBleDetected
                    else -> s.diagnosticBleNotDetected
                },
                active = event.bleNearby,
                muted = event.bleSkipped
            )
            if (event.action != null) {
                Text(
                    s.diagnosticActionLabel(event.action),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            if (event.reason != null) {
                Text(
                    s.diagnosticReasonLabel(event.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DetectionChip(icon: ImageVector, label: String, active: Boolean, muted: Boolean = false) {
    val color = when {
        active -> MaterialTheme.colorScheme.primary
        muted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
