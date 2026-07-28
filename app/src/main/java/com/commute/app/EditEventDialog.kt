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
import java.util.Calendar
import java.util.TimeZone

/**
 * Lets the user correct a misdetected record (change its type, its time(s), or exclude it from
 * 기록보기) or, when [isNew] is true, fill in one the service missed entirely (e.g. a wifi/permission
 * hiccup, or the phone being off) — same fields, just an insert instead of an update and no exclude
 * option. [onDelete], despite its name, excludes rather than removes the row — see
 * [CommuteViewModel.excludeEvent]. Date/time are picked with the platform's own DatePicker/TimePicker
 * (well-known, familiar components) rather than typed as free text, so there's no invalid-format
 * guessing.
 *
 * [otherEventsForDuplicateCheck] guards a *blank* add against creating a second 출근 or 퇴근 on a
 * day that already has one — a free-form add has no idea a day is already fully accounted for, so
 * without this a stray tap quietly produces an orphaned duplicate that then throws off that day's
 * session pairing. Left empty (the default) for the 기록 누락 "수정" flow, which targets a specific
 * detected gap and must stay unrestricted — an orphan LEAVE on a day that already has an unrelated
 * ARRIVE/LEAVE pair (a genuine multi-session day) still legitimately needs its own new ARRIVE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventDialog(
    event: CommuteEvent,
    isNew: Boolean,
    onSave: (CommuteEvent) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    otherEventsForDuplicateCheck: List<CommuteEvent> = emptyList()
) {
    var type by remember(event) { mutableStateOf(event.type) }
    var dateMillis by remember(event) { mutableStateOf(startOfDay(event.timestamp)) }
    var hour by remember(event) { mutableStateOf(hourOf(event.timestamp)) }
    var minute by remember(event) { mutableStateOf(minuteOf(event.timestamp)) }
    val endReference = event.endTimestamp ?: event.timestamp
    var endHour by remember(event) { mutableStateOf(hourOf(endReference)) }
    var endMinute by remember(event) { mutableStateOf(minuteOf(endReference)) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // Only meaningful for a blank add of 출근/퇴근 — 자리비움 has no such uniqueness expectation,
    // and an edit of an existing record is never "duplicating" anything.
    val duplicateOfSameDay = isNew && type != CommuteEventType.AWAY &&
        otherEventsForDuplicateCheck.any { it.type == type && startOfDay(it.timestamp) == dateMillis }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val s = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) s.recordAddTitle else s.recordEditTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        CommuteEventType.ARRIVE to s.eventArrive,
                        CommuteEventType.LEAVE to s.eventLeave,
                        CommuteEventType.AWAY to s.eventAway
                    ).forEach { (candidateType, label) ->
                        FilterChip(
                            selected = type == candidateType,
                            onClick = { type = candidateType },
                            label = { Text(label) }
                        )
                    }
                }
                PickerField(
                    label = s.dateLabel,
                    value = s.leaveDate(dateMillis),
                    icon = Icons.Filled.CalendarToday,
                    onClick = { showDatePicker = true }
                )
                PickerField(
                    label = if (type == CommuteEventType.AWAY) s.awayStartTimeLabel else s.timeLabel,
                    value = formatHourMinute(hour, minute),
                    icon = Icons.Filled.AccessTime,
                    onClick = { showTimePicker = true }
                )
                if (type == CommuteEventType.AWAY) {
                    PickerField(
                        label = s.awayEndTimeLabel,
                        value = formatHourMinute(endHour, endMinute),
                        icon = Icons.Filled.AccessTime,
                        onClick = { showEndTimePicker = true }
                    )
                    if (combineDateTime(dateMillis, endHour, endMinute) <= combineDateTime(dateMillis, hour, minute)) {
                        Text(
                            s.endAfterStart,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (duplicateOfSameDay) {
                    Text(
                        if (type == CommuteEventType.ARRIVE) s.duplicateArriveError else s.duplicateLeaveError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (confirmingDelete) {
                    Text(
                        s.excludeExplain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !duplicateOfSameDay,
                onClick = {
                    val timestamp = combineDateTime(dateMillis, hour, minute)
                    val endTimestamp = if (type == CommuteEventType.AWAY) combineDateTime(dateMillis, endHour, endMinute) else null
                    val valid = type != CommuteEventType.AWAY || (endTimestamp != null && endTimestamp > timestamp)
                    if (valid) {
                        onSave(event.copy(type = type, timestamp = timestamp, endTimestamp = endTimestamp))
                    }
                }
            ) { Text(if (isNew) s.add else s.save) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = {
                        if (confirmingDelete) onDelete() else confirmingDelete = true
                    }) { Text(if (confirmingDelete) s.reallyExclude else s.excludeAction, color = MaterialTheme.colorScheme.error) }
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
    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        TimePickerDialog(
            title = if (type == CommuteEventType.AWAY) s.startTimePickerTitle else s.timePickerSelect,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour = state.hour; minute = state.minute; showTimePicker = false }
        ) { TimePicker(state = state) }
    }
    if (showEndTimePicker) {
        val state = rememberTimePickerState(initialHour = endHour, initialMinute = endMinute, is24Hour = true)
        TimePickerDialog(
            title = s.endTimePickerTitle,
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
    val s = LocalStrings.current
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
                    TextButton(onClick = onDismiss) { Text(s.cancel) }
                    TextButton(onClick = onConfirm) { Text(s.confirm) }
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
fun localMidnightToUtcMillis(localMidnight: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMidnight }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

fun utcMillisToLocalMidnight(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
