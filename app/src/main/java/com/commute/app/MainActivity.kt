package com.commute.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
                CommuteApp()
            }
        }
    }
}

@Composable
fun CommuteApp(viewModel: CommuteViewModel = viewModel()) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            CommuteScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun CommuteScreen(viewModel: CommuteViewModel = viewModel(), onOpenSettings: () -> Unit = {}) {
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
    var locationServicesEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(hasLocationPermission) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        while (true) {
            currentSsid = if (hasLocationPermission) currentWifiSsid(context) else null
            locationServicesEnabled = locationManager == null ||
                LocationManagerCompat.isLocationEnabled(locationManager)
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Commute", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onOpenSettings) { Text("근무 규칙 설정") }
        }

        if (!hasLocationPermission) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("와이파이 이름을 읽고 출퇴근을 감지하려면 위치 권한이 필요합니다.")
                    Button(onClick = { requestPermissions() }) { Text("권한 허용") }
                }
            }
        } else if (!locationServicesEnabled) {
            // Android requires the device's Location toggle to be on (separately from the
            // app permission) to read the connected Wi-Fi's real SSID; otherwise WifiManager
            // silently returns "<unknown ssid>" forever, which looks like a broken app.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("위치 권한은 허용되어 있지만 기기의 위치 서비스(GPS)가 꺼져 있어 와이파이 이름을 읽을 수 없습니다. 설정에서 위치 서비스를 켜주세요.")
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
    val dateTimeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    return when (event.type) {
        CommuteEventType.ARRIVE -> "${dateTimeFormat.format(Date(event.timestamp))}  출근  (${event.ssid})"
        CommuteEventType.LEAVE -> "${dateTimeFormat.format(Date(event.timestamp))}  퇴근  (${event.ssid})"
        CommuteEventType.AWAY -> {
            val end = event.endTimestamp
            val range = if (end != null) {
                "${dateTimeFormat.format(Date(event.timestamp))}~${timeFormat.format(Date(end))} (${(end - event.timestamp) / 60_000}분)"
            } else {
                dateTimeFormat.format(Date(event.timestamp))
            }
            "$range  이석  (${event.ssid})"
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CommuteScreenPreview() {
    CommuteTheme {
        Text("Commute")
    }
}
