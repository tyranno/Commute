package com.commute.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.commute.app.data.formatMinuteOfDayToHHmm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CommuteViewModel = viewModel(),
    onBack: () -> Unit = {},
    onOpenPolicyDocument: () -> Unit = {}
) {
    val companySsid by viewModel.companySsid.collectAsState()
    val companyBssids by viewModel.companyBssids.collectAsState()
    val absenceThresholdMinutes by viewModel.absenceThresholdMinutes.collectAsState()
    val lunchStartMinute by viewModel.lunchStartMinute.collectAsState()
    val lunchEndMinute by viewModel.lunchEndMinute.collectAsState()
    val showWeekend by viewModel.showWeekend.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importBackup)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("근무 규칙 설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RuleCard(
                icon = Icons.Filled.Router,
                title = "회사 AP 등록 (${companyBssids.size}대)"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (companyBssids.isEmpty()) {
                        Text(
                            "AP가 등록되지 않아 와이파이 이름만으로 감지합니다. 같은 이름의 다른 와이파이도 회사로 인식될 수 있으니, 회사에서 아래 버튼을 눌러 등록하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        companyBssids.sorted().forEach { bssid ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(bssid, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { viewModel.removeCompanyBssid(bssid) }) { Text("삭제") }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::addNearbyCompanyBssids,
                        enabled = companySsid != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("지금 보이는 ${companySsid ?: "회사"} AP 등록") }
                }
            }

            RuleCard(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = "자리비움 인정 기준(분)"
            ) {
                AbsenceThresholdEditor(
                    minutes = absenceThresholdMinutes,
                    onSave = viewModel::setAbsenceThresholdMinutes
                )
            }

            RuleCard(
                icon = Icons.Filled.Restaurant,
                title = "점심시간"
            ) {
                LunchWindowEditor(
                    startMinute = lunchStartMinute,
                    endMinute = lunchEndMinute,
                    onSave = viewModel::setLunchWindow
                )
            }

            RuleCard(
                icon = Icons.Filled.Weekend,
                title = "주말(토·일) 표시"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (showWeekend) "표시함" else "숨김")
                    Switch(checked = showWeekend, onCheckedChange = viewModel::setShowWeekend)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("근거 문서", style = MaterialTheme.typography.titleSmall)
                    }
                    Button(
                        onClick = onOpenPolicyDocument,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("가산 연구소 운영 방안 보기") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("데이터 백업", style = MaterialTheme.typography.titleSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val fileName = "commute_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.json"
                                exportLauncher.launch(fileName)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("백업 저장") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("백업 복원") }
                    }
                }
            }
        }
    }
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

private fun formatThresholdLabel(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0 -> "${minutes}분"
        mins == 0 -> "${hours}시간"
        else -> "${hours}시간 ${mins}분"
    }
}

/** A fixed set of choices tapped and saved immediately (no free-text, no debounce) — picking one
 * is always a complete, valid value, so there's no "still mid-edit" state that a debounced
 * auto-save could flush too early or lose if the app is closed before the delay elapses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbsenceThresholdEditor(minutes: Int, onSave: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(top = 4.dp)
    ) {
        OutlinedTextField(
            value = formatThresholdLabel(minutes),
            onValueChange = {},
            readOnly = true,
            label = { Text("자리비움 인정 기준") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ABSENCE_THRESHOLD_OPTIONS_MINUTES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatThresholdLabel(option)) },
                    onClick = {
                        onSave(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Picked (not typed) so every value is a complete, valid time — same reasoning as
 * [AbsenceThresholdEditor]: no free-text intermediate state that could be lost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LunchWindowEditor(startMinute: Int, endMinute: Int, onSave: (Int, Int) -> Unit) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerField(
            label = "시작",
            value = formatMinuteOfDayToHHmm(startMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showStartPicker = true },
            modifier = Modifier.weight(1f)
        )
        PickerField(
            label = "종료",
            value = formatMinuteOfDayToHHmm(endMinute),
            icon = Icons.Filled.AccessTime,
            onClick = { showEndPicker = true },
            modifier = Modifier.weight(1f)
        )
    }

    if (showStartPicker) {
        val state = rememberTimePickerState(initialHour = startMinute / 60, initialMinute = startMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = "시작 시각 선택",
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
            title = "종료 시각 선택",
            onDismiss = { showEndPicker = false },
            onConfirm = {
                val newEnd = state.hour * 60 + state.minute
                if (startMinute < newEnd) onSave(startMinute, newEnd)
                showEndPicker = false
            }
        ) { TimePicker(state = state) }
    }
}
