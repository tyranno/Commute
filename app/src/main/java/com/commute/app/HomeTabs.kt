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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.sp
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.DailyWorkStat
import com.commute.app.data.MissingRecordFlag
import com.commute.app.data.MissingRecordType
import com.commute.app.data.findMissingRecords
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.data.startOfDay
import com.commute.app.data.startOfWeek
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val WEEK_DAYS = 7
private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val CHART_START_MINUTE = 7 * 60 // 07:00 — matches 근무 인정 시간 lower bound; nothing is ever recognized before this, so no point showing empty space below it
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
    showWeekend: Boolean,
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
                        "이번주 출퇴근 시간(${weekDateRange(weekStart)})",
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
                    showWeekend = showWeekend,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsTab(
    events: List<CommuteEvent>,
    missingRecords: List<MissingRecordFlag>,
    companySsid: String?,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onDeleteEvent: (CommuteEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingEvent by remember { mutableStateOf<CommuteEvent?>(null) }
    var addingEvent by remember { mutableStateOf(false) }
    var fixingFlag by remember { mutableStateOf<MissingRecordFlag?>(null) }

    // Null bound = unrestricted on that side, so the default (both null) shows every record —
    // 기록 탭 used to only ever show "this week" with no way to look further back.
    var filterStart by remember { mutableStateOf<Long?>(null) }
    var filterEnd by remember { mutableStateOf<Long?>(null) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    val filteredEvents = events.filter { event ->
        val day = startOfDay(event.timestamp)
        (filterStart == null || day >= filterStart!!) && (filterEnd == null || day <= filterEnd!!)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PickerField(
                    label = "시작일",
                    value = filterStart?.let(::formatDateShort) ?: "전체",
                    icon = Icons.Filled.CalendarToday,
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.weight(1f)
                )
                PickerField(
                    label = "종료일",
                    value = filterEnd?.let(::formatDateShort) ?: "전체",
                    icon = Icons.Filled.CalendarToday,
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.weight(1f)
                )
                if (filterStart != null || filterEnd != null) {
                    TextButton(onClick = { filterStart = null; filterEnd = null }) { Text("전체") }
                }
            }
            if (missingRecords.isNotEmpty()) {
                MissingRecordsBanner(missingRecords, onFix = { fixingFlag = it })
            }
            // Guards on the list alone, not on the banner too — with a missing-record flag
            // present, an empty date range would otherwise render the edit hint above a blank
            // list with no "이 기간에 기록이 없습니다" to explain it.
            if (filteredEvents.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (filterStart == null && filterEnd == null) "기록이 없습니다." else "이 기간에 기록이 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "기록을 눌러 잘못 인식된 유형이나 시각을 고치거나 삭제할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredEvents.sortedByDescending { it.timestamp }, key = { it.id }) { event ->
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
            // remember-ed: the template reads the clock, so recomputing it inline produced a new
            // (unequal) event on every recomposition, and EditEventDialog keys its editing state
            // on that object — a background ARRIVE landing mid-entry silently reset the user's
            // chosen type/date/time back to "now".
            event = remember { blankEventTemplate(startOfDay(System.currentTimeMillis()), companySsid) },
            isNew = true,
            onSave = { created -> onAddEvent(created); addingEvent = false },
            onDelete = null,
            onDismiss = { addingEvent = false }
        )
    }
    fixingFlag?.let { flag ->
        EditEventDialog(
            event = missingEventTemplate(flag, companySsid),
            isNew = true,
            onSave = { created -> onAddEvent(created); fixingFlag = null },
            onDelete = null,
            onDismiss = { fixingFlag = null }
        )
    }

    if (showStartDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localMidnightToUtcMillis(filterStart ?: startOfDay(System.currentTimeMillis()))
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { filterStart = utcMillisToLocalMidnight(it) }
                    showStartDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showStartDatePicker = false }) { Text("취소") } }
        ) { DatePicker(state = state) }
    }
    if (showEndDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localMidnightToUtcMillis(filterEnd ?: startOfDay(System.currentTimeMillis()))
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { filterEnd = utcMillisToLocalMidnight(it) }
                    showEndDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showEndDatePicker = false }) { Text("취소") } }
        ) { DatePicker(state = state) }
    }
}

