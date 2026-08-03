package com.commute.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.commute.app.ui.theme.ExcludedDark
import com.commute.app.ui.theme.ExcludedLight
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.absentMinuteSpans
import com.commute.app.data.awayDisplayRange
import com.commute.app.data.awayMinutesExcludingLunch
import com.commute.app.data.MinuteSpan
import com.commute.app.data.subtractSpans
import com.commute.app.data.DAILY_REQUIRED_MINUTES
import com.commute.app.data.DailyWorkStat
import com.commute.app.data.Holiday
import com.commute.app.data.LeaveEntry
import com.commute.app.data.LeaveType
import com.commute.app.data.MissingRecordFlag
import com.commute.app.data.MissingRecordType
import com.commute.app.data.findMissingRecords
import com.commute.app.data.formatMinuteOfDayToHHmm
import com.commute.app.data.overtimeMinutesForWeek
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
    weeklyOvertimeMinutes: Long,
    dailyStats: List<DailyWorkStat>,
    events: List<CommuteEvent>,
    leaves: List<LeaveEntry>,
    holidays: List<Holiday>,
    companySsid: String?,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    halfAmStartMinute: Int,
    halfAmEndMinute: Int,
    halfPmStartMinute: Int,
    halfPmEndMinute: Int,
    showWeekend: Boolean,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onExcludeEvent: (CommuteEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var selectedDay by remember { mutableStateOf<Long?>(null) }
    var weekOffset by remember { mutableStateOf(0) }
    var showTodayIncludingLunch by remember { mutableStateOf(false) }
    // Tap-to-reveal 이번주 초과근무: not shown at rest, surfaced on tap then auto-hidden after 10초.
    // Tapping again while it's shown reverts immediately; a fresh reveal bumps
    // `overtimeRevealToken`, which restarts the auto-revert timer below.
    var overtimeRevealToken by remember { mutableStateOf(0) }
    var showTotalOvertime by remember { mutableStateOf(false) }
    LaunchedEffect(overtimeRevealToken) {
        if (overtimeRevealToken > 0) {
            showTotalOvertime = true
            delay(10_000)
            showTotalOvertime = false
        }
    }
    val weekStart = startOfWeek(System.currentTimeMillis()) + weekOffset * WEEK_DAYS * DAY_MS

    // Paging the chart to another week used to leave these two tiles stuck on 이번주's figures —
    // confusing since the chart right below them had already moved on. While weekOffset == 0
    // they still read straight off the ViewModel flows (which apply extra bounds against a
    // mistyped future date, see weeklyWorkedMinutes); any other week is recomputed here from the
    // same dailyStats the chart itself uses, scoped to that week's own date range.
    val isThisWeek = weekOffset == 0
    val displayedWeekday = run {
        val now = System.currentTimeMillis()
        val todayOffset = ((startOfDay(now) - startOfWeek(now)) / DAY_MS).toInt()
        weekStart + todayOffset * DAY_MS
    }
    val displayedDayStat = dailyStats.firstOrNull { it.dayStart == displayedWeekday }
    val displayedDayMinutes = if (isThisWeek) todayMinutes else displayedDayStat?.creditedMinutes ?: 0L
    val displayedDayMinutesInclLunch = if (isThisWeek) todayMinutesIncludingLunch
        else displayedDayStat?.let { it.rawSpanMinutes + it.leaveMinutes } ?: 0L
    val displayedWeekEnd = weekStart + WEEK_DAYS * DAY_MS
    val displayedWeekMinutes = if (isThisWeek) weeklyMinutes
        else dailyStats.filter { it.dayStart in weekStart until displayedWeekEnd }.sumOf { it.creditedMinutes }
    val displayedWeekOvertimeMinutes = if (isThisWeek) weeklyOvertimeMinutes
        else overtimeMinutesForWeek(dailyStats, weekStart, System.currentTimeMillis())

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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = if (showTodayIncludingLunch) {
                            if (isThisWeek) s.todayWorkedInclLunch else s.weekdayWorkedInclLunch(s.weekday(displayedWeekday))
                        } else {
                            if (isThisWeek) s.todayWorked else s.weekdayWorked(s.weekday(displayedWeekday))
                        },
                        valueText = s.workHoursMinutes(if (showTodayIncludingLunch) displayedDayMinutesInclLunch else displayedDayMinutes),
                        modifier = Modifier.weight(1f),
                        onClick = { showTodayIncludingLunch = !showTodayIncludingLunch }
                    )
                    // Tap to briefly reveal 이번주 초과근무(이번주 기록 누적 +/−) for ~10초, then it reverts
                    // back to 이번주 총 근무시간 on its own. Tapping again while shown reverts at once —
                    // overtime isn't shown at rest to keep the card calm. Scoped to this week to match
                    // the 이번주 총 근무시간 it toggles from — the same 이번주 기록 basis (오늘 제외).
                    StatTile(
                        label = if (showTotalOvertime) {
                            if (isThisWeek) s.weeklyOvertime else s.selectedWeekOvertime
                        } else {
                            if (isThisWeek) s.weeklyTotal else s.selectedWeekTotal
                        },
                        valueText = if (showTotalOvertime) s.signedMinutes(displayedWeekOvertimeMinutes)
                            else s.workHoursMinutes(displayedWeekMinutes),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (showTotalOvertime) showTotalOvertime = false
                            else overtimeRevealToken++
                        }
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        s.weekCommuteHeader(weekDateRange(weekStart)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (weekOffset != 0) {
                        TextButton(onClick = { weekOffset = 0 }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(s.gotoThisWeek, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                WeeklyRangeChart(
                    stats = dailyStats,
                    leaves = leaves,
                    holidays = holidays,
                    events = events,
                    weekStart = weekStart,
                    lunchStartMinute = lunchStartMinute,
                    lunchEndMinute = lunchEndMinute,
                    halfAmStartMinute = halfAmStartMinute,
                    halfAmEndMinute = halfAmEndMinute,
                    halfPmStartMinute = halfPmStartMinute,
                    halfPmEndMinute = halfPmEndMinute,
                    showWeekend = showWeekend,
                    onDayClick = { selectedDay = it },
                    onWeekChange = { delta -> weekOffset = (weekOffset + delta).coerceAtMost(0) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    selectedDay?.let { day ->
        val dayStat = dailyStats.firstOrNull { it.dayStart == day }
        DayDetailDialog(
            day = day,
            events = events,
            dayLeaves = leaves.filter { startOfDay(it.date) == day },
            creditedMinutes = dayStat?.creditedMinutes ?: 0L,
            companySsid = companySsid,
            lunchStartMinute = lunchStartMinute,
            lunchEndMinute = lunchEndMinute,
            onAddEvent = onAddEvent,
            onUpdateEvent = onUpdateEvent,
            onExcludeEvent = onExcludeEvent,
            onDismiss = { selectedDay = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsTab(
    events: List<CommuteEvent>,
    excludedEvents: List<CommuteEvent>,
    missingRecords: List<MissingRecordFlag>,
    companySsid: String?,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onExcludeEvent: (CommuteEvent) -> Unit,
    onRestoreEvent: (CommuteEvent) -> Unit,
    onDeleteEvents: (List<CommuteEvent>) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var editingEvent by remember { mutableStateOf<CommuteEvent?>(null) }
    var addingEvent by remember { mutableStateOf(false) }
    var fixingFlag by remember { mutableStateOf<MissingRecordFlag?>(null) }
    var showingExcluded by remember { mutableStateOf(false) }

    // Preset ranges instead of two hand-picked dates: one tap filters the list. The upper bound
    // is always open (today is included); each preset only sets the lower bound. 전체 = show all.
    // Opens on 이번주: the records people come here to check are almost always this week's, and
    // 전체 buried them under months of history that had to be scrolled past first.
    var selectedRange by remember { mutableStateOf(DateRangePreset.THIS_WEEK) }
    val filterStart = selectedRange.startMillis(System.currentTimeMillis())
    val filteredEvents = events.filter { event ->
        filterStart == null || startOfDay(event.timestamp) >= filterStart
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DateRangePreset.values().forEach { preset ->
                    FilterChip(
                        selected = selectedRange == preset,
                        onClick = { selectedRange = preset },
                        label = { Text(when (preset) {
                            DateRangePreset.THIS_WEEK -> s.rangeThisWeek
                            DateRangePreset.TWO_WEEKS -> s.rangeTwoWeeks
                            DateRangePreset.THIS_MONTH -> s.rangeThisMonth
                            DateRangePreset.THREE_MONTHS -> s.rangeThreeMonths
                            DateRangePreset.SIX_MONTHS -> s.rangeSixMonths
                            DateRangePreset.ALL -> s.rangeAll
                        }) }
                    )
                }
            }
            // Only shown once something has actually been excluded — an empty-state button here
            // at all times would be one more thing to explain on a screen most people never need it.
            if (excludedEvents.isNotEmpty()) {
                TextButton(
                    onClick = { showingExcluded = true },
                    modifier = Modifier.align(Alignment.End)
                ) { Text(s.excludedRecordsButton(excludedEvents.size)) }
            }
            if (missingRecords.isNotEmpty()) {
                MissingRecordsBanner(missingRecords, onFix = { fixingFlag = it })
            }
            // Guards on the list alone, not on the banner too — with a missing-record flag
            // present, an empty date range would otherwise render a blank list with no
            // "이 기간에 기록이 없습니다" to explain it.
            if (filteredEvents.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedRange == DateRangePreset.ALL) s.noRecords else s.noRecordsInRange,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredEvents.sortedByDescending { it.timestamp }, key = { it.id }) { event ->
                        EventRow(event, lunchStartMinute, lunchEndMinute, onClick = { editingEvent = event })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { addingEvent = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = s.addMissingRecordDesc) }
    }

    editingEvent?.let { event ->
        EditEventDialog(
            event = event,
            isNew = false,
            onSave = { updated -> onUpdateEvent(updated); editingEvent = null },
            onDelete = { onExcludeEvent(event); editingEvent = null },
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
            onDismiss = { addingEvent = false },
            otherEventsForDuplicateCheck = events
        )
    }
    fixingFlag?.let { flag ->
        EditEventDialog(
            // remember-ed for the same reason as the blank-add template below: it now reads the
            // clock too, so recomputing it inline would reset the user's in-progress edits on
            // every recomposition (e.g. a background ARRIVE landing mid-entry).
            event = remember(flag) { missingEventTemplate(flag, companySsid) },
            isNew = true,
            onSave = { created -> onAddEvent(created); fixingFlag = null },
            onDelete = null,
            onDismiss = { fixingFlag = null }
        )
    }
    if (showingExcluded) {
        ExcludedRecordsDialog(
            events = excludedEvents,
            lunchStartMinute = lunchStartMinute,
            lunchEndMinute = lunchEndMinute,
            onRestore = onRestoreEvent,
            onDeleteMany = onDeleteEvents,
            onDismiss = { showingExcluded = false }
        )
    }

}

/** Sizing for [ExcludedRecordsDialog]'s dense rows, named so the row's various `.size()`/`.padding()`
 * calls read as one consistent scale instead of scattered magic numbers. Restore is the row's only
 * action (deleting goes through the checkbox + 선택 삭제 flow), so it can afford a proper touch
 * target without squeezing the time range back into an ellipsis. */
private val CompactCheckboxIconSize = 14.dp
private val CompactCheckboxTouchPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val CompactRowActionButtonSize = 40.dp
private val CompactRowActionIconSize = 16.dp
private val CompactRowPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
private val CompactRowItemSpacing = 2.dp
private val CompactRowLabelTimeGap = 10.dp

/** A checkbox sized/margined by hand instead of Material3's [androidx.compose.material3.Checkbox],
 * whose internal `requiredSize(20.dp)` ignores any outer size modifier — so callers that need a
 * genuinely compact box (e.g. [ExcludedRecordsDialog]'s dense rows) can't shrink it via modifiers.
 * Uses [toggleable] with [Role.Checkbox] rather than a bare [clickable] so TalkBack still announces
 * it as a checkbox and reads its checked state, which a plain clickable [Icon] would not. */
@Composable
private fun SmallCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Icon(
        if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
        contentDescription = label,
        tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox)
            .padding(CompactCheckboxTouchPadding)
            .size(CompactCheckboxIconSize)
    )
}

/** Lists every record the user has excluded from 기록보기, newest first, each with a one-tap
 * restore — the counterpart to the exclude action in [EditEventDialog].
 *
 * Permanent deletion (for rows that are genuine duplicates, e.g. left behind by a backup restore,
 * rather than something to bring back) runs *only* through the per-row checkbox plus the "select
 * all" toggle, which feed [onDeleteMany] behind a single confirm. There is deliberately no per-row
 * delete button: it would sit a couple of dp from restore at icon size, putting the one
 * irreversible action in this dialog within mis-tap range of the reversible one. Routing every
 * delete through an explicit selection keeps the destructive path something you opt into.
 *
 * Closes itself once the last one is gone rather than leaving an empty dialog open. */
@Composable
private fun ExcludedRecordsDialog(
    events: List<CommuteEvent>,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onRestore: (CommuteEvent) -> Unit,
    onDeleteMany: (List<CommuteEvent>) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var confirmingDeleteSelected by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    // Every selection change disarms a pending confirm. Without this an armed "정말 삭제?" survives
    // the selection changing underneath it, so tapping it once would wipe a *different* set of rows
    // than the one the user confirmed — e.g. arm on one row, then hit 전체 선택, then one tap.
    val setSelection: (Set<Long>) -> Unit = { ids ->
        selectedIds = ids
        confirmingDeleteSelected = false
    }
    LaunchedEffect(events.isEmpty()) {
        if (events.isEmpty()) onDismiss()
    }
    // Selection can go stale once a restore/delete elsewhere drops an id out of `events`.
    LaunchedEffect(events) {
        val liveIds = events.map { it.id }.toSet()
        if (selectedIds.any { it !in liveIds }) setSelection(selectedIds.intersect(liveIds))
    }
    val sorted = events.sortedByDescending { it.timestamp }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } },
        title = { Text(s.excludedRecordsTitle) },
        text = {
            if (events.isEmpty()) {
                Text(s.noExcludedRecords, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallCheckbox(
                                checked = selectedIds.isNotEmpty() && selectedIds.size == sorted.size,
                                onCheckedChange = { checkAll ->
                                    setSelection(if (checkAll) sorted.map { it.id }.toSet() else emptySet())
                                },
                                label = s.selectAll
                            )
                            Text(s.selectAll, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (selectedIds.isNotEmpty()) {
                            TextButton(onClick = {
                                if (confirmingDeleteSelected) {
                                    onDeleteMany(sorted.filter { it.id in selectedIds })
                                    setSelection(emptySet())
                                } else {
                                    confirmingDeleteSelected = true
                                }
                            }) {
                                Text(
                                    if (confirmingDeleteSelected) s.reallyDelete else s.deleteSelectedButton(selectedIds.size),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    sorted.forEach { event ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(CompactRowPadding),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(CompactRowItemSpacing)
                            ) {
                                val typeLabel = when (event.type) {
                                    CommuteEventType.ARRIVE -> s.eventArrive
                                    CommuteEventType.LEAVE -> s.eventLeave
                                    CommuteEventType.AWAY -> s.eventAway
                                }
                                SmallCheckbox(
                                    checked = event.id in selectedIds,
                                    onCheckedChange = { checked ->
                                        setSelection(
                                            if (checked) selectedIds + event.id else selectedIds - event.id
                                        )
                                    },
                                    label = typeLabel
                                )
                                Text(
                                    typeLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(CompactRowLabelTimeGap))
                                Text(
                                    formatEventRangeText(event, lunchStartMinute, lunchEndMinute),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onRestore(event) },
                                    modifier = Modifier.size(CompactRowActionButtonSize)
                                ) {
                                    Icon(
                                        Icons.Filled.Restore,
                                        contentDescription = s.restoreAction,
                                        modifier = Modifier.size(CompactRowActionIconSize)
                                    )
                                }
                            }
                        }
                    }
                }
              }
            }
        }
    )
}

/** Quick date-range filters for 기록 탭 — pick one instead of hand-entering start/end dates.
 *  Each preset only bounds the past; the upper end stays open so today always shows. */
private enum class DateRangePreset {
    THIS_WEEK, TWO_WEEKS, THIS_MONTH, THREE_MONTHS, SIX_MONTHS, ALL;

    /** Inclusive lower bound as a local-midnight millis, or null for "no lower bound" (전체). */
    fun startMillis(now: Long): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(now) }
        return when (this) {
            THIS_WEEK -> startOfWeek(now)
            TWO_WEEKS -> { cal.add(Calendar.DAY_OF_YEAR, -13); cal.timeInMillis }   // 오늘 포함 14일
            THIS_MONTH -> { cal.set(Calendar.DAY_OF_MONTH, 1); cal.timeInMillis }
            THREE_MONTHS -> { cal.add(Calendar.MONTH, -3); cal.timeInMillis }
            SIX_MONTHS -> { cal.add(Calendar.MONTH, -6); cal.timeInMillis }
            ALL -> null
        }
    }
}

