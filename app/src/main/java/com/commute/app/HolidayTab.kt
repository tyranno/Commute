package com.commute.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.commute.app.data.Holiday
import com.commute.app.data.HolidaySource
import com.commute.app.data.startOfDay
import com.commute.app.holiday.HolidaySyncStatus

/**
 * The 휴일 management tab: a sync card at the top (fetches South Korea's public holidays) and a
 * date-ascending list of every holiday — synced or user-declared — below it. Separate from
 * [LeaveTab] because a holiday is calendar reference data (no leave credit, no time range), not a
 * personal 연차/외출 declaration; both feed the home chart, but for different reasons.
 */
@Composable
fun HolidayTab(
    holidays: List<Holiday>,
    syncStatus: HolidaySyncStatus,
    onSync: () -> Unit,
    onSaveHoliday: (Holiday, Long?) -> Unit,
    onDeleteHoliday: (Holiday) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf<Holiday?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HolidaySyncCard(status = syncStatus, onSync = onSync)
            if (holidays.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Event,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        s.emptyHolidayMessage,
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
                    items(holidays.sortedBy { it.date }, key = { it.date }) { holiday ->
                        HolidayRow(holiday, onClick = { editing = holiday })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = s.addHolidayDesc) }
    }

    editing?.let { holiday ->
        HolidayEditDialog(
            holiday = holiday,
            isNew = false,
            onSave = { updated, oldDate -> onSaveHoliday(updated, oldDate); editing = null },
            onDelete = { onDeleteHoliday(holiday); editing = null },
            onDismiss = { editing = null }
        )
    }
    if (adding) {
        HolidayEditDialog(
            holiday = remember { Holiday(date = startOfDay(System.currentTimeMillis()), name = "", source = HolidaySource.CUSTOM) },
            isNew = true,
            onSave = { created, _ -> onSaveHoliday(created, null); adding = false },
            onDelete = null,
            onDismiss = { adding = false }
        )
    }
}

@Composable
private fun HolidaySyncCard(status: HolidaySyncStatus, onSync: () -> Unit) {
    val s = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(s.syncHolidaysCardTitle, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                s.syncHolidaysDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (status) {
                is HolidaySyncStatus.Syncing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(s.syncingHolidays, style = MaterialTheme.typography.bodyMedium)
                }
                is HolidaySyncStatus.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.syncHolidaysSuccess(status.count), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text(s.syncHolidaysButton) }
                }
                is HolidaySyncStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        s.syncHolidaysFailed(status.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text(s.syncHolidaysButton) }
                }
                is HolidaySyncStatus.Idle -> androidx.compose.material3.Button(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(s.syncHolidaysButton) }
            }
        }
    }
}

@Composable
private fun HolidayRow(holiday: Holiday, onClick: () -> Unit) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (holiday.source == HolidaySource.AUTO) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    holiday.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    s.leaveDate(holiday.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                if (holiday.source == HolidaySource.AUTO) s.holidaySourceAuto else s.holidaySourceCustom,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolidayEditDialog(
    holiday: Holiday,
    isNew: Boolean,
    onSave: (Holiday, Long?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var dateMillis by remember(holiday) { mutableStateOf(startOfDay(holiday.date)) }
    var name by remember(holiday) { mutableStateOf(holiday.name) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) s.holidayAddTitle else s.holidayEditTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField(
                    label = s.dateLabel,
                    value = s.leaveDate(dateMillis),
                    icon = Icons.Filled.CalendarToday,
                    onClick = { showDatePicker = true }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s.holidayNameLabel) },
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
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        holiday.copy(date = startOfDay(dateMillis), name = name.trim(), source = HolidaySource.CUSTOM),
                        if (isNew) null else holiday.date
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
}
