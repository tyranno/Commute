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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.data.parseHHmmToMinuteOfDay

@Composable
fun SettingsScreen(viewModel: CommuteViewModel = viewModel(), onBack: () -> Unit = {}) {
    val absenceThresholdMinutes by viewModel.absenceThresholdMinutes.collectAsState()
    val lunchStartMinute by viewModel.lunchStartMinute.collectAsState()
    val lunchEndMinute by viewModel.lunchEndMinute.collectAsState()

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
            Text("근무 규칙 설정", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text("뒤로") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("이석 인정 기준(분)", style = MaterialTheme.typography.titleSmall)
                Text("이 시간 미만으로 회사 와이파이가 끊기면 퇴근이 아니라 이석으로 기록합니다. (기본값: 가산 연구소 운영 방안 기준 10분)")
                AbsenceThresholdEditor(
                    minutes = absenceThresholdMinutes,
                    onSave = viewModel::setAbsenceThresholdMinutes
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("점심시간", style = MaterialTheme.typography.titleSmall)
                Text("이 구간의 단절은 이석 인정 기준과 무관하게 퇴근으로 마감하지 않습니다. (기본값: 12:00~13:00)")
                LunchWindowEditor(
                    startMinute = lunchStartMinute,
                    endMinute = lunchEndMinute,
                    onSave = viewModel::setLunchWindow
                )
            }
        }
    }
}

@Composable
private fun AbsenceThresholdEditor(minutes: Int, onSave: (Int) -> Unit) {
    var text by remember(minutes) { mutableStateOf(minutes.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("분") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.padding(top = 4.dp)
        )
        Button(onClick = {
            text.toIntOrNull()?.takeIf { it > 0 }?.let(onSave)
        }) { Text("저장") }
    }
}

@Composable
private fun LunchWindowEditor(startMinute: Int, endMinute: Int, onSave: (Int, Int) -> Unit) {
    var startText by remember(startMinute) { mutableStateOf(formatMinuteOfDayToHHmm(startMinute)) }
    var endText by remember(endMinute) { mutableStateOf(formatMinuteOfDayToHHmm(endMinute)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = startText,
            onValueChange = { startText = it },
            label = { Text("시작") },
            modifier = Modifier.padding(top = 4.dp)
        )
        OutlinedTextField(
            value = endText,
            onValueChange = { endText = it },
            label = { Text("종료") },
            modifier = Modifier.padding(top = 4.dp)
        )
        Button(onClick = {
            val start = parseHHmmToMinuteOfDay(startText)
            val end = parseHHmmToMinuteOfDay(endText)
            if (start != null && end != null && start < end) {
                onSave(start, end)
            }
        }) { Text("저장") }
    }
}
