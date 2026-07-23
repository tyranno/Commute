package com.commute.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.commute.app.data.LeaveEntry
import com.commute.app.data.LeaveType
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.data.leaveCreditMinutes
import com.commute.app.data.startOfDay

/**
 * The 연차·외출 management tab: lists declared 연차/반차/외출 records (newest first) and lets the
 * user add, edit, or delete them. These feed the same work-time totals and the home chart's hollow
 * leave bars — they're the manual counterpart to the RF-detected 출근/퇴근 records.
 */
@Composable
fun LeaveTab(
    leaves: List<LeaveEntry>,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onAddLeave: (LeaveEntry) -> Unit,
    onUpdateLeave: (LeaveEntry) -> Unit,
    onDeleteLeave: (LeaveEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf<LeaveEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (leaves.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.BeachAccess,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    s.emptyLeaveMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(leaves, key = { it.id }) { leave ->
                    LeaveRow(leave, lunchStartMinute, lunchEndMinute, onClick = { editing = leave })
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = s.addLeaveOutingDesc) }
    }

    editing?.let { leave ->
        LeaveEditDialog(
            entry = leave,
            isNew = false,
            onSave = { onUpdateLeave(it); editing = null },
            onDelete = { onDeleteLeave(leave); editing = null },
            onDismiss = { editing = null }
        )
    }
    if (adding) {
        LeaveEditDialog(
            entry = remember { blankLeaveTemplate() },
            isNew = true,
            onSave = { onAddLeave(it); adding = false },
            onDelete = null,
            onDismiss = { adding = false }
        )
    }
}

@Composable
private fun LeaveRow(leave: LeaveEntry, lunchStartMinute: Int, lunchEndMinute: Int, onClick: () -> Unit) {
    val s = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.BeachAccess,
                contentDescription = s.leaveLabel(leave.type),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(s.leaveLabel(leave.type), style = MaterialTheme.typography.bodyMedium)
                val subtitle = buildString {
                    append(s.leaveDate(leave.date))
                    if (leave.type == LeaveType.OUTING && leave.startMinute != null && leave.endMinute != null) {
                        append(" · ${formatMinuteOfDayToHHmm(leave.startMinute)}~${formatMinuteOfDayToHHmm(leave.endMinute)}")
                    }
                    if (leave.note.isNotBlank()) append(" · ${leave.note}")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                creditText(leave, lunchStartMinute, lunchEndMinute, s),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LeaveEditDialog(
    entry: LeaveEntry,
    isNew: Boolean,
    onSave: (LeaveEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var type by remember(entry) { mutableStateOf(entry.type) }
    var dateMillis by remember(entry) { mutableStateOf(startOfDay(entry.date)) }
    var startMinute by remember(entry) { mutableStateOf(entry.startMinute ?: DEFAULT_OUTING_START_MINUTE) }
    var endMinute by remember(entry) { mutableStateOf(entry.endMinute ?: DEFAULT_OUTING_END_MINUTE) }
    var note by remember(entry) { mutableStateOf(entry.note) }
    var confirmingDelete by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val outingValid = type != LeaveType.OUTING || endMinute > startMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) s.leaveAddTitle else s.leaveEditTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // FlowRow, not Row: the four type chips (연차/오전 반차/오후 반차/외출) overflow a
                // single fixed row on a narrow dialog, which pushed 외출 off-screen entirely — now
                // they wrap to a second line so every type is reachable.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeaveType.entries.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(s.leaveLabel(candidate)) }
                        )
                    }
                }
                PickerField(
                    label = s.dateLabel,
                    value = s.leaveDate(dateMillis),
                    icon = Icons.Filled.CalendarToday,
                    onClick = { showDatePicker = true }
                )
                if (type == LeaveType.OUTING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickerField(
                            label = s.startLabel,
                            value = formatMinuteOfDayToHHmm(startMinute),
                            icon = Icons.Filled.CalendarToday,
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f)
                        )
                        PickerField(
                            label = s.endLabel,
                            value = formatMinuteOfDayToHHmm(endMinute),
                            icon = Icons.Filled.CalendarToday,
                            onClick = { showEndPicker = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!outingValid) {
                        Text(
                            s.endAfterStart,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(s.noteOptional) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirmingDelete) {
                    Text(
                        s.deleteIrreversible,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = outingValid,
                onClick = {
                    val isOuting = type == LeaveType.OUTING
                    onSave(
                        entry.copy(
                            type = type,
                            date = startOfDay(dateMillis),
                            startMinute = if (isOuting) startMinute else null,
                            endMinute = if (isOuting) endMinute else null,
                            note = note.trim()
                        )
                    )
                }
            ) { Text(if (isNew) s.add else s.save) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = {
                        if (confirmingDelete) onDelete() else confirmingDelete = true
                    }) { Text(if (confirmingDelete) s.reallyDelete else s.delete, color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(s.cancel) }
            }
        }
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = localMidnightToUtcMillis(dateMillis))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dateMillis = utcMillisToLocalMidnight(it) }
                    showDatePicker = false
                }) { Text(s.confirm) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(s.cancel) } }
        ) { DatePicker(state = state) }
    }
    if (showStartPicker) {
        val state = rememberTimePickerState(initialHour = startMinute / 60, initialMinute = startMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = s.startTimePickerTitle,
            onDismiss = { showStartPicker = false },
            onConfirm = { startMinute = state.hour * 60 + state.minute; showStartPicker = false }
        ) { TimePicker(state = state) }
    }
    if (showEndPicker) {
        val state = rememberTimePickerState(initialHour = endMinute / 60, initialMinute = endMinute % 60, is24Hour = true)
        TimePickerDialog(
            title = s.endTimePickerTitle,
            onDismiss = { showEndPicker = false },
            onConfirm = { endMinute = state.hour * 60 + state.minute; showEndPicker = false }
        ) { TimePicker(state = state) }
    }
}

private const val DEFAULT_OUTING_START_MINUTE = 13 * 60
private const val DEFAULT_OUTING_END_MINUTE = 14 * 60

private fun blankLeaveTemplate(): LeaveEntry =
    LeaveEntry(id = 0L, type = LeaveType.ANNUAL, date = startOfDay(System.currentTimeMillis()))

private fun creditText(leave: LeaveEntry, lunchStartMinute: Int, lunchEndMinute: Int, strings: Strings): String {
    val minutes = leaveCreditMinutes(leave.type, leave.startMinute, leave.endMinute, lunchStartMinute, lunchEndMinute)
    return strings.durationLabel(minutes.toInt())
}
