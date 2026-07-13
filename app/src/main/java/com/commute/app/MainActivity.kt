package com.commute.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.ui.theme.CommuteTheme
import com.commute.app.wifi.currentWifiSsid
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CommuteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CommuteScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun CommuteScreen(modifier: Modifier = Modifier, viewModel: CommuteViewModel = viewModel()) {
    val context = LocalContext.current
    val companySsid by viewModel.companySsid.collectAsState()
    val monitoringEnabled by viewModel.monitoringEnabled.collectAsState()
    val isAtWork by viewModel.isAtWork.collectAsState()
    val events by viewModel.events.collectAsState()

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var enableMonitoringAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocationPermission && enableMonitoringAfterPermission) {
            viewModel.setMonitoringEnabled(true)
        }
        enableMonitoringAfterPermission = false
    }

    var currentSsid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hasLocationPermission) {
        while (true) {
            currentSsid = if (hasLocationPermission) currentWifiSsid(context) else null
            delay(3_000)
        }
    }

    fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Commute", style = MaterialTheme.typography.headlineMedium)

        if (!hasLocationPermission) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("와이파이 이름을 읽고 출퇴근을 감지하려면 위치 권한이 필요합니다.")
                    Button(onClick = { requestPermissions() }) { Text("권한 허용") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("현재 연결된 와이파이: ${currentSsid ?: "없음/알 수 없음"}")
                Text("등록된 회사 와이파이: ${companySsid ?: "미등록"}")
                Button(
                    onClick = { currentSsid?.let(viewModel::registerCompanySsid) },
                    enabled = currentSsid != null
                ) { Text("현재 와이파이를 회사 와이파이로 등록") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("자동 출퇴근 감지", modifier = Modifier.padding(end = 8.dp))
                    Switch(
                        checked = monitoringEnabled,
                        enabled = companySsid != null,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasLocationPermission) {
                                enableMonitoringAfterPermission = true
                                requestPermissions()
                            } else {
                                viewModel.setMonitoringEnabled(enabled)
                            }
                        }
                    )
                }
                Text(if (isAtWork) "오늘 상태: 출근 중" else "오늘 상태: 퇴근 / 회사 밖")
            }
        }

        Text("최근 기록", style = MaterialTheme.typography.titleMedium)
        if (events.isEmpty()) {
            Text("아직 기록이 없습니다.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                events.take(50).forEach { event ->
                    Text(formatEvent(event))
                }
            }
        }
    }
}

private fun formatEvent(event: CommuteEvent): String {
    val label = if (event.type == CommuteEventType.ARRIVE) "출근" else "퇴근"
    val time = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(event.timestamp))
    return "$time  $label  (${event.ssid})"
}

@Preview(showBackground = true)
@Composable
fun CommuteScreenPreview() {
    CommuteTheme {
        Text("Commute")
    }
}
