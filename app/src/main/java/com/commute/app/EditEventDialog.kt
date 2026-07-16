package com.commute.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.MissingRecordFlag
import com.commute.app.data.MissingRecordType
import com.commute.app.data.startOfDay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lets the user correct a misdetected record (change its type, its time(s), or delete it) or,
 * when [isNew] is true, fill in one the service missed entirely (e.g. a wifi/permission hiccup,
 * or the phone being off) — same fields, just an insert instead of an update and no delete option.
 * Date/time are picked with the platform's own DatePicker/TimePicker (well-known, familiar
 * components) rather than typed as free text, so there's no invalid-format guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventDialog(
    event: CommuteEvent,
    isNew: Boolean,
    onSave: (CommuteEvent) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var type by remember(event) { mutableStateOf(event.type) }
    var dateMillis by remember(event) { mutableStateOf(startOfDay(event.timestamp)) }
    var hour by remember(event) { mutableStateOf(hourOf(event.timestamp)) }
    var minute by remember(event) { mutableStateOf(minuteOf(event.timestamp)) }
    val endReference = event.endTimestamp ?: event.timestamp
    var endHour by remember(event) { mutableStateOf(hourOf(endReference)) }
    var endMinute by remember(event) { mutableStateOf(minuteOf(endReference)) }
    var confirmingDelete by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "기록 추가" else "기록 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        CommuteEventType.ARRIVE to "출근",
                        CommuteEventType.LEAVE to "퇴근",
                        CommuteEventType.AWAY to "자리비움"
                    ).forEach { (candidateType, label) ->
                        FilterChip(
                            selected = type == candidateType,
                            onClick = { type = candidateType },
                            label = { Text(label) }
                        )
                    }
                }
                PickerField(
                    label = "날짜",
                    value = formatDateOnly(dateMillis),
                    icon = Icons.Filled.CalendarToday,
                    onClick = { showDatePicker = true }
                )
                PickerField(
                    label = if (type == CommuteEventType.AWAY) "시작 시각" else "시각",
                    value = formatHourMinute(hour, minute),
                    icon = Icons.Filled.AccessTime,
                    onClick = { showTimePicker = true }
                )
                if (type == CommuteEventType.AWAY) {
                    PickerField(
                        label = "종료 시각",
                        value = formatHourMinute(endHour, endMinute),
                        icon = Icons.Filled.AccessTime,
                        onClick = { showEndTimePicker = true }
                    )
                    if (combineDateTime(dateMillis, endHour, endMinute) <= combineDateTime(dateMillis, hour, minute)) {
                        Text(
                            "종료 시각은 시작 시각보다 늦어야 합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (confirmingDelete) {
                    Text(
                        "삭제하면 되돌릴 수 없습니다. 한 번 더 누르면 삭제됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val timestamp = combineDateTime(dateMillis, hour, minute)
                val endTimestamp = if (type == CommuteEventType.AWAY) combineDateTime(dateMillis, endHour, endMinute) else null
                val valid = type != CommuteEventType.AWAY || (endTimestamp != null && endTimestamp > timestamp)
                if (valid) {
                    onSave(event.copy(type = type, timestamp = timestamp, endTimestamp = endTimestamp))
                }
            }) { Text(if (isNew) "추가" else "저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = {
                        if (confirmingDelete) onDelete() else confirmingDelete = true
                    }) { Text(if (confirmingDelete) "정말 삭제" else "삭제", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
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
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("취소") } }
        ) { DatePicker(state = state) }
    }
    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        TimePickerDialog(
            title = if (type == CommuteEventType.AWAY) "시작 시각 선택" else "시각 선택",
            onDismiss = { showTimePicker = false },
            onConfirm = { hour = state.hour; minute = state.minute; showTimePicker = false }
        ) { TimePicker(state = state) }
    }
    if (showEndTimePicker) {
        val state = rememberTimePickerState(initialHour = endHour, initialMinute = endMinute, is24Hour = true)
        TimePickerDialog(
            title = "종료 시각 선택",
            onDismiss = { showEndTimePicker = false },
            onConfirm = { endHour = state.hour; endMinute = state.minute; showEndTimePicker = false }
        ) { TimePicker(state = state) }
    }
}

/** A tappable field styled like a text field but opening a picker instead of a keyboard. */
@Composable
fun PickerField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        trailingIcon = { Icon(icon, contentDescription = null) },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/** Material3 doesn't ship a TimePicker dialog wrapper (unlike DatePickerDialog) — this follows
 * the pattern from the official docs: a plain [Dialog] hosting the [TimePicker] plus buttons. */
@Composable
fun TimePickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.width(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                content()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("취소") }
                    TextButton(onClick = onConfirm) { Text("확인") }
                }
            }
        }
    }
}

/** A blank, unsaved record (id=0, so [com.commute.app.data.CommuteDao.insert] assigns a fresh
 * id) prefilled with [day]'s date and the current time-of-day, for the "add a record the
 * service missed" flow. */
fun blankEventTemplate(day: Long, ssid: String?): CommuteEvent {
    val now = System.currentTimeMillis()
    val timeOfDay = now - startOfDay(now)
    return CommuteEvent(id = 0L, type = CommuteEventType.ARRIVE, ssid = ssid ?: "", timestamp = day + timeOfDay)
}

/** A blank record prefilled to fix a [MissingRecordFlag] — the opposite type, defaulted an hour
 * before/after the flagged event as a plausible starting point the user then adjusts with the
 * date/time pickers. */
fun missingEventTemplate(flag: MissingRecordFlag, ssid: String?): CommuteEvent {
    val oneHourMs = 60 * 60 * 1000L
    return if (flag.type == MissingRecordType.LEAVE_MISSING) {
        CommuteEvent(id = 0L, type = CommuteEventType.LEAVE, ssid = ssid ?: flag.event.ssid, timestamp = flag.event.timestamp + oneHourMs)
    } else {
        CommuteEvent(id = 0L, type = CommuteEventType.ARRIVE, ssid = ssid ?: flag.event.ssid, timestamp = flag.event.timestamp - oneHourMs)
    }
}

private fun hourOf(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)
private fun minuteOf(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.MINUTE)

private fun formatHourMinute(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun formatDateOnly(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREAN).format(Date(timestamp))

private fun combineDateTime(dateMillis: Long, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = dateMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Material3's [androidx.compose.material3.DatePickerState] always represents the selected date
 * as midnight UTC, regardless of device timezone — converting a local-midnight timestamp to/from
 * that representation avoids the picker showing a day off from what was actually stored. */
private fun localMidnightToUtcMillis(localMidnight: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMidnight }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun utcMillisToLocalMidnight(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