/** Lists ARRIVE/LEAVE events missing their other half (see [findMissingRecords]) with a quick
 * "add the missing one" action per row — these are the same cases [computeDailyWorkStats]
 * otherwise swallows silently, so surfacing them here is what lets the user actually notice and
 * fix the gap instead of a day quietly losing worked time. */
@Composable
private fun MissingRecordsBanner(flags: List<MissingRecordFlag>, onFix: (MissingRecordFlag) -> Unit) {
    val s = LocalStrings.current
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
                    s.missingRecordsCount(flags.size),
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
                        MissingRecordType.LEAVE_MISSING -> s.leaveMissingMsg(formatEventTimeRange(flag.event, s))
                        MissingRecordType.ARRIVE_MISSING -> s.arriveMissingMsg(formatEventTimeRange(flag.event, s))
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onFix(flag) }) {
                        Text(if (flag.type == MissingRecordType.LEAVE_MISSING) s.addLeaveShort else s.addArriveShort)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        // labelMedium + titleLarge (was labelLarge/headlineSmall): three tiles now share the row,
        // so the smaller type keeps each label on ~one line and the values from crowding.
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valueText, style = MaterialTheme.typography.titleLarge)
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
    leaves: List<LeaveEntry>,
    holidays: List<Holiday>,
    events: List<CommuteEvent>,
    weekStart: Long,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    halfAmStartMinute: Int,
    halfAmEndMinute: Int,
    halfPmStartMinute: Int,
    halfPmEndMinute: Int,
    showWeekend: Boolean,
    onDayClick: (Long) -> Unit,
    onWeekChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    // weekStart is always a Monday (startOfWeek), so the first 5 slots are 월~금 — 토·일 (the
    // last two) are left out entirely unless showWeekend is on, rather than just dimmed/skipped,
    // so the chart doesn't waste two columns on days that are almost never worked.
    val visibleDayCount = if (showWeekend) WEEK_DAYS else WEEK_DAYS - 2
    val days = (0 until visibleDayCount).map { weekStart + it * DAY_MS }
    val statByDay = stats.associateBy { it.dayStart }
    val leavesByDay = leaves.groupBy { startOfDay(it.date) }
    val holidaysByDay = holidays.associateBy { startOfDay(it.date) }
    val eventsByDay = events.groupBy { startOfDay(it.timestamp) }
    // The bar is read as one rule: cool is normal, warm is more-than-planned, grey doesn't count.
    val barColor = MaterialTheme.colorScheme.primary          // 근무 — 청록
    val leaveColor = MaterialTheme.colorScheme.secondary      // 연차·외출 — 슬레이트
    val holidayColor = MaterialTheme.colorScheme.error        // 휴일 — 산화
    val overtimeColor = MaterialTheme.colorScheme.tertiary    // 초과근무 — 황동
    // 점심 is the one grey in the chart: time that passed and doesn't count. Fixed rather than a
    // scheme role because it has to hold the same weight on both surfaces, and because it used to
    // borrow `tertiary` — the same role the 이석 status card uses, which now belongs to 황동 alone.
    // Picked off the surface actually being drawn, not isSystemInDarkTheme(): the app has its own
    // 화면 테마 setting, so the device's mode and the rendered one are no longer the same question.
    val lunchColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) ExcludedDark else ExcludedLight
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
            val leaveStroke = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
            days.forEachIndexed { index, day ->
                val left = chartLeft + index * slotWidth + (slotWidth - barWidth) / 2f
                val alpha = if (day == today) 1f else 0.55f

                // A synced or user-declared holiday renders the same hollow dashed block as a
                // declared leave (rather than tinting the whole column), spanning the full workday
                // and labelled with its name, so it reads at the same visual weight as 연차 instead
                // of dominating the chart.
                holidaysByDay[day]?.let { holiday ->
                    val blockTop = yFor(halfPmEndMinute)
                    val blockBottom = yFor(halfAmStartMinute).coerceAtLeast(blockTop + 6f)
                    val rect = Rect(left, blockTop, left + barWidth, blockBottom)
                    drawPath(
                        Path().apply { addRoundRect(RoundRect(rect = rect, cornerRadius = corner)) },
                        color = holidayColor.copy(alpha = alpha * 0.15f)
                    )
                    drawRoundRect(
                        color = holidayColor.copy(alpha = alpha),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        cornerRadius = corner,
                        style = leaveStroke
                    )
                    val measured = textMeasurer.measure(holiday.name, style = barTimeStyle)
                    val maxX = (size.width - measured.size.width).coerceAtLeast(0f)
                    val maxY = (size.height - measured.size.height).coerceAtLeast(0f)
                    drawText(
                        measured,
                        topLeft = Offset(
                            ((rect.left + rect.right) / 2f - measured.size.width / 2f).coerceIn(0f, maxX),
                            ((rect.top + rect.bottom) / 2f - measured.size.height / 2f).coerceIn(0f, maxY)
                        )
                    )
                }

                // Declared 연차/반차/외출 render as hollow (dashed, faintly-filled) blocks over their
                // time-of-day window with a short label inside — "약간 빈 막대구간". Drawn before the
                // worked bar and independent of it, so a leave-only day (no ARRIVE/LEAVE) still gets a
                // bar, and a 반차's off-half sits beside the attended half's solid bar.
                leavesByDay[day]?.forEach { leave ->
                    val range = leaveMinuteRange(leave, halfAmStartMinute, halfAmEndMinute, halfPmStartMinute, halfPmEndMinute)
                        ?: return@forEach
                    val (startMin, endMin) = range
                    if (endMin <= startMin) return@forEach
                    val blockTop = yFor(endMin)
                    val blockBottom = yFor(startMin).coerceAtLeast(blockTop + 6f)
                    val rect = Rect(left, blockTop, left + barWidth, blockBottom)
                    drawPath(
                        Path().apply { addRoundRect(RoundRect(rect = rect, cornerRadius = corner)) },
                        color = leaveColor.copy(alpha = alpha * 0.15f)
                    )
                    drawRoundRect(
                        color = leaveColor.copy(alpha = alpha),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        cornerRadius = corner,
                        style = leaveStroke
                    )
                    val measured = textMeasurer.measure(s.leaveShortLabel(leave.type), style = barTimeStyle)
                    val maxX = (size.width - measured.size.width).coerceAtLeast(0f)
                    val maxY = (size.height - measured.size.height).coerceAtLeast(0f)
                    drawText(
                        measured,
                        topLeft = Offset(
                            ((rect.left + rect.right) / 2f - measured.size.width / 2f).coerceIn(0f, maxX),
                            ((rect.top + rect.bottom) / 2f - measured.size.height / 2f).coerceIn(0f, maxY)
                        )
                    )
                }

                val stat = statByDay[day]
                val arriveAt = stat?.firstArriveAt ?: return@forEachIndexed
                val departAt = if (stat.open) now else (stat.lastLeaveAt ?: return@forEachIndexed)
                val arriveMinute = minuteOfDay(arriveAt)
                val departMinute = minuteOfDay(departAt)
                // The bar shows *presence*, not merely the span from the day's first 출근 to its
                // last 퇴근: 자리비움 and 퇴근→출근 gaps are cut back out, so a day with an outside
                // meeting reads as two blocks with a hole between them instead of one unbroken
                // nine-hour bar. Everything painted on the bar below is clipped to these same
                // pieces, so nothing is ever drawn across a hole.
                val daySpan = MinuteSpan(arriveMinute, departMinute)
                val presentSpans = subtractSpans(
                    daySpan,
                    absentMinuteSpans(eventsByDay[day].orEmpty(), day)
                )
                // The absence is drawn as a faded stretch of the same bar rather than left blank,
                // so the day still reads as one continuous span with a dim middle — a plain hole
                // looked like two unrelated days, and lost the fact that 출근 and 퇴근 bracket it.
                // Square corners (unlike the solid pieces) so it butts flush against them.
                subtractSpans(daySpan, presentSpans).forEach { span ->
                    val gapTop = yFor(span.end)
                    val gapBottom = yFor(span.start).coerceAtLeast(gapTop + 2f)
                    drawRect(
                        color = barColor.copy(alpha = alpha * 0.22f),
                        topLeft = Offset(left, gapTop),
                        size = Size(barWidth, gapBottom - gapTop)
                    )
                }
                // Outer edges of the whole day, for the 출근/퇴근 annotations — those label the
                // day's first arrival and last departure, so they stay anchored to the full span
                // even when the bar itself is broken into pieces.
                val top = yFor(departMinute)
                val bottom = yFor(arriveMinute).coerceAtLeast(top + 4f)
                presentSpans.forEach { span ->
                    val segTop = yFor(span.end)
                    val segBottom = yFor(span.start).coerceAtLeast(segTop + 2f)
                    drawPath(
                        Path().apply {
                            addRoundRect(
                                RoundRect(rect = Rect(left, segTop, left + barWidth, segBottom), cornerRadius = corner)
                            )
                        },
                        color = barColor.copy(alpha = alpha)
                    )
                }

                // Distinguish worked time beyond the daily 8시간 requirement by painting the
                // closing stretch of the bar in the 초과근무 color. Walked backwards through the
                // present pieces rather than taken as one window before departure, so an absence
                // late in the day doesn't get counted as part of the overtime stretch.
                var overtimeLeft = (stat.workedMinutes - DAILY_REQUIRED_MINUTES).coerceAtLeast(0)
                for (span in presentSpans.asReversed()) {
                    if (overtimeLeft <= 0) break
                    val take = minOf((span.end - span.start).toLong(), overtimeLeft).toInt()
                    val otTop = yFor(span.end)
                    val otBottom = yFor(span.end - take).coerceAtLeast(otTop + 2f)
                    drawRect(
                        color = overtimeColor.copy(alpha = alpha),
                        topLeft = Offset(left, otTop),
                        size = Size(barWidth, otBottom - otTop)
                    )
                    overtimeLeft -= take
                }

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

                // Mark the portion of the bar that falls inside the configured lunch window — it's
                // excluded from worked time, so it reads visually as a break in the bar. Spans the
                // whole day rather than only the present pieces, so lunch stays marked on a day
                // spent away over it (an outside meeting) — the faded absence stretch is still part
                // of the bar, and colour alone is enough to place the break.
                if (lunchStartMinute < lunchEndMinute) {
                    val overlapStart = maxOf(daySpan.start, lunchStartMinute)
                    val overlapEnd = minOf(daySpan.end, lunchEndMinute)
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
                    text = s.weekday(day),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (holidaysByDay[day] != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayDetailDialog(
    day: Long,
    events: List<CommuteEvent>,
    dayLeaves: List<LeaveEntry>,
    creditedMinutes: Long,
    companySsid: String?,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onAddEvent: (CommuteEvent) -> Unit,
    onUpdateEvent: (CommuteEvent) -> Unit,
    onExcludeEvent: (CommuteEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var editingEvent by remember { mutableStateOf<CommuteEvent?>(null) }
    var addingEvent by remember { mutableStateOf(false) }
    val dayEvents = events.filter { startOfDay(it.timestamp) == day }.sortedBy { it.timestamp }
    val firstArrive = dayEvents.firstOrNull { it.type == CommuteEventType.ARRIVE }
    val lastLeave = dayEvents.lastOrNull { it.type == CommuteEventType.LEAVE }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } },
        title = { Text(s.dayHeader(day)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DaySummaryItem(s.eventArrive, firstArrive?.let { formatTimeOnly(it.timestamp) } ?: "-")
                    DaySummaryItem(
                        s.eventLeave,
                        lastLeave?.let { formatTimeOnly(it.timestamp) } ?: if (firstArrive != null) s.stillWorking else "-"
                    )
                    // Includes 연차/반차/외출 credit so the number matches the totals and the chart.
                    DaySummaryItem(s.creditedTime, s.workHoursMinutes(creditedMinutes))
                }
                if (dayLeaves.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        dayLeaves.forEach { leave ->
                            val extra = if (leave.type == LeaveType.OUTING && leave.startMinute != null && leave.endMinute != null) {
                                " (${com.commute.app.data.formatMinuteOfDayToHHmm(leave.startMinute)}~${com.commute.app.data.formatMinuteOfDayToHHmm(leave.endMinute)})"
                            } else ""
                            Text(
                                "· ${s.leaveLabel(leave.type)}$extra",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                TextButton(onClick = { addingEvent = true }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(s.addMissingForDay, style = MaterialTheme.typography.bodySmall)
                }
                if (dayEvents.isEmpty()) {
                    Text(s.noRecordsThisDay, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dayEvents.forEach { event ->
                            EventRow(event, lunchStartMinute, lunchEndMinute, onClick = { editingEvent = event })
                        }
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
            onDelete = { onExcludeEvent(event); editingEvent = null },
            onDismiss = { editingEvent = null }
        )
    }
    if (addingEvent) {
        EditEventDialog(
            event = remember(day) { blankEventTemplate(day, companySsid) },
            isNew = true,
            onSave = { created -> onAddEvent(created); addingEvent = false },
            onDelete = null,
            onDismiss = { addingEvent = false },
            otherEventsForDuplicateCheck = events
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

/** The displayed week's calendar date range, e.g. "7/6~7/12" — shown regardless of whether
 * it's the current week or one paged back to, so the heading always says which days it is. */
private fun weekDateRange(weekStart: Long): String {
    val fmt = SimpleDateFormat("M/d", Locale.getDefault())
    val weekEnd = weekStart + (WEEK_DAYS - 1) * DAY_MS
    return "${fmt.format(Date(weekStart))}~${fmt.format(Date(weekEnd))}"
}

@Composable
private fun EventRow(
    event: CommuteEvent,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    onClick: () -> Unit = {}
) {
    val s = LocalStrings.current
    // Same rule as the bar: 출근 is the working colour, 퇴근 is declared and quiet, 이석 is warm.
    // These were a hardcoded green/red/amber, which read as pass/fail — 퇴근 is not a failure, and
    // the app makes no judgement about either end of the day.
    val (icon, label, tint) = when (event.type) {
        CommuteEventType.ARRIVE -> Triple(Icons.Filled.Work, s.eventArrive, MaterialTheme.colorScheme.primary)
        CommuteEventType.LEAVE -> Triple(Icons.AutoMirrored.Filled.ExitToApp, s.eventLeave, MaterialTheme.colorScheme.secondary)
        CommuteEventType.AWAY -> Triple(Icons.AutoMirrored.Filled.DirectionsWalk, s.eventAway, MaterialTheme.colorScheme.tertiary)
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
            // Range and duration on separate lines rather than one long string: a 자리비움 carries
            // both a range and a "(286분, 점심 제외)" tail, which wrapped across several lines and
            // made those rows tower over the plain 출근/퇴근 ones. Two right-aligned lines match the
            // label/ssid column beside them, so every row is the same height whatever its type.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatEventRangeText(event, lunchStartMinute, lunchEndMinute),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                formatEventDurationText(event, s, lunchStartMinute, lunchEndMinute)?.let { duration ->
                    Text(
                        duration,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * The time an event row shows, without its duration: a stamp for 출근/퇴근, a range for anything
 * with an end. A 자리비움 range is trimmed to the lunch boundary (12:20~13:00, not 11:59~13:00) so
 * it agrees with the lunch-excluded minutes beside it instead of contradicting them with a raw
 * start inside the break.
 */
private fun formatEventRangeText(
    event: CommuteEvent,
    lunchStartMinute: Int = 0,
    lunchEndMinute: Int = 0
): String {
    val dateTimeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val end = event.endTimestamp ?: return dateTimeFormat.format(Date(event.timestamp))
    val trimmed = if (event.type == CommuteEventType.AWAY) {
        awayDisplayRange(event.timestamp, end, lunchStartMinute, lunchEndMinute)
    } else {
        null
    }
    val from = trimmed?.first ?: event.timestamp
    val to = trimmed?.second ?: end
    return "${dateTimeFormat.format(Date(from))}~${timeFormat.format(Date(to))}"
}

/**
 * The duration shown under an event's time, or null for a plain 출근/퇴근 stamp that has none. A
 * 자리비움 straddling lunch reports only its outside-lunch minutes — lunch is the unpaid break, not
 * a 자리비움 — matching how worked time is computed, and says so in the label.
 *
 * Separate from [formatEventRangeText] so a row can put the two on their own lines; as one string
 * the 자리비움 text wrapped and left those rows far taller than the rest of the list.
 */
private fun formatEventDurationText(
    event: CommuteEvent,
    s: Strings,
    lunchStartMinute: Int = 0,
    lunchEndMinute: Int = 0
): String? {
    val end = event.endTimestamp ?: return null
    val rawMinutes = (end - event.timestamp) / 60_000
    if (event.type != CommuteEventType.AWAY) return s.minutesParen(rawMinutes)
    val exLunch = awayMinutesExcludingLunch(event.timestamp, end, lunchStartMinute, lunchEndMinute)
    return if (exLunch != rawMinutes) s.minutesParenExLunch(exLunch) else s.minutesParen(exLunch)
}

/** Both of the above as one string, for the 기록 누락 banner's inline sentence. */
private fun formatEventTimeRange(
    event: CommuteEvent,
    s: Strings,
    lunchStartMinute: Int = 0,
    lunchEndMinute: Int = 0
): String = listOfNotNull(
    formatEventRangeText(event, lunchStartMinute, lunchEndMinute),
    formatEventDurationText(event, s, lunchStartMinute, lunchEndMinute)
).joinToString(" ")

private fun formatTimeOnly(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

/** The time-of-day window (minutes-since-midnight) a leave occupies on the chart: the configured
 * 반차 windows for half-days, both spans for a full 연차, and the recorded range for an 외출.
 * Null when an 외출 has no times set (shouldn't happen via the editor). */
private fun leaveMinuteRange(
    leave: LeaveEntry,
    halfAmStart: Int,
    halfAmEnd: Int,
    halfPmStart: Int,
    halfPmEnd: Int
): Pair<Int, Int>? = when (leave.type) {
    LeaveType.ANNUAL -> halfAmStart to halfPmEnd
    LeaveType.HALF_AM -> halfAmStart to halfAmEnd
    LeaveType.HALF_PM -> halfPmStart to halfPmEnd
    LeaveType.OUTING -> {
        val start = leave.startMinute
        val end = leave.endMinute
        if (start != null && end != null) start to end else null
    }
}
