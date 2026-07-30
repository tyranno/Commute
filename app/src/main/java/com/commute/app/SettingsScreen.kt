package com.commute.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.commute.app.ble.hasBleScanPermission
import com.commute.app.ble.isBluetoothOn
import com.commute.app.ble.requiredBleScanPermissions
import com.commute.app.ble.scanNearbyBeacons
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.update.UpdateStatus
import com.commute.app.update.canRequestInstallPackages
import com.commute.app.update.currentAppVersionName
import com.commute.app.update.installApkIntent
import com.commute.app.update.unknownSourcesSettingsIntent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the registered-network list: an SSID paired with one of its BSSIDs, or (when
 * [bssid] is null) a network that has no BSSID pinned yet — in that case the SSID *is* the whole
 * entry, so removing this row un-registers the network rather than trimming one AP from it. */
private data class ApRow(val ssid: String, val bssid: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CommuteViewModel = viewModel(),
    onBack: () -> Unit = {},
    onOpenPolicyDocument: () -> Unit = {}
) {
    val companyNetworks by viewModel.companyNetworks.collectAsState()
    val bleEnabled by viewModel.bleEnabled.collectAsState()
    val companyBeaconIds by viewModel.companyBeaconIds.collectAsState()
    val absenceThresholdMinutes by viewModel.absenceThresholdMinutes.collectAsState()
    val autoLeaveAfterAwayMinutes by viewModel.autoLeaveAfterAwayMinutes.collectAsState()
    val leaveMarginMinutes by viewModel.leaveMarginMinutes.collectAsState()
    val workEndMinute by viewModel.workEndMinute.collectAsState()
    val lunchStartMinute by viewModel.lunchStartMinute.collectAsState()
    val lunchEndMinute by viewModel.lunchEndMinute.collectAsState()
    val halfAmStartMinute by viewModel.halfAmStartMinute.collectAsState()
    val halfAmEndMinute by viewModel.halfAmEndMinute.collectAsState()
    val halfPmStartMinute by viewModel.halfPmStartMinute.collectAsState()
    val halfPmEndMinute by viewModel.halfPmEndMinute.collectAsState()
    val showWeekend by viewModel.showWeekend.collectAsState()
    val recoverableCount by viewModel.recoverableCount.collectAsState()
    val language by viewModel.language.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    val s = LocalStrings.current
    val context = LocalContext.current
    val currentVersionName = remember { currentAppVersionName(context) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importBackup)
    }

    // Settings grew past a single scroll — group them into tabs so each screen holds one concern:
    // 감지(어디를 회사로 볼지: Wi-Fi/BLE), 근무 규칙(시간 판정 규칙), 데이터(문서·백업·복구).
    var selectedTab by remember { mutableIntStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.settingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(s.tabDetection) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(s.tabRules) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(s.tabData) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(s.tabApp) })
            }
            when (selectedTab) {
                0 -> SettingsTabColumn {
                    var showWifiSearch by remember { mutableStateOf(false) }
                    // One row per BSSID (an office can span more than one AP, or more than one
                    // Wi-Fi name), plus one row for a network that has no BSSIDs pinned yet — its
                    // ssid is the whole entry, so removing that row un-registers the network.
                    var selectedApRows by remember { mutableStateOf(setOf<ApRow>()) }
                    val apRows = remember(companyNetworks) {
                        companyNetworks.flatMap { network ->
                            if (network.bssids.isEmpty()) listOf(ApRow(network.ssid, null))
                            else network.bssids.sorted().map { ApRow(network.ssid, it) }
                        }
                    }
                    RuleCard(
                        icon = Icons.Filled.Router,
                        title = s.companyApTitle(companyNetworks.sumOf { it.bssids.size })
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (apRows.isEmpty()) {
                                Text(
                                    s.apNotRegisteredWarning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                apRows.forEach { row ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedApRows = if (row in selectedApRows) {
                                                    selectedApRows - row
                                                } else {
                                                    selectedApRows + row
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Checkbox(
                                            checked = row in selectedApRows,
                                            onCheckedChange = { checked ->
                                                selectedApRows = if (checked) selectedApRows + row else selectedApRows - row
                                            }
                                        )
                                        Text(
                                            if (row.bssid != null) "${row.ssid}  ·  ${row.bssid}" else row.ssid,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                if (selectedApRows.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            viewModel.removeCompanyEntries(selectedApRows.mapTo(mutableSetOf()) { it.ssid to it.bssid })
                                            selectedApRows = emptySet()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            s.deleteSelectedAction(selectedApRows.size),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { showWifiSearch = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(s.searchWifiTitle) }
                        }
                    }

                    RuleCard(
                        icon = Icons.Filled.Bluetooth,
                        title = s.beaconTitle
                    ) {
                        BeaconEditor(
                            enabled = bleEnabled,
                            beaconIds = companyBeaconIds,
                            onEnabledChange = viewModel::setBleEnabled,
                            onRegister = viewModel::registerCompanyBeacon,
                            onRemove = viewModel::removeCompanyBeacons
                        )
                    }

                    if (showWifiSearch) {
                        WifiSearchDialog(
                            registeredSsids = companyNetworks.mapTo(mutableSetOf()) { it.ssid },
                            onSelect = { ssid ->
                                viewModel.registerCompanySsid(ssid)
                                showWifiSearch = false
                            },
                            onDismiss = { showWifiSearch = false }
                        )
                    }
                }

                1 -> SettingsTabColumn {
                    RuleCard(
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        title = s.absenceThresholdTitle
                    ) {
                        AbsenceThresholdEditor(
                            minutes = absenceThresholdMinutes,
                            onSave = viewModel::setAbsenceThresholdMinutes
                        )
                    }

                    RuleCard(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = s.autoLeaveTitle
                    ) {
                        AutoLeaveEditor(
                            afterAwayMinutes = autoLeaveAfterAwayMinutes,
                            workEndMinute = workEndMinute,
                            onSaveAfterAwayMinutes = viewModel::setAutoLeaveAfterAwayMinutes,
                            onSaveWorkEndMinute = viewModel::setWorkEndMinute
                        )
                    }

                    RuleCard(
                        icon = Icons.Filled.AccessTime,
                        title = s.leaveMarginTitle
                    ) {
                        LeaveMarginEditor(
                            minutes = leaveMarginMinutes,
                            onSave = viewModel::setLeaveMarginMinutes
                        )
                    }

                    RuleCard(
                        icon = Icons.Filled.Restaurant,
                        title = s.lunchTitle
                    ) {
                        LunchWindowEditor(
                            startMinute = lunchStartMinute,
                            endMinute = lunchEndMinute,
                            onSave = viewModel::setLunchWindow
                        )
                    }

                    RuleCard(
                        icon = Icons.Filled.BeachAccess,
                        title = s.halfDayTitle
                    ) {
                        HalfDayWindowEditor(
                            amStartMinute = halfAmStartMinute,
                            amEndMinute = halfAmEndMinute,
                            pmStartMinute = halfPmStartMinute,
                            pmEndMinute = halfPmEndMinute,
                            onSaveAm = viewModel::setHalfDayAmWindow,
                            onSavePm = viewModel::setHalfDayPmWindow
                        )
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(s.policyDocCardTitle, style = MaterialTheme.typography.titleSmall)
                            }
                            Button(
                                onClick = onOpenPolicyDocument,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(s.viewPolicy) }
                        }
                    }
                }

                2 -> SettingsTabColumn {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(s.backupCardTitle, style = MaterialTheme.typography.titleSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val fileName = "commute_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.json"
                                        exportLauncher.launch(fileName)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(s.backupSave) }
                                OutlinedButton(
                                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(s.backupRestore) }
                            }
                            Text(
                                s.backupRecoveryNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = viewModel::recoverFromJournal,
                                enabled = recoverableCount > 0,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (recoverableCount > 0) s.recoverFromLog(recoverableCount)
                                    else s.noRecoverable
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(s.resetCardTitle, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                s.resetDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { showResetDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text(s.resetButton) }
                        }
                    }
                }

                else -> SettingsTabColumn {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(s.languageCardTitle, style = MaterialTheme.typography.titleSmall)
                            }
                            LanguageSelector(current = language, onSelect = viewModel::setLanguage)
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(s.updateCardTitle, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                s.currentVersionLabel(currentVersionName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            UpdateStatusSection(status = updateStatus, viewModel = viewModel)
                        }
                    }

                    RuleCard(
                        icon = Icons.Filled.Weekend,
                        title = s.weekendTitle
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (showWeekend) s.shown else s.hidden)
                            Switch(checked = showWeekend, onCheckedChange = viewModel::setShowWeekend)
                        }
                    }
                }
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text(s.resetCardTitle) },
                    text = { Text(s.resetDialogMessage) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.resetAllData()
                            showResetDialog = false
                        }) { Text(s.resetButton, color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) { Text(s.cancel) }
                    }
                )
            }
        }
    }
}

