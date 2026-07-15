package com.commute.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.DailyWorkStat
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.data.startOfDay
import com.commute.app.data.startOfWeek
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val WEEK_DAYS = 7
private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val CHART_START_MINUTE = 6 * 60 // 06:00
private const val CHART_END_MINUTE = 22 * 60 // 22:00 — matches 근무 인정 시간 upper bound

@Composable
fun StatusTab(
    todayMinutes: Long,
    todayMinutesIncludingLunch: Long,
    weeklyMinutes: Long,
    dailyStats: List<DailyWorkStat>,
    events: List<CommuteEvent>,
    companySsid: String?,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onDeleteEvent: (CommuteEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDay by remember { mutableStateOf<Long?>(null) }
    var weekOffset by remember { mutableStateOf(0) }
    var showTodayIncludingLunch by remember { mutableStateOf(false) }
    val weekStart = startOfWeek(System.currentTimeMillis()) + weekOffset * WEEK_DAYS * DAY_MS

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile(
                        label = if (showTodayIncludingLunch) "오늘 근무시간 (점심 포함)" else "오늘 근무시간",
                        valueMinutes = if (showTodayIncludingLunch) todayMinutesIncludingLunch else todayMinutes,
                        onClick = { showTodayIncludingLunch = !showTodayIncludingLunch }
                    )
                    StatTile("이번주 총 근무시간", weeklyMinutes)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${weekRangeLabel(weekStart)} 출퇴근 시간 (눌러서 상세 기록, 좌우로 스와이프해 다른 주 보기)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (weekOffset != 0) {
                        TextButton(onClick = { weekOffset = 0 }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("이번주로", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                WeeklyRangeChart(
                    stats = dailyStats,
                    weekStart = weekStart,
                    lunchStartMinute = lunchStartMinute,
                    lunchEndMinute = lunchEndMinute,
                    onDayClick = { selectedDay = it },
                    onWeekChange = { delta -> weekOffset = (weekOffset + delta).coerceAtMost(0) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    selectedDay?.let { day ->
        DayDetailDialog(
            day = day,
            events = events,
            workedMinutes = dailyStats.firstOrNull { it.dayStart == day }?.workedMinutes ?: 0L,
            companySsid = companySsid,
            onAddEvent = onAddEvent,
            onUpdateEvent = onUpdateEvent,
            onDeleteEvent = onDeleteEvent,
            onDismiss = { selectedDay = null }
        )
    }
}

@Composable
fun RecordsTab(
    events: List<CommuteEvent>,
    companySsid: String?,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onDeleteEvent: (CommuteEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingEvent by remember { mutableStateOf<CommuteEvent?>(null) }
    var addingEvent by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("이번주 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "기록을 눌러 잘못 인식된 유형이나 시각을 고치거나 삭제할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events.sortedByDescending { it.timestamp }, key = { it.id }) { event ->
                        EventRow(event, onClick = { editingEvent = event })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { addingEvent = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = "빠진 기록 추가") }
    }

    editingEvent?.let { event ->
        EditEventDialog(
            event = event,
            isNew = false,
            onSave = { updated -> onUpdateEvent(updated); editingEvent = null },
            onDelete = { onDeleteEvent(event); editingEvent = null },
            onDismiss = { editingEvent = null }
        )
    }
    if (addingEvent) {
        EditEventDialog(
            event = blankEventTemplate(startOfDay(System.currentTimeMillis()), companySsid),
            isNew = true,
            onSave = { created -> onAddEvent(created); addingEvent = false },
            onDelete = null,
            onDismiss = { addingEvent = false }
        )
    }
}

@Composable
private fun StatTile(label: String, valueMinutes: Long, onClick: (() -> Unit)? = null) {
    Column(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatMinutesAsHours(valueMinutes), style = MaterialTheme.typography.headlineSmall)
    }
}

/**
 * Floating range chart (월~일 of the current week): each day's bar spans from its first
 * 출근 to its last 퇴근 (or "now" if the session is still open), on a time-of-day Y axis —
 * so bar *position* shows when the person arrived/left and bar *length* shows how long they
 * worked, in one view. Single series (one hue throughout), today emphasized. Days are
 * clickable — tapping a bar reports which day via [onDayClick] so the caller can show that
 * day's detailed records.
 */
@Composable
private fun WeeklyRangeChart(
    stats: List<DailyWorkStat>,
    weekStart: Long,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onDayClick: (Long) -> Unit,
    onWeekChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    val days = (0 until WEEK_DAYS).map { weekStart + it * DAY_MS }
    val statByDay = stats.associateBy { it.dayStart }
    val barColor = MaterialTheme.colorScheme.primary
    val lunchColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val cornerPx = with(density) { 4.dp.toPx() }
    val maxBarWidthPx = with(density) { 24.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val axisLabelWidthPx = with(density) { 34.dp.toPx() }

    fun minuteOfDay(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        if (offset.x < axisLabelWidthPx) return@detectTapGestures
                        val chartWidth = size.width - axisLabelWidthPx
                        val slotWidth = chartWidth / days.size
                        val index = ((offset.x - axisLabelWidthPx) / slotWidth).toInt().coerceIn(0, days.size - 1)
                        onDayClick(days[index])
                    }
                }
                .pointerInput(Unit) {
                    val swipeThresholdPx = 56.dp.toPx()
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                        onDragEnd = {
                            // Drag right (finger moves toward positive x) reveals the earlier
                            // week to its left, like sliding a physical page aside.
                            if (totalDrag > swipeThresholdPx) onWeekChange(-1)
                            else if (totalDrag < -swipeThresholdPx) onWeekChange(1)
                            totalDrag = 0f
                        }
                    ) { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    }
                }
        ) {
            val chartLeft = axisLabelWidthPx
            val chartWidth = size.width - chartLeft
            val rangeSpan = (CHART_END_MINUTE - CHART_START_MINUTE).toFloat()

            fun yFor(minute: Int): Float =
                size.height * (1f - (minute - CHART_START_MINUTE).coerceIn(0, CHART_END_MINUTE - CHART_START_MINUTE) / rangeSpan)

            // Gridlines + time labels every 2 hours — the chart now fills the tab's remaining
            // height, so a finer interval than the old 4-hour one reads clearly without crowding.
            var tick = CHART_START_MINUTE
            while (tick <= CHART_END_MINUTE) {
                val y = yFor(tick)
                drawLine(
                    color = axisColor,
                    start = Offset(chartLeft, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
                val measured = textMeasurer.measure(formatMinuteOfDayToHHmm(tick), style = labelStyle)
                drawText(measured, topLeft = Offset(0f, (y - measured.size.height / 2f).coerceIn(0f, size.height - measured.size.height)))
                tick += 2 * 60
            }

            val slotWidth = chartWidth / days.size
            val barWidth = (slotWidth * 0.5f).coerceAtMost(maxBarWidthPx)
            val corner = CornerRadius(cornerPx, cornerPx)
            days.forEachIndexed { index, day ->
                val stat = statByDay[day]
                val arriveAt = stat?.firstArriveAt ?: return@forEachIndexed
                val departAt = if (stat.open) now else (stat.lastLeaveAt ?: return@forEachIndexed)
                val arriveMinute = minuteOfDay(arriveAt)
                val departMinute = minuteOfDay(departAt)
                val top = yFor(departMinute)
                val bottom = yFor(arriveMinute).coerceAtLeast(top + 4f)
                val left = chartLeft + index * slotWidth + (slotWidth - barWidth) / 2f
                val alpha = if (day == today) 1f else 0.55f
                val path = Path().apply {
                    addRoundRect(RoundRect(rect = Rect(left, top, left + barWidth, bottom), cornerRadius = corner))
                }
                drawPath(path, color = barColor.copy(alpha = alpha))

                // Mark the portion of the bar that falls inside the configured lunch window —
                // it's excluded from worked time, so it reads visually as a break in the bar.
                if (lunchStartMinute < lunchEndMinute) {
                    val overlapStart = maxOf(arriveMinute, lunchStartMinute)
                    val overlapEnd = minOf(departMinute, lunchEndMinute)
                    if (overlapEnd > overlapStart) {
                        val lunchTop = yFor(overlapEnd)
                        val lunchBottom = yFor(overlapStart)
                        drawRect(
                            color = lunchColor.copy(alpha = alpha),
                            topLeft = Offset(left, lunchTop),
                            size = Size(barWidth, lunchBottom - lunchTop)
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.width(with(density) { axisLabelWidthPx.toDp() }))
            days.forEach { day ->
                Text(
                    text = weekdayLabel(day),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayDetailDialog(
    day: Long,
    events: List<CommuteEvent>,
    workedMinutes: Long,
    companySsid: String?,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onDeleteEvent: (CommuteEvent) -> Unit,
    onDismiss: () -> Unit
) {
    var editingEvent by remember { mutableStateOf<CommuteEvent?>(null) }
    var addingEvent by remember { mutableStateOf(false) }
    val dayEvents = events.filter { startOfDay(it.timestamp) == day }.sortedBy { it.timestamp }
    val firstArrive = dayEvents.firstOrNull { it.type == CommuteEventType.ARRIVE }
    val lastLeave = dayEvents.lastOrNull { it.type == CommuteEventType.LEAVE }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text(formatDayHeader(day)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DaySummaryItem("출근", firstArrive?.let { formatTimeOnly(it.timestamp) } ?: "-")
                    DaySummaryItem(
                        "퇴근",
                        lastLeave?.let { formatTimeOnly(it.timestamp) } ?: if (firstArrive != null) "근무 중" else "-"
                    )
                    DaySummaryItem("근무시간", formatMinutesAsHours(workedMinutes))
                }
                TextButton(onClick = { addingEvent = true }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("이 날짜에 빠진 기록 추가", style = MaterialTheme.typography.bodySmall)
                }
                if (dayEvents.isEmpty()) {
                    Text("이 날의 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dayEvents.forEach { event -> EventRow(event, onClick = { editingEvent = event }) }
                    }
                }
            }
        }
    )

    editingEvent?.let { event ->
        EditEventDialog(
            event = event,
            isNew = false,
            onSave = { updated -> onUpdateEvent(updated); editingEvent = null },
            onDelete = { onDeleteEvent(event); editingEvent = null },
            onDismiss = { editingEvent = null }
        )
    }
    if (addingEvent) {
        EditEventDialog(
            event = blankEventTemplate(day, companySsid),
            isNew = true,
            onSave = { created -> onAddEvent(created); addingEvent = false },
            onDelete = null,
            onDismiss = { addingEvent = false }
        )
    }
}

@Composable
private fun DaySummaryItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatDayHeader(day: Long): String {
    val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
    val cal = Calendar.getInstance().apply { timeInMillis = day }
    val dateFormat = SimpleDateFormat("M월 d일", Locale.getDefault())
    return "${dateFormat.format(Date(day))} (${names[cal.get(Calendar.DAY_OF_WEEK) - 1]})"
}

/** "이번주" for the current calendar week, otherwise the week's date range (e.g. "7/6~7/12"). */
private fun weekRangeLabel(weekStart: Long): String {
    if (weekStart == startOfWeek(System.currentTimeMillis())) return "이번주"
    val fmt = SimpleDateFormat("M/d", Locale.getDefault())
    val weekEnd = weekStart + (WEEK_DAYS - 1) * DAY_MS
    return "${fmt.format(Date(weekStart))}~${fmt.format(Date(weekEnd))}"
}

private fun weekdayLabel(dayStart: Long): String {
    val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
    val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
    return names[cal.get(Calendar.DAY_OF_WEEK) - 1]
}

private fun formatMinutesAsHours(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}시간 ${mins}분" else "${mins}분"
}

@Composable
private fun EventRow(event: CommuteEvent, onClick: () -> Unit = {}) {
    val (icon, label, tint) = when (event.type) {
        CommuteEventType.ARRIVE -> Triple(Icons.Filled.Work, "출근", Color(0xFF2E7D32))
        CommuteEventType.LEAVE -> Triple(Icons.AutoMirrored.Filled.ExitToApp, "퇴근", Color(0xFFC62828))
        CommuteEventType.AWAY -> Triple(Icons.AutoMirrored.Filled.DirectionsWalk, "자리비움", Color(0xFFF9A825))
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = label, tint = tint)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    event.ssid,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(formatEventTimeRange(event), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatEventTimeRange(event: CommuteEvent): String {
    val dateTimeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val end = event.endTimestamp
    return if (end != null) {
        "${dateTimeFormat.format(Date(event.timestamp))}~${timeFormat.format(Date(end))} (${(end - event.timestamp) / 60_000}분)"
    } else {
        dateTimeFormat.format(Date(event.timestamp))
    }
}

/** A blank, unsaved record (id=0, so [CommuteDao.insert] assigns a fresh id) prefilled with
 * [day]'s date and the current time-of-day, for the "add a record the service missed" flow. */
private fun blankEventTemplate(day: Long, ssid: String?): CommuteEvent {
    val now = System.currentTimeMillis()
    val timeOfDay = now - startOfDay(now)
    return CommuteEvent(id = 0L, type = CommuteEventType.ARRIVE, ssid = ssid ?: "", timestamp = day + timeOfDay)
}

/**
 * Lets the user correct a misdetected record (change its type, its time(s), or delete it) or,
 * when [isNew] is true, fill in one the service missed entirely (e.g. a wifi/permission hiccup,
 * or the phone being off) — same fields, just an insert instead of an update and no delete option.
 */
@Composable
private fun EditEventDialog(
    event: CommuteEvent,
    isNew: Boolean,
    onSave: (CommuteEvent) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var type by remember(event) { mutableStateOf(event.type) }
    var dateText by remember(event) { mutableStateOf(formatDateOnly(event.timestamp)) }
    var timeText by remember(event) { mutableStateOf(formatTimeOnly(event.timestamp)) }
    var endTimeText by remember(event) {
        mutableStateOf(event.endTimestamp?.let { formatTimeOnly(it) } ?: formatTimeOnly(event.timestamp))
    }
    var confirmingDelete by remember { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("날짜 (yyyy-MM-dd)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text(if (type == CommuteEventType.AWAY) "시작 시각 (HH:mm)" else "시각 (HH:mm)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (type == CommuteEventType.AWAY) {
                    OutlinedTextField(
                        value = endTimeText,
                        onValueChange = { endTimeText = it },
                        label = { Text("종료 시각 (HH:mm)") },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                val timestamp = parseDateTime(dateText, timeText)
                val endTimestamp = if (type == CommuteEventType.AWAY) parseDateTime(dateText, endTimeText) else null
                val valid = timestamp != null &&
                    (type != CommuteEventType.AWAY || (endTimestamp != null && endTimestamp > timestamp))
                if (valid) {
                    onSave(event.copy(type = type, timestamp = timestamp!!, endTimestamp = endTimestamp))
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
}

private fun formatDateOnly(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

private fun formatTimeOnly(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun parseDateTime(dateText: String, timeText: String): Long? {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false }
    return try {
        format.parse("${dateText.trim()} ${timeText.trim()}")?.time
    } catch (e: ParseException) {
        null
    }
}