private fun formatDateShort(timestamp: Long): String =
    SimpleDateFormat("M/d", Locale.getDefault()).format(Date(timestamp))

/** Lists ARRIVE/LEAVE events missing their other half (see [findMissingRecords]) with a quick
 * "add the missing one" action per row — these are the same cases [computeDailyWorkStats]
 * otherwise swallows silently, so surfacing them here is what lets the user actually notice and
 * fix the gap instead of a day quietly losing worked time. */
@Composable
private fun MissingRecordsBanner(flags: List<MissingRecordFlag>, onFix: (MissingRecordFlag) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "기록 누락 ${flags.size}건",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            flags.forEach { flag ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val message = when (flag.type) {
                        MissingRecordType.LEAVE_MISSING -> "${formatEventTimeRange(flag.event)} 출근 이후 퇴근 기록 없음"
                        MissingRecordType.ARRIVE_MISSING -> "${formatEventTimeRange(flag.event)} 퇴근 이전 출근 기록 없음"
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onFix(flag) }) {
                        Text(if (flag.type == MissingRecordType.LEAVE_MISSING) "퇴근 추가" else "출근 추가")
                    }
                }
            }
        }
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
    showWeekend: Boolean,
    onDayClick: (Long) -> Unit,
    onWeekChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    // weekStart is always a Monday (startOfWeek), so the first 5 slots are 월~금 — 토·일 (the
    // last two) are left out entirely unless showWeekend is on, rather than just dimmed/skipped,
    // so the chart doesn't waste two columns on days that are almost never worked.
    val visibleDayCount = if (showWeekend) WEEK_DAYS else WEEK_DAYS - 2
    val days = (0 until visibleDayCount).map { weekStart + it * DAY_MS }
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
    // Smaller than the axis labels so the per-bar times read as annotations on the bar rather
    // than competing with the axis itself.
    val barTimeStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor, fontSize = 9.sp)
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
                // size.height can be smaller than the label (e.g. a transient tiny layout pass,
                // split-screen/multi-window), which would make the upper bound negative and
                // crash coerceIn — clamp it to never go below the lower bound.
                val maxLabelY = (size.height - measured.size.height).coerceAtLeast(0f)
                drawText(measured, topLeft = Offset(0f, (y - measured.size.height / 2f).coerceIn(0f, maxLabelY)))
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

                // Times annotated directly on the bar — arrival just under its bottom edge,
                // departure just above its top edge — so the chart answers "몇 시에 출퇴근했나"
                // without tapping. Centered on the bar and clamped inside the canvas; the upper
                // bounds go through coerceAtLeast(0f) first because a degenerate canvas height
                // would otherwise make coerceIn's range invalid and crash (this chart has hit
                // exactly that before).
                val labelGap = 2.dp.toPx()
                val centerX = left + barWidth / 2f
                fun drawBarTime(text: String, y: Float) {
                    val measured = textMeasurer.measure(text, style = barTimeStyle)
                    val maxX = (size.width - measured.size.width).coerceAtLeast(0f)
                    val maxY = (size.height - measured.size.height).coerceAtLeast(0f)
                    drawText(
                        measured,
                        topLeft = Offset(
                            (centerX - measured.size.width / 2f).coerceIn(0f, maxX),
                            y.coerceIn(0f, maxY)
                        )
                    )
                }
                drawBarTime(formatTimeOnly(arriveAt), bottom + labelGap)
                // An open session's "top" is just the current time, not a departure — labelling
                // it 퇴근 would be a time the user hasn't actually left at.
                if (!stat.open) {
                    val departMeasured = textMeasurer.measure(formatTimeOnly(departAt), style = barTimeStyle)
                    drawBarTime(formatTimeOnly(departAt), top - departMeasured.size.height - labelGap)
                }

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
            event = remember(day) { blankEventTemplate(day, companySsid) },
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

/** The displayed week's calendar date range, e.g. "7/6~7/12" — shown regardless of whether
 * it's the current week or one paged back to, so the heading always says which days it is. */
private fun weekDateRange(weekStart: Long): String {
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
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
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

private fun formatTimeOnly(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
