package com.commute.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.data.parseHHmmToMinuteOfDay
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CommuteViewModel = viewModel(),
    onBack: () -> Unit = {},
    onOpenPolicyDocument: () -> Unit = {}
) {
    val absenceThresholdMinutes by viewModel.absenceThresholdMinutes.collectAsState()
    val lunchStartMinute by viewModel.lunchStartMinute.collectAsState()
    val lunchEndMinute by viewModel.lunchEndMinute.collectAsState()

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
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = "자리비움 인정 기준(분)",
                description = "이 시간 미만으로 회사 와이파이가 끊기면 퇴근이 아니라 자리비움으로 기록합니다. (기본값: 가산 연구소 운영 방안 기준 10분)"
            ) {
                AbsenceThresholdEditor(
                    minutes = absenceThresholdMinutes,
                    onSave = viewModel::setAbsenceThresholdMinutes
                )
            }

            RuleCard(
                icon = Icons.Filled.Restaurant,
                title = "점심시간",
                description = "이 구간의 단절은 자리비움 인정 기준과 무관하게 퇴근으로 마감하지 않습니다. (기본값: 현재 운영 중인 점심시간 11:20~12:20)"
            ) {
                LunchWindowEditor(
                    startMinute = lunchStartMinute,
                    endMinute = lunchEndMinute,
                    onSave = viewModel::setLunchWindow
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("근거 문서", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "위 규칙들의 근거가 되는 가산 연구소 운영 방안 문서입니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onOpenPolicyDocument,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("가산 연구소 운영 방안 보기") }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(
    icon: ImageVector,
    title: String,
    description: String,
    editor: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            editor()
        }
    }
}

/** How long to wait after the user stops typing before auto-saving. */
private const val AUTO_SAVE_DEBOUNCE_MS = 800L

@Composable
private fun AbsenceThresholdEditor(minutes: Int, onSave: (Int) -> Unit) {
    var text by remember(minutes) { mutableStateOf(minutes.toString()) }

    fun trySave() {
        text.toIntOrNull()?.takeIf { it > 0 }?.let(onSave)
    }

    LaunchedEffect(text) {
        delay(AUTO_SAVE_DEBOUNCE_MS)
        trySave()
    }
    // Flush a pending valid edit immediately if the user leaves before the debounce fires.
    DisposableEffect(Unit) {
        onDispose { trySave() }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("분") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}

@Composable
private fun LunchWindowEditor(startMinute: Int, endMinute: Int, onSave: (Int, Int) -> Unit) {
    var startText by remember(startMinute) { mutableStateOf(formatMinuteOfDayToHHmm(startMinute)) }
    var endText by remember(endMinute) { mutableStateOf(formatMinuteOfDayToHHmm(endMinute)) }

    fun trySave() {
        val start = parseHHmmToMinuteOfDay(startText)
        val end = parseHHmmToMinuteOfDay(endText)
        if (start != null && end != null && start < end) {
            onSave(start, end)
        }
    }

    LaunchedEffect(startText, endText) {
        delay(AUTO_SAVE_DEBOUNCE_MS)
        trySave()
    }
    DisposableEffect(Unit) {
        onDispose { trySave() }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = startText,
            onValueChange = { startText = it },
            label = { Text("시작") },
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = endText,
            onValueChange = { endText = it },
            label = { Text("종료") },
            modifier = Modifier.weight(1f)
        )
    }
}
