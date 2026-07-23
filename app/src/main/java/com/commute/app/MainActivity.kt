package com.commute.app

import android.Manifest
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.commute.app.ble.detectCompanyBeacon
import com.commute.app.ble.hasBleScanPermission
import com.commute.app.data.isWithinMinuteOfDayWindow
import com.commute.app.ui.theme.CommuteTheme
import com.commute.app.wifi.WifiMonitorService
import com.commute.app.wifi.currentWifiSsid
import com.commute.app.wifi.isCompanyWifiNearby
import com.commute.app.wifi.nearbyWifiSsids
import com.commute.app.wifi.requestWifiScan
import kotlinx.coroutines.delay

/** Process-scoped so the battery-optimization prompt survives navigating away from home and
 * back, which recreates that destination's composition (and any remember'd flag with it). */
private var batteryOptimizationAsked = false
private var notificationPermissionAsked = false

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
                onBack = { navController.popBackStack() },
                onOpenPolicyDocument = { navController.navigate("policy") }
            )
        }
        composable("policy") {
            PolicyDocumentScreen(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommuteScreen(viewModel: CommuteViewModel = viewModel(), onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val companySsid by viewModel.companySsid.collectAsState()
    val companyBssids by viewModel.companyBssids.collectAsState()
    val bleEnabled by viewModel.bleEnabled.collectAsState()
    val companyBeaconId by viewModel.companyBeaconId.collectAsState()
    val monitoringEnabled by viewModel.monitoringEnabled.collectAsState()
    val isAtWork by viewModel.isAtWork.collectAsState()
    val awaySinceAt by viewModel.awaySinceAt.collectAsState()
    val absenceThresholdMinutes by viewModel.absenceThresholdMinutes.collectAsState()
    val allEvents by viewModel.events.collectAsState()
    val todayWorkedMinutes by viewModel.todayWorkedMinutes.collectAsState()
    val todayWorkedMinutesIncludingLunch by viewModel.todayWorkedMinutesIncludingLunch.collectAsState()
    val weeklyWorkedMinutes by viewModel.weeklyWorkedMinutes.collectAsState()
    val weeklyOvertimeMinutes by viewModel.weeklyOvertimeMinutes.collectAsState()
    val dailyWorkStats by viewModel.dailyWorkStats.collectAsState()
    val leaves by viewModel.leaves.collectAsState()
    val lunchStartMinute by viewModel.lunchStartMinute.collectAsState()
    val lunchEndMinute by viewModel.lunchEndMinute.collectAsState()
    val halfAmStartMinute by viewModel.halfAmStartMinute.collectAsState()
    val halfAmEndMinute by viewModel.halfAmEndMinute.collectAsState()
    val halfPmStartMinute by viewModel.halfPmStartMinute.collectAsState()
    val halfPmEndMinute by viewModel.halfPmEndMinute.collectAsState()
    val showWeekend by viewModel.showWeekend.collectAsState()
    val missingRecords by viewModel.missingRecords.collectAsState()

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

    // Battery-optimization exemption: without this, the OS (Samsung's background app
    // management in particular) can still kill WifiMonitorService outside of a reboot even
    // though it's a foreground service — this is what caused a missed arrival overnight (see
    // project memory). Requesting the exemption once at startup, only if not already granted,
    // makes that kill far less likely in the first place.
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}
    LaunchedEffect(Unit) {
        // Asked at most once per process. This effect lives in the "home" nav destination, whose
        // composition is disposed and recreated on every trip to 설정 — so a user who declines
        // got the system dialog thrown at them again every single time they backed out.
        if (batteryOptimizationAsked) return@LaunchedEffect
        batteryOptimizationAsked = true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                batteryOptLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (e: ActivityNotFoundException) {
                // Some ROMs ship no handler for this intent; it's an optimization, not a
                // requirement, so carry on rather than crashing before the UI is even usable.
            }
        }
    }

    var currentSsid by remember { mutableStateOf<String?>(null) }
    var companyWifiDetectedNow by remember { mutableStateOf(false) }
    var companyBeaconDetectedNow by remember { mutableStateOf(false) }
    var locationServicesEnabled by remember { mutableStateOf(true) }
    var isLunchTimeNow by remember { mutableStateOf(false) }
    // "Now" has to be state, not a bare System.currentTimeMillis() read inside the card: the
    // 자리비움 label depends on how much time has passed since the disconnect, and once the wifi
    // is gone every poll writes identical values, so nothing else would ever invalidate the
    // card's composition. Without this the label sits on 근무중 forever even after the absence
    // threshold has elapsed, disagreeing with the AWAY the service actually recorded.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Bound to STARTED, not to composition. A LaunchedEffect survives the activity being stopped,
    // so this loop used to keep polling WifiManager and re-starting the service from the
    // background — which Android 12+ rejects outright and 14+ rejects for a while-in-use service
    // type, crashing the app precisely when the watchdog was supposed to save it. Suspending
    // while invisible also drops the pointless background binder traffic.
    LaunchedEffect(hasLocationPermission, companySsid, companyBssids, bleEnabled, companyBeaconId, lunchStartMinute, lunchEndMinute) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowTick = System.currentTimeMillis()
                currentSsid = if (hasLocationPermission) currentWifiSsid(context) else null
                val registeredSsid = companySsid
                companyWifiDetectedNow = hasLocationPermission && registeredSsid != null &&
                    isCompanyWifiNearby(context, registeredSsid, companyBssids)
                // BLE presence mirrors the Wi-Fi live poll, gated on the beacon being registered
                // and enabled. detectCompanyBeacon() is a bounded (~6s) active scan that already
                // returns "not nearby" when the permission is missing or Bluetooth is off, so no
                // extra guard is needed beyond skipping it entirely when BLE isn't in use. This is
                // the same OR-with-Wi-Fi presence the background service records via mergePresence,
                // so the card agrees with the service even when only the beacon can see the office.
                val beaconToken = companyBeaconId
                companyBeaconDetectedNow = if (bleEnabled && !beaconToken.isNullOrBlank() &&
                    hasBleScanPermission(context)) {
                    detectCompanyBeacon(context, beaconToken).nearby
                } else {
                    false
                }
                locationServicesEnabled = locationManager == null ||
                    LocationManagerCompat.isLocationEnabled(locationManager)
                isLunchTimeNow = isWithinMinuteOfDayWindow(nowTick, lunchStartMinute, lunchEndMinute)
                // Watchdog: the OS (Samsung's background app management in particular) can kill
                // the foreground WifiMonitorService outside of a reboot, and nothing else
                // restarts it — BootReceiver only fires on ACTUAL reboot. Re-issuing start() is a
                // no-op when the service is already alive, so this just revives it the moment the
                // user opens the app. Only legal while we're actually foreground, hence the gate.
                if (monitoringEnabled && hasLocationPermission) {
                    WifiMonitorService.start(context)
                }
                delay(60_000)
            }
        }
    }

    fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Notification permission is asked for independently of location. It used to ride along only
    // with the location request, so anyone who granted location some other way (system settings,
    // or an older install predating POST_NOTIFICATIONS) never got asked — and every 출근/퇴근/
    // 자리비움 notification then silently no-oped with nothing to indicate why.
    LaunchedEffect(hasLocationPermission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (notificationPermissionAsked) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionAsked = true
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Commute") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "근무 규칙 설정")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top section: sized to its content (well under a third of the screen), so the
            // tabs below get all the leftover space instead of a big empty gap.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!hasLocationPermission) {
                    NoticeCard(
                        icon = Icons.Filled.LocationOn,
                        title = "위치 권한 필요",
                        message = "와이파이 이름을 읽고 출퇴근을 감지하려면 위치 권한이 필요합니다."
                    ) {
                        Button(onClick = { requestPermissions() }) { Text("권한 허용") }
                    }
                } else if (!locationServicesEnabled) {
                    // Android requires the device's Location toggle to be on (separately from the
                    // app permission) to read the connected Wi-Fi's real SSID; otherwise WifiManager
                    // silently returns "<unknown ssid>" forever, which looks like a broken app.
                    NoticeCard(
                        icon = Icons.Filled.Warning,
                        title = "위치 서비스 꺼짐",
                        message = "위치 권한은 허용되어 있지만 기기의 위치 서비스(GPS)가 꺼져 있어 와이파이 이름을 읽을 수 없습니다. 설정에서 위치 서비스를 켜주세요."
                    )
                }

                var showWifiSearch by remember { mutableStateOf(false) }
                CompactStatusCard(
                    isAtWork = isAtWork,
                    companyWifiDetectedNow = companyWifiDetectedNow,
                    bleEnabled = bleEnabled,
                    companyBeaconDetectedNow = companyBeaconDetectedNow,
                    awaySinceAt = awaySinceAt,
                    absenceThresholdMinutes = absenceThresholdMinutes,
                    nowMillis = nowTick,
                    isLunchTimeNow = isLunchTimeNow,
                    currentSsid = currentSsid,
                    companySsid = companySsid,
                    monitoringEnabled = monitoringEnabled,
                    switchEnabled = companySsid != null,
                    onRegister = { currentSsid?.let(viewModel::registerCompanySsid) },
                    onOpenWifiSearch = { showWifiSearch = true },
                    onMonitoringChange = { enabled ->
                        if (enabled && !hasLocationPermission) {
                            enableMonitoringAfterPermission = true
                            requestPermissions()
                        } else {
                            viewModel.setMonitoringEnabled(enabled)
                        }
                    }
                )

                if (showWifiSearch) {
                    WifiSearchDialog(
                        onSelect = { ssid ->
                            viewModel.registerCompanySsid(ssid)
                            showWifiSearch = false
                        },
                        onDismiss = { showWifiSearch = false }
                    )
                }
            }

            // Bottom section: 현황(stats)/기록(records) tabs, filling all remaining space.
            var selectedTab by remember { mutableIntStateOf(0) }
            Column(modifier = Modifier.weight(1f)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("현황") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("기록") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("연차·외출") })
                }
                when (selectedTab) {
                    0 -> StatusTab(
                        todayMinutes = todayWorkedMinutes,
                        todayMinutesIncludingLunch = todayWorkedMinutesIncludingLunch,
                        weeklyMinutes = weeklyWorkedMinutes,
                        weeklyOvertimeMinutes = weeklyOvertimeMinutes,
                        dailyStats = dailyWorkStats,
                        events = allEvents,
                        leaves = leaves,
                        companySsid = companySsid,
                        lunchStartMinute = lunchStartMinute,
                        lunchEndMinute = lunchEndMinute,
                        halfAmStartMinute = halfAmStartMinute,
                        halfAmEndMinute = halfAmEndMinute,
                        halfPmStartMinute = halfPmStartMinute,
                        halfPmEndMinute = halfPmEndMinute,
                        showWeekend = showWeekend,
                        onAddEvent = viewModel::addEvent,
                        onUpdateEvent = viewModel::updateEvent,
                        onDeleteEvent = viewModel::deleteEvent,
                        modifier = Modifier.weight(1f)
                    )
                    1 -> RecordsTab(
                        events = allEvents,
                        missingRecords = missingRecords,
                        companySsid = companySsid,
                        onAddEvent = viewModel::addEvent,
                        onUpdateEvent = viewModel::updateEvent,
                        onDeleteEvent = viewModel::deleteEvent,
                        modifier = Modifier.weight(1f)
                    )
                    else -> LeaveTab(
                        leaves = leaves,
                        lunchStartMinute = lunchStartMinute,
                        lunchEndMinute = lunchEndMinute,
                        onAddLeave = viewModel::addLeave,
                        onUpdateLeave = viewModel::updateLeave,
                        onDeleteLeave = viewModel::deleteLeave,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Four displayable states, derived from the two real signals the app tracks: [isAtWork] (the
 * committed session state the background service maintains) and whether the company Wi-Fi is
 * detected in the live 60-second poll right now. This surfaces the gap between "we can see the
 * wifi" and "the service has officially logged it" (up to a minute later on its own 1-minute
 * cadence) as its own state, instead of leaving the user staring at a stale "퇴근" label.
 */
private enum class CommuteStatus(val label: String) {
    LEFT("퇴근"),
    ARRIVAL_DETECTED("출근 인식됨"),
    WORKING("근무중"),
    AWAY("자리비움")
}

/** The signal dropping isn't itself 자리비움 — [WifiMonitorService] only confirms it once
 * [awaySinceAt] is older than the configured 자리비움 인정 기준, exactly like it does before
 * actually recording a LEAVE/AWAY event. Showing 자리비움 the instant the wifi is lost (before
 * that grace period elapses) would flag a status the backend itself doesn't consider settled
 * yet, so the status card stays on 근무중 until the same threshold has actually passed. */
private fun commuteStatus(
    isAtWork: Boolean,
    detectedNow: Boolean,
    awaySinceAt: Long?,
    absenceThresholdMinutes: Int,
    nowMillis: Long
): CommuteStatus {
    val pastAwayThreshold = awaySinceAt != null &&
        nowMillis - awaySinceAt >= absenceThresholdMinutes * 60_000L
    return when {
        isAtWork && detectedNow -> CommuteStatus.WORKING
        isAtWork && !detectedNow && pastAwayThreshold -> CommuteStatus.AWAY
        isAtWork && !detectedNow -> CommuteStatus.WORKING
        !isAtWork && detectedNow -> CommuteStatus.ARRIVAL_DETECTED
        else -> CommuteStatus.LEFT
    }
}

@Composable
private fun CompactStatusCard(
    isAtWork: Boolean,
    companyWifiDetectedNow: Boolean,
    bleEnabled: Boolean,
    companyBeaconDetectedNow: Boolean,
    awaySinceAt: Long?,
    absenceThresholdMinutes: Int,
    nowMillis: Long,
    isLunchTimeNow: Boolean,
    currentSsid: String?,
    companySsid: String?,
    monitoringEnabled: Boolean,
    switchEnabled: Boolean,
    onRegister: () -> Unit,
    onOpenWifiSearch: () -> Unit,
    onMonitoringChange: (Boolean) -> Unit
) {
    // Presence is OR'd across Wi-Fi and BLE, matching the background service's mergePresence, so
    // the card reads 근무중/출근 인식됨 when *either* radio sees the office — e.g. Wi-Fi off but at
    // your desk by the beacon. BLE only counts when the user has actually enabled it.
    val beaconDetectedNow = bleEnabled && companyBeaconDetectedNow
    val detectedNow = companyWifiDetectedNow || beaconDetectedNow
    val status = commuteStatus(isAtWork, detectedNow, awaySinceAt, absenceThresholdMinutes, nowMillis)
    val containerColor = when (status) {
        CommuteStatus.WORKING -> MaterialTheme.colorScheme.primaryContainer
        CommuteStatus.ARRIVAL_DETECTED, CommuteStatus.AWAY -> MaterialTheme.colorScheme.tertiaryContainer
        CommuteStatus.LEFT -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (status) {
        CommuteStatus.WORKING -> MaterialTheme.colorScheme.onPrimaryContainer
        CommuteStatus.ARRIVAL_DETECTED, CommuteStatus.AWAY -> MaterialTheme.colorScheme.onTertiaryContainer
        CommuteStatus.LEFT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (status) {
        CommuteStatus.WORKING -> Icons.Filled.Work
        CommuteStatus.AWAY -> Icons.AutoMirrored.Filled.DirectionsWalk
        CommuteStatus.ARRIVAL_DETECTED -> if (companyWifiDetectedNow) Icons.Filled.Wifi else Icons.Filled.Bluetooth
        CommuteStatus.LEFT -> Icons.AutoMirrored.Filled.ExitToApp
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isLunchTimeNow) "${status.label} · 점심시간" else status.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor
                    )
                    Text(
                        companySsid?.let { "회사 와이파이: $it" } ?: "회사 와이파이 미등록",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                    if (bleEnabled) {
                        Text(
                            "회사 비콘: ${if (companyBeaconDetectedNow) "감지됨" else "미감지"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor
                        )
                    }
                }
                // Live BLE presence indicator, shown only when the beacon is in use. Purely a
                // status glyph (not clickable): the beacon is registered on the settings screen,
                // unlike Wi-Fi which is registered from this card.
                if (bleEnabled) {
                    Icon(
                        imageVector = if (companyBeaconDetectedNow) Icons.Filled.BluetoothConnected
                            else Icons.Filled.BluetoothDisabled,
                        contentDescription = if (companyBeaconDetectedNow) "회사 비콘 감지됨" else "회사 비콘 미감지",
                        tint = contentColor
                    )
                }
                IconButton(onClick = onOpenWifiSearch) {
                    Icon(
                        imageVector = if (currentSsid != null) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        contentDescription = "주변 와이파이 검색",
                        tint = contentColor
                    )
                }
                Switch(checked = monitoringEnabled, enabled = switchEnabled, onCheckedChange = onMonitoringChange)
            }
            if (companySsid == null || currentSsid != companySsid) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "현재 와이파이: ${currentSsid ?: "없음/알 수 없음"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRegister, enabled = currentSsid != null) {
                        Text("회사 와이파이로 등록")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(message, color = MaterialTheme.colorScheme.onTertiaryContainer)
            action?.invoke()
        }
    }
}

/**
 * Lets the user register a company Wi-Fi without needing to actually connect to it first —
 * requests a fresh scan (best-effort; may be throttled) and lists whatever nearby SSIDs the
 * device can see, ordered by signal strength, so the user just taps the right one.
 */
@Composable
private fun WifiSearchDialog(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var ssids by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        requestWifiScan(context)
        delay(1_500)
        ssids = nearbyWifiSsids(context)
        scanning = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("주변 와이파이 검색") },
        text = {
            when {
                scanning -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("검색 중...")
                }
                ssids.isEmpty() -> Text(
                    "주변에서 와이파이를 찾지 못했습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(ssids) { ssid ->
                        Text(
                            ssid,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(ssid) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Preview(showBackground = true)
@Composable
fun CommuteScreenPreview() {
    CommuteTheme {
        Text("Commute")
    }
}