/**
 * The 앱 업데이트 card's body: a linear walk through [UpdateStatus] from idle, through checking
 * GitHub, to downloading, to handing off to the system installer. Kept as its own composable
 * (rather than inlined into the settings screen) because it owns two Context-driven side effects
 * — the install intent and the "allow unknown apps" deep link — that don't belong on the
 * ViewModel, which has no Activity to launch them from.
 */
@Composable
private fun UpdateStatusSection(status: UpdateStatus, viewModel: CommuteViewModel) {
    val s = LocalStrings.current
    val context = LocalContext.current

    when (status) {
        is UpdateStatus.Idle -> {
            Button(onClick = viewModel::checkForUpdate, modifier = Modifier.fillMaxWidth()) {
                Text(s.checkForUpdate)
            }
        }
        is UpdateStatus.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(s.checkingForUpdate, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is UpdateStatus.UpToDate -> {
            Text(s.upToDate, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = viewModel::checkForUpdate, modifier = Modifier.fillMaxWidth()) {
                Text(s.checkForUpdate)
            }
        }
        is UpdateStatus.Available -> {
            Text(s.updateAvailable(status.release.version), style = MaterialTheme.typography.bodyMedium)
            // A release exists and the user is one tap from downloading it, but this device may
            // never have granted "install unknown apps" — surfaced here rather than only after
            // the download finishes, so it's not a surprise at the last step.
            if (!canRequestInstallPackages(context)) {
                Text(
                    s.allowUnknownSourcesNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = { context.startActivity(unknownSourcesSettingsIntent(context)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(s.openSettings) }
            } else {
                Button(
                    onClick = { viewModel.downloadUpdate(status.release) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(s.downloadAndInstall) }
            }
        }
        is UpdateStatus.Downloading -> {
            Text(s.downloadingUpdate(status.percent), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        is UpdateStatus.ReadyToInstall -> {
            Button(
                onClick = { context.startActivity(installApkIntent(context, status.apkFile)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(s.installNow) }
        }
        is UpdateStatus.Failed -> {
            Text(
                s.updateCheckFailed(status.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = viewModel::checkForUpdate, modifier = Modifier.fillMaxWidth()) {
                Text(s.tryAgain)
            }
        }
    }
}

/** A read-only field that opens a menu of the three language choices, saving immediately on pick —
 * same auto-save-on-select pattern as the duration dropdowns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    val s = LocalStrings.current
    fun label(language: AppLanguage) = when (language) {
        AppLanguage.SYSTEM -> s.languageSystem
        AppLanguage.KOREAN -> s.languageKorean
        AppLanguage.ENGLISH -> s.languageEnglish
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(s.languageCardTitle) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Each settings tab is a vertically-scrolling stack of cards with consistent padding/spacing —
 * factored out so the three tabs can't drift apart on those details. */
@Composable
private fun SettingsTabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun RuleCard(
    icon: ImageVector,
    title: String,
    editor: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            editor()
        }
    }
}

/** 10-minute steps to an hour, 20-minute steps to 2 hours, 30-minute steps to a 3-hour cap —
 * finer control isn't useful at the low end, and nobody needs to configure past 3 hours. */
private val ABSENCE_THRESHOLD_OPTIONS_MINUTES =
    (10..60 step 10) + (80..120 step 20) + (150..180 step 30)

/** A fixed set of choices tapped and saved immediately (no free-text, no debounce) — picking one
 * is always a complete, valid value, so there's no "still mid-edit" state that a debounced
 * auto-save could flush too early or lose if the app is closed before the delay elapses. */
@Composable
private fun AbsenceThresholdEditor(minutes: Int, onSave: (Int) -> Unit) {
    MinutesDropdown(
        label = LocalStrings.current.absenceThresholdLabel,
        minutes = minutes,
        options = ABSENCE_THRESHOLD_OPTIONS_MINUTES.toList(),
        onSave = onSave
    )
}

/** 0분(보정 안 함)부터 10분까지 1분 단위 — 전파 꼬리는 보통 몇 분이라 이 범위면 충분하고, 1분
 * 단위면 자리 이탈 시각을 세밀하게 맞출 수 있다. */
private val LEAVE_MARGIN_OPTIONS_MINUTES = (0..10).toList()

/** 퇴근 시각을 lastSeenAt에서 이만큼 앞당긴다 — 회사 밖에서도 신호가 잡혀 실제 이탈보다 늦게
 * 찍히는 걸 보정. 자리 이탈 후 남는 전파 꼬리를 사용자가 직접 조절하는 값. */
@Composable
private fun LeaveMarginEditor(minutes: Int, onSave: (Int) -> Unit) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            s.leaveMarginDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MinutesDropdown(
            label = s.leaveMarginPickerLabel,
            minutes = minutes,
            options = LEAVE_MARGIN_OPTIONS_MINUTES,
            onSave = onSave
        )
    }
}

/** Shared duration picker for the rule cards — a read-only field opening a fixed option list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinutesDropdown(label: String, minutes: Int, options: List<Int>, onSave: (Int) -> Unit) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(top = 4.dp)
    ) {
        OutlinedTextField(
            value = s.durationLabel(minutes),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(s.durationLabel(option)) },
                    onClick = {
                        onSave(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Whole hours only: this is "how long before we're sure they went home", a judgement call where
 * 3시간 vs 3시간 30분 is a distinction without a difference. Capped at 6시간 because a longer wait
 * would routinely be pre-empted by the 근무 인정 시간 종료 rule anyway. */
private val AUTO_LEAVE_OPTIONS_MINUTES = (1..6).map { it * 60 }

/** The two ways a 자리비움 turns into a 퇴근 — whichever comes first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoLeaveEditor(
    afterAwayMinutes: Int,
    workEndMinute: Int,
    onSaveAfterAwayMinutes: (Int) -> Unit,
    onSaveWorkEndMinute: (Int) -> Unit
) {
    var showEndPicker by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            s.autoLeaveDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MinutesDropdown(
            label = s.awayDurationLabel,
            minutes = afterAwayMinutes,
            options = AUTO_LEAVE_OPTIONS_MINUTES,
            onSave = onSaveAfterAwayMinutes
        )
        PickerField(
            label = s.workEndLabel,
            value = formatMinuteOfDayToHHmm(workEndMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showEndPicker = true },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showEndPicker) {
        val state = rememberTimePickerState(
            initialHour = workEndMinute / 60,
            initialMinute = workEndMinute % 60,
            is24Hour = true
        )
        TimePickerDialog(
            title = s.workEndLabel,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                onSaveWorkEndMinute(state.hour * 60 + state.minute)
                showEndPicker = false
            }
        ) { TimePicker(state = state) }
    }
}

/** Picked (not typed) so every value is a complete, valid time — same reasoning as
 * [AbsenceThresholdEditor]: no free-text intermediate state that could be lost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LunchWindowEditor(startMinute: Int, endMinute: Int, onSave: (Int, Int) -> Unit) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerField(
            label = s.startLabel,
            value = formatMinuteOfDayToHHmm(startMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showStartPicker = true },
            modifier = Modifier.weight(1f)
        )
        PickerField(
            label = s.endLabel,
            value = formatMinuteOfDayToHHmm(endMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showEndPicker = true },
            modifier = Modifier.weight(1f)
        )
    }

    if (showStartPicker) {
        val state = rememberTimePickerState(initialHour = startMinute / 60, initialMinute = startMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = s.startTimePickerTitle,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                val newStart = state.hour * 60 + state.minute
                if (newStart < endMinute) onSave(newStart, endMinute)
                showStartPicker = false
            }
        ) { TimePicker(state = state) }
    }
    if (showEndPicker) {
        val state = rememberTimePickerState(initialHour = endMinute / 60, initialMinute = endMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = s.endTimePickerTitle,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                val newEnd = state.hour * 60 + state.minute
                if (startMinute < newEnd) onSave(startMinute, newEnd)
                showEndPicker = false
            }
        ) { TimePicker(state = state) }
    }
}

/**
 * 오전/오후 반차의 시간대. 가산 연구소 운영 방안엔 연차/반차 규정이 없어 일반 관행(오전 09:00~13:00,
 * 오후 14:00~18:00)을 기본값으로 두되, 회사 규칙이 다르면 조정할 수 있게 한다. 이 값은 그래프에서
 * 반차·연차 막대의 위치를 잡고, 연차는 오전 시작~오후 종료 전체 구간으로 그려진다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HalfDayWindowEditor(
    amStartMinute: Int,
    amEndMinute: Int,
    pmStartMinute: Int,
    pmEndMinute: Int,
    onSaveAm: (Int, Int) -> Unit,
    onSavePm: (Int, Int) -> Unit
) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            s.halfDayDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(s.leaveHalfAm, style = MaterialTheme.typography.labelMedium)
        TimeRangeRow(
            startMinute = amStartMinute,
            endMinute = amEndMinute,
            onSave = onSaveAm
        )
        Text(s.leaveHalfPm, style = MaterialTheme.typography.labelMedium)
        TimeRangeRow(
            startMinute = pmStartMinute,
            endMinute = pmEndMinute,
            onSave = onSavePm
        )
    }
}

/** A start/end time-of-day picker pair that only commits when start < end — shared by the
 * 오전/오후 반차 rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeRow(startMinute: Int, endMinute: Int, onSave: (Int, Int) -> Unit) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerField(
            label = s.startLabel,
            value = formatMinuteOfDayToHHmm(startMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showStartPicker = true },
            modifier = Modifier.weight(1f)
        )
        PickerField(
            label = s.endLabel,
            value = formatMinuteOfDayToHHmm(endMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showEndPicker = true },
            modifier = Modifier.weight(1f)
        )
    }

    if (showStartPicker) {
        val state = rememberTimePickerState(initialHour = startMinute / 60, initialMinute = startMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = s.startTimePickerTitle,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                val newStart = state.hour * 60 + state.minute
                if (newStart < endMinute) onSave(newStart, endMinute)
                showStartPicker = false
            }
        ) { TimePicker(state = state) }
    }
    if (showEndPicker) {
        val state = rememberTimePickerState(initialHour = endMinute / 60, initialMinute = endMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = s.endTimePickerTitle,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                val newEnd = state.hour * 60 + state.minute
                if (startMinute < newEnd) onSave(startMinute, newEnd)
                showEndPicker = false
            }
        ) { TimePicker(state = state) }
    }
}

/**
 * The BLE-beacon counterpart to the 회사 AP 등록 card: an on/off toggle for parallel detection, a
 * flat one-line-per-token list of registered beacons with checkbox multi-select + batch delete
 * (mirroring the Wi-Fi AP list), plus a "search nearby beacons and tap to register" flow that
 * mirrors the Wi-Fi one. Registering a beacon turns parallel detection on, since picking one is a
 * clear statement of intent to use it.
 *
 * BLE scanning needs BLUETOOTH_SCAN on Android 12+, which isn't part of the app's startup grant
 * (it's opt-in hardware), so both the toggle and the search request it on demand and only proceed
 * once granted — the same shape as the home screen's location request.
 */
@Composable
private fun BeaconEditor(
    enabled: Boolean,
    beaconIds: List<String>,
    onEnabledChange: (Boolean) -> Unit,
    onRegister: (String) -> Unit,
    onRemove: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var showSearch by remember { mutableStateOf(false) }
    var enableAfterGrant by remember { mutableStateOf(false) }
    var openSearchAfterGrant by remember { mutableStateOf(false) }
    var selectedTokens by remember { mutableStateOf(setOf<String>()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = hasBleScanPermission(context)
        if (granted && enableAfterGrant) onEnabledChange(true)
        if (granted && openSearchAfterGrant) showSearch = true
        enableAfterGrant = false
        openSearchAfterGrant = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            s.beaconDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (enabled) s.on else s.off)
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    if (want && !hasBleScanPermission(context)) {
                        enableAfterGrant = true
                        permissionLauncher.launch(requiredBleScanPermissions())
                    } else {
                        onEnabledChange(want)
                    }
                }
            )
        }
        if (beaconIds.isEmpty()) {
            Text(
                s.noRegisteredBeacon,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        } else {
            beaconIds.forEach { token ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedTokens = if (token in selectedTokens) selectedTokens - token else selectedTokens + token
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(
                        checked = token in selectedTokens,
                        onCheckedChange = { checked ->
                            selectedTokens = if (checked) selectedTokens + token else selectedTokens - token
                        }
                    )
                    Text(token, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (selectedTokens.isNotEmpty()) {
                TextButton(
                    onClick = {
                        onRemove(selectedTokens)
                        selectedTokens = emptySet()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.deleteSelectedAction(selectedTokens.size), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        OutlinedButton(
            onClick = {
                if (!hasBleScanPermission(context)) {
                    openSearchAfterGrant = true
                    permissionLauncher.launch(requiredBleScanPermissions())
                } else {
                    showSearch = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(s.searchNearbyBeacon) }
    }

    if (showSearch) {
        BeaconSearchDialog(
            registeredTokens = beaconIds.toSet(),
            onSelect = { token ->
                onRegister(token)
                // Picking a beacon is intent to use it, so switch parallel detection on if it isn't.
                if (!enabled) onEnabledChange(true)
                showSearch = false
            },
            onDismiss = { showSearch = false }
        )
    }
}

/**
 * Scans briefly for nearby office-format beacons and lists their tokens (strongest first) for the
 * user to pick — the BLE mirror of [WifiSearchDialog], including the same already-registered
 * marker per row. Distinguishes "Bluetooth is off" from "found nothing", since the fix for each is
 * different. Not private: also used from [com.commute.app.MainActivity]'s home-card beacon icon,
 * the same way [WifiSearchDialog] is shared with the Wi-Fi icon there.
 */
@Composable
fun BeaconSearchDialog(
    registeredTokens: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onScanResult: (List<String>) -> Unit = {}
) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var tokens by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var noBluetooth by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isBluetoothOn(context)) {
            noBluetooth = true
            scanning = false
            return@LaunchedEffect
        }
        tokens = scanNearbyBeacons(context)
        scanning = false
        onScanResult(tokens)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.searchNearbyBeacon) },
        text = {
            when {
                scanning -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(s.searching)
                }
                noBluetooth -> Text(
                    s.bluetoothOff,
                    color = MaterialTheme.colorScheme.error
                )
                tokens.isEmpty() -> Text(
                    s.noBeaconFound,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(tokens) { token ->
                        val isRegistered = token in registeredTokens
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { if (isRegistered) it else it.clickable { onSelect(token) } }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(token, style = MaterialTheme.typography.bodyLarge)
                            if (isRegistered) {
                                Text(
                                    s.alreadyRegistered,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                TextButton(onClick = { onSelect(token) }) { Text(s.registerAction) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } }
    )
}
