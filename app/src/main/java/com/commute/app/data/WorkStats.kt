package com.commute.app.data

import java.util.Calendar

/** Local midnight timestamp for the day containing [timestamp]. */
fun startOfDay(timestamp: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Midnight of the Monday starting the calendar week containing [timestamp] — the work week
 * always runs 월요일~금요일, so weekly stats reset on Monday rather than rolling 7 days. */
fun startOfWeek(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(timestamp) }
    val daysSinceMonday = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    cal.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
    return cal.timeInMillis
}

/** 근무 인정 시간의 시작(자정 기준 분) — 가산 연구소 운영 방안 기준 07:00. 이보다 일찍 출근해도
 * 근무 시간은 07:00부터 인정. */
private const val WORK_RECOGNITION_START_MINUTE = 7 * 60

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Worked minutes for the day starting at [dayStart], plus that day's overall span — the
 * earliest ARRIVE and the latest LEAVE (or null/[open] if the last session hasn't closed
 * yet) — so a UI can plot both "how long" and "when" from one value. [firstArriveAt] is
 * clamped to [WORK_RECOGNITION_START_MINUTE] (07:00) — arriving earlier doesn't move it
 * back, since work isn't recognized before then. [rawSpanMinutes] is the same span with the
 * lunch deduction added back, i.e. "how long was I actually present, lunch included" — for
 * a UI that wants to show that number alongside the worked total.
 */
data class DailyWorkStat(
    val dayStart: Long,
    val workedMinutes: Long,
    val rawSpanMinutes: Long = workedMinutes,
    val firstArriveAt: Long? = null,
    val lastLeaveAt: Long? = null,
    val open: Boolean = false,
    /** Declared 연차/반차/외출 credit for this day (see [mergeLeaveStats]), capped at one full
     * standard day. Kept separate from [workedMinutes] so the chart can show RF attendance and
     * declared leave distinctly, while totals/overtime use [creditedMinutes]. */
    val leaveMinutes: Long = 0
) {
    /** RF-detected worked time plus declared leave credit — the number that counts toward the
     * daily 8-hour requirement and weekly totals. */
    val creditedMinutes: Long get() = workedMinutes + leaveMinutes
}

/** 하루 표준 근무 시간(분) — 가산 연구소 운영 방안의 일 8시간(주 40시간) 기준. 초과근무는 하루 실
 * 근무(연차/반차/외출 크레딧 포함)에서 이 값을 뺀 합계로 계산한다. */
const val DAILY_REQUIRED_MINUTES = 8 * 60L

/**
 * Work-time credit a single leave contributes: a full 연차 is a standard 8-hour day, a 반차 is
 * half of that, and an 외출 counts for its own duration (approved-outing → 근무 인정).
 *
 * 연차/반차 credit is *already* net of lunch: [DAILY_REQUIRED_MINUTES] (8h) is the standard day
 * *after* the unpaid lunch is taken out, so a full day = 480 and a half = 240 both exclude lunch by
 * definition — subtracting it again would double-deduct and make 오전 반차+오후 반차 read as less
 * than a full 연차. An 외출's credit is the raw declared range, though, so any part of it that falls
 * inside the configured lunch window is removed here — being out over lunch isn't paid work, same as
 * an RF-present session has its lunch subtracted in [computeDailyWorkStats].
 */
fun leaveCreditMinutes(
    type: LeaveType,
    startMinute: Int?,
    endMinute: Int?,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): Long = when (type) {
    LeaveType.ANNUAL -> DAILY_REQUIRED_MINUTES
    LeaveType.HALF_AM, LeaveType.HALF_PM -> DAILY_REQUIRED_MINUTES / 2
    LeaveType.OUTING ->
        if (startMinute != null && endMinute != null && endMinute > startMinute) {
            val gross = (endMinute - startMinute).toLong()
            val lunch = minuteWindowOverlap(startMinute, endMinute, lunchStartMinute, lunchEndMinute)
            (gross - lunch).coerceAtLeast(0)
        } else 0
}

/** Overlap, in minutes, between the minute-of-day ranges [aStart, aEnd) and [bStart, bEnd) — used
 * to strip an 외출's lunch portion. Zero if the lunch window is empty/invalid or they don't touch. */
private fun minuteWindowOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Long {
    if (bStart >= bEnd) return 0
    return (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0).toLong()
}

/**
 * Folds declared leave credit into per-day [DailyWorkStat]s. A day with only leave and no RF
 * attendance (a plain 연차) has no ARRIVE/LEAVE, so [computeDailyWorkStats] emits nothing for it —
 * those days are added here so they still count toward totals and get a chart bar. Same-day leaves
 * sum, but the credit is capped at one full standard day so 연차+반차 can't read as more than 8h.
 *
 * Only leave for [nowMillis]'s day or earlier is credited — a 연차 declared for a day that hasn't
 * arrived yet is a plan, not time actually taken off, so it shouldn't inflate 이번주 총 근무시간
 * before the week even gets there. It still reaches the chart, though: that reads the raw [leaves]
 * list directly rather than this function's output, so a future 연차 still shows as a planned block
 * on its day — only the *credited* total waits for the day to actually arrive.
 */
fun mergeLeaveStats(
    base: List<DailyWorkStat>,
    leaves: List<LeaveEntry>,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    nowMillis: Long
): List<DailyWorkStat> {
    if (leaves.isEmpty()) return base
    val today = startOfDay(nowMillis)
    val creditByDay = leaves
        .filter { startOfDay(it.date) <= today }
        .groupBy { startOfDay(it.date) }
        .mapValues { (_, dayLeaves) ->
            dayLeaves.sumOf { leaveCreditMinutes(it.type, it.startMinute, it.endMinute, lunchStartMinute, lunchEndMinute) }
                .coerceAtMost(DAILY_REQUIRED_MINUTES)
        }
    val byDay = base.associateBy { it.dayStart }.toMutableMap()
    for ((day, credit) in creditByDay) {
        val existing = byDay[day]
        byDay[day] = existing?.copy(leaveMinutes = credit)
            ?: DailyWorkStat(dayStart = day, workedMinutes = 0, leaveMinutes = credit)
    }
    return byDay.values.sortedBy { it.dayStart }
}

/**
 * Signed over/under time for the days in [weekStart, weekStart+7d) that actually have a record —
 * Σ(그날 실근무(연차/반차/외출 포함) − 하루 8시간). Positive means net overtime, negative means
 * short of the daily requirement. Only days with attendance or a leave count, so days simply not
 * worked (weekends, untracked days) don't drag the figure negative. The current day ([nowMillis])
 * is excluded — it's still in progress, so counting its not-yet-8h partial would drag the figure
 * negative; overtime is only tallied through 어제(yesterday). See [overtimeMinutesTotal].
 */
fun overtimeMinutesForWeek(stats: List<DailyWorkStat>, weekStart: Long, nowMillis: Long): Long {
    val weekEnd = weekStart + 7 * DAY_MILLIS
    val today = startOfDay(nowMillis)
    return stats
        .filter { it.dayStart in weekStart until weekEnd && it.dayStart != today && (it.workedMinutes > 0 || it.leaveMinutes > 0) }
        .sumOf { it.creditedMinutes - DAILY_REQUIRED_MINUTES }
}

/**
 * Signed 초과근무(+)/부족(−) across ALL recorded days — the running cumulative total vs the daily
 * 8시간 requirement, with no week bound. Same per-day rule as [overtimeMinutesForWeek]: only days
 * with attendance or a declared leave count, so untracked days (weekends, days simply not worked)
 * never drag it negative. The current day ([nowMillis]) is also excluded: it's a work-in-progress
 * that hasn't reached 8h yet, so it would otherwise read as a large deficit — the total only counts
 * completed days (어제까지). This is the "지금까지 전체 얼마나 오버했나" figure shown on the home card.
 */
fun overtimeMinutesTotal(stats: List<DailyWorkStat>, nowMillis: Long): Long {
    val today = startOfDay(nowMillis)
    return stats
        .filter { it.dayStart != today && (it.workedMinutes > 0 || it.leaveMinutes > 0) }
        .sumOf { it.creditedMinutes - DAILY_REQUIRED_MINUTES }
}

/**
 * Pairs ARRIVE→LEAVE events chronologically into sessions (exactly one per day — see
 * [com.commute.app.wifi.WifiMonitorService]) and sums each session's duration. A session's
 * effective start is clamped forward to 07:00(근무 인정 시간, [WORK_RECOGNITION_START_MINUTE]) —
 * arriving earlier doesn't count toward worked time or move the chart's bar earlier, matching
 * the 가산 연구소 운영 방안's 근무 인정 시간 07:00~22:00 lower bound. The configured lunch window
 * is always subtracted from a session that spans it — regardless of whether the person actually
 * disconnected from wifi during lunch — since it's unpaid break time either way. An AWAY span is
 * then considered only for its portion *outside* that lunch window (lunch is carved out first, so
 * being away over lunch is just the break, not a 자리비움); each remaining before-/after-lunch
 * piece that reaches [absenceThresholdMinutes] is *also* subtracted, while shorter pieces stay
 * counted as work time, matching the 가산 연구소 운영 방안 자리비움 rule. If the last ARRIVE has
 * no matching LEAVE yet (still at work), the session
 * is closed at [nowMillis] so "today" reflects the ongoing session, and that day's
 * [DailyWorkStat.open] is true.
 */
fun computeDailyWorkStats(
    events: List<CommuteEvent>,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
    absenceThresholdMinutes: Int,
    nowMillis: Long
): List<DailyWorkStat> {
    val sorted = events.sortedBy { it.timestamp }

    data class DayAccum(
        var minutes: Long = 0,
        var rawMinutes: Long = 0,
        var firstArrive: Long? = null,
        var lastLeave: Long? = null,
        var open: Boolean = false
    )
    val byDay = linkedMapOf<Long, DayAccum>()

    fun closeSession(sessionStart: Long, sessionEnd: Long, stillOpen: Boolean, awaySpans: List<Pair<Long, Long>>) {
        if (sessionEnd <= sessionStart) return
        val workStart = timestampAtMinuteOfDay(sessionStart, WORK_RECOGNITION_START_MINUTE)
        val recognizedStart = maxOf(sessionStart, workStart)
        if (recognizedStart >= sessionEnd) return // entire session falls before 07:00 — nothing recognized

        val rawMinutes = (sessionEnd - recognizedStart) / 60_000
        val lunchMinutes = lunchOverlapMinutes(recognizedStart, sessionEnd, lunchStartMinute, lunchEndMinute)
        val longAwayMinutes = deductibleAwayMinutes(
            awaySpans, recognizedStart, sessionEnd, absenceThresholdMinutes, lunchStartMinute, lunchEndMinute
        )
        val minutes = rawMinutes - lunchMinutes - longAwayMinutes
        val acc = byDay.getOrPut(startOfDay(sessionStart)) { DayAccum() }
        acc.minutes += minutes.coerceAtLeast(0)
        // "Present, lunch included" = worked time with only the lunch deduction added back. Long
        // absences must stay deducted here too — the user genuinely wasn't present for those.
        acc.rawMinutes += (minutes + lunchMinutes).coerceAtLeast(0)
        if (acc.firstArrive == null || recognizedStart < acc.firstArrive!!) acc.firstArrive = recognizedStart
        acc.open = stillOpen
        if (!stillOpen && (acc.lastLeave == null || sessionEnd > acc.lastLeave!!)) acc.lastLeave = sessionEnd
    }

    // Collected up front rather than accumulated per-session: [deductibleAwayMinutes] selects the
    // ones overlapping each session's window, which also catches an AWAY that starts just before
    // its session's ARRIVE (possible via manual entry) — threading a "pending" list through the
    // loop would silently drop those, since they're visited before the ARRIVE is seen.
    val allAwaySpans = sorted.mapNotNull { event ->
        if (event.type != CommuteEventType.AWAY) null
        else event.endTimestamp?.let { end -> event.timestamp to end }
    }

    // An unclosed session is only "still running" on the day it started — a dangling ARRIVE from
    // a past day (monitoring toggled off mid-session, or the service killed before it could
    // record a LEAVE) must not keep accruing. Left uncapped it billed every hour since, so a
    // week-old open session read as 144시간 while its chart bar showed one hour. Capping at the
    // end of its own day keeps the number bounded and plausible; 기록 누락 already flags it for
    // the user to correct properly.
    fun openSessionEnd(arriveAt: Long): Long = minOf(nowMillis, startOfDay(arriveAt) + DAY_MILLIS)

    var pendingArriveAt: Long? = null
    for (event in sorted) {
        when (event.type) {
            CommuteEventType.ARRIVE -> {
                // Two ARRIVEs with no LEAVE between them: close the earlier one instead of
                // discarding it. Dropping it silently zeroed a real in-progress day the moment
                // the user added a future-dated record.
                pendingArriveAt?.let {
                    closeSession(it, openSessionEnd(it), stillOpen = false, awaySpans = allAwaySpans)
                }
                pendingArriveAt = event.timestamp
            }
            CommuteEventType.LEAVE -> {
                val arriveAt = pendingArriveAt ?: continue
                pendingArriveAt = null
                closeSession(arriveAt, event.timestamp, stillOpen = false, awaySpans = allAwaySpans)
            }
            CommuteEventType.AWAY -> Unit
        }
    }
    pendingArriveAt?.let {
        val end = openSessionEnd(it)
        closeSession(it, end, stillOpen = end >= nowMillis, awaySpans = allAwaySpans)
    }

    return byDay.entries.map { (day, acc) ->
        DailyWorkStat(day, acc.minutes, acc.rawMinutes, acc.firstArrive, acc.lastLeave, acc.open)
    }.sortedBy { it.dayStart }
}

/** Which half of an ARRIVE/LEAVE pair is missing for a [MissingRecordFlag]. */
enum class MissingRecordType { LEAVE_MISSING, ARRIVE_MISSING }

/** [event] has no matching counterpart of the other type next to it — either an ARRIVE with no
 * LEAVE after it, or a LEAVE with no ARRIVE before it. */
data class MissingRecordFlag(val event: CommuteEvent, val type: MissingRecordType)

/**
 * Finds ARRIVE/LEAVE events that aren't properly paired — the same silent cases
 * [computeDailyWorkStats] has to swallow to keep pairing sessions: two ARRIVEs in a row (the
 * earlier one never got a LEAVE) and a LEAVE with no ARRIVE before it. Surfacing these lets the
 * user fill in the missing half instead of that day quietly losing worked time. The very last
 * pending ARRIVE is only flagged if it's not today's still-open session (someone currently at
 * work is expected to have no LEAVE yet).
 */
fun findMissingRecords(events: List<CommuteEvent>, nowMillis: Long): List<MissingRecordFlag> {
    val sorted = events.sortedBy { it.timestamp }
    val flags = mutableListOf<MissingRecordFlag>()

    var pendingArrive: CommuteEvent? = null
    for (event in sorted) {
        when (event.type) {
            CommuteEventType.ARRIVE -> {
                pendingArrive?.let { flags.add(MissingRecordFlag(it, MissingRecordType.LEAVE_MISSING)) }
                pendingArrive = event
            }
            CommuteEventType.LEAVE -> {
                if (pendingArrive == null) {
                    flags.add(MissingRecordFlag(event, MissingRecordType.ARRIVE_MISSING))
                } else {
                    pendingArrive = null
                }
            }
            CommuteEventType.AWAY -> Unit
        }
    }
    pendingArrive?.let { arrive ->
        if (startOfDay(arrive.timestamp) != startOfDay(nowMillis)) {
            flags.add(MissingRecordFlag(arrive, MissingRecordType.LEAVE_MISSING))
        }
    }
    return flags.sortedByDescending { it.event.timestamp }
}

/**
 * Minutes to subtract from a session for absences that reached [absenceThresholdMinutes].
 *
 * The lunch window is carved out of every AWAY span *first*, before anything else: being away
 * over lunch isn't a 자리비움, it's the unpaid break (already removed by the session-level lunch
 * subtraction). What's left is each span's *work-time* absence — the part that overlaps actual
 * recognized work — split into a before-lunch piece and an after-lunch piece. The 자리비움
 * threshold is then tested against each of those pieces, so leaving a little early for lunch or
 * coming back a little late isn't deducted unless the work-time portion itself reaches the
 * threshold. (Previously the threshold was tested against the whole wall-clock span *including*
 * lunch, so an away straddling lunch was treated as one long absence and its small outside-lunch
 * minutes were deducted even when neither side reached the threshold.)
 *
 * Each piece is also clamped to the session's *recognized* window: time outside it (before the
 * 07:00 recognition start, or past the session end) was never counted as worked, so deducting it
 * would remove it twice. Overlapping pieces are merged so shared time is only deducted once, and
 * the lunch window is derived from the session's own day so it lines up exactly with the
 * session-level lunch subtraction.
 */
private fun deductibleAwayMinutes(
    awaySpans: List<Pair<Long, Long>>,
    recognizedStart: Long,
    sessionEnd: Long,
    absenceThresholdMinutes: Int,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): Long {
    val hasLunch = lunchEndMinute > lunchStartMinute
    val lunchStart = timestampAtMinuteOfDay(recognizedStart, lunchStartMinute)
    val lunchEnd = timestampAtMinuteOfDay(recognizedStart, lunchEndMinute)

    val thresholdMs = absenceThresholdMinutes.toLong() * 60_000
    val pieces = mutableListOf<Pair<Long, Long>>()
    for ((rawStart, rawEnd) in awaySpans) {
        val start = maxOf(rawStart, recognizedStart)
        val end = minOf(rawEnd, sessionEnd)
        if (end <= start) continue
        // Carve the lunch window out → up to two outside-lunch pieces (before / after lunch).
        val candidates = if (hasLunch) {
            listOf(start to minOf(end, lunchStart), maxOf(start, lunchEnd) to end)
        } else {
            listOf(start to end)
        }
        for ((s, e) in candidates) {
            if (e - s >= thresholdMs) pieces.add(s to e)
        }
    }
    if (pieces.isEmpty()) return 0

    pieces.sortBy { it.first }
    val merged = mutableListOf<Pair<Long, Long>>()
    for ((start, end) in pieces) {
        val last = merged.lastOrNull()
        if (last != null && start <= last.second) {
            merged[merged.lastIndex] = last.first to maxOf(last.second, end)
        } else {
            merged.add(start to end)
        }
    }
    // Summed in millis and divided once, so per-piece truncation doesn't compound into
    // several minutes of drift across a day with many absences.
    return merged.sumOf { (start, end) -> end - start } / 60_000
}

/**
 * Away time to *show* for a single 자리비움 span, with the configured lunch window carved out —
 * being away over lunch is just the unpaid break, so it isn't a 자리비움. This mirrors how
 * [computeDailyWorkStats]/[deductibleAwayMinutes] treat lunch, but with *no* absence threshold: the
 * display reports the real outside-lunch away duration (both the before-lunch and after-lunch
 * pieces), not only the part long enough to be deducted from worked time. Returns the raw span when
 * no lunch window is configured, and 0 when the whole span falls inside lunch.
 */
fun awayMinutesExcludingLunch(
    start: Long,
    end: Long,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): Long {
    if (end <= start) return 0
    if (lunchStartMinute >= lunchEndMinute) return (end - start) / 60_000
    val lunchStart = timestampAtMinuteOfDay(start, lunchStartMinute)
    val lunchEnd = timestampAtMinuteOfDay(start, lunchEndMinute)
    val before = (minOf(end, lunchStart) - start).coerceAtLeast(0)
    val after = (end - maxOf(start, lunchEnd)).coerceAtLeast(0)
    return (before + after) / 60_000
}

/**
 * The 자리비움 time *range* to display, with lunch-window endpoints trimmed to the lunch boundary —
 * the companion to [awayMinutesExcludingLunch], which trims the *duration*. If the span starts inside
 * the lunch window its shown start is moved forward to lunch end, and if it ends inside lunch its
 * shown end is moved back to lunch start, so the displayed range matches the actual outside-lunch
 * 자리비움 (e.g. 11:59~13:00 with an 11:20~12:20 lunch shows as 12:20~13:00, not 11:59~13:00). A span
 * that straddles lunch entirely keeps both real endpoints (lunch just sits in the middle). Returns
 * the raw span when no lunch window is configured, and null when the whole span falls inside lunch
 * (nothing outside-lunch to show).
 */
fun awayDisplayRange(
    start: Long,
    end: Long,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): Pair<Long, Long>? {
    if (end <= start) return null
    if (lunchStartMinute >= lunchEndMinute) return start to end
    val lunchStart = timestampAtMinuteOfDay(start, lunchStartMinute)
    val lunchEnd = timestampAtMinuteOfDay(start, lunchEndMinute)
    if (start >= lunchStart && end <= lunchEnd) return null // whole span inside lunch
    val displayStart = if (start in lunchStart until lunchEnd) lunchEnd else start
    val displayEnd = if (end > lunchStart && end <= lunchEnd) lunchStart else end
    return if (displayEnd > displayStart) displayStart to displayEnd else null
}

/** Minutes of [sessionStart, sessionEnd) that fall inside the configured lunch window
 * (on sessionStart's calendar day), so that time is never counted as worked. */
private fun lunchOverlapMinutes(
    sessionStart: Long,
    sessionEnd: Long,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): Long {
    if (lunchStartMinute >= lunchEndMinute) return 0
    val lunchStart = timestampAtMinuteOfDay(sessionStart, lunchStartMinute)
    val lunchEnd = timestampAtMinuteOfDay(sessionStart, lunchEndMinute)
    val overlapStart = maxOf(sessionStart, lunchStart)
    val overlapEnd = minOf(sessionEnd, lunchEnd)
    return if (overlapEnd > overlapStart) (overlapEnd - overlapStart) / 60_000 else 0
}

/** A half-open minute-of-day interval `[start, end)`, used for chart geometry. */
data class MinuteSpan(val start: Int, val end: Int)

private const val MINUTES_PER_DAY = 24 * 60

/**
 * The minute-of-day stretches of [dayStart]'s bar where the person was *not* at the office:
 * recorded 자리비움 spans, plus any 퇴근→출근 gap between two sessions on the same day.
 *
 * The chart used to draw one solid bar from a day's first 출근 to its last 퇴근, which made an
 * absence invisible — a day with a 10:30~16:15 outside meeting read as nine hours of continuous
 * work even though the totals correctly excluded it. These are the pieces to cut back out, so the
 * bar shows presence rather than mere span.
 *
 * Both kinds of hole are collected because they're the same thing recorded two ways: an absence
 * that resolved on its own is an AWAY, while one that outlasted 자동 퇴근 처리 기준 before the
 * person came back is a LEAVE followed by a fresh ARRIVE. Spans are clamped into the day (an AWAY
 * that runs past midnight is cut at 24:00) and returned sorted; overlaps are the caller's to merge
 * via [subtractSpans].
 */
fun absentMinuteSpans(dayEvents: List<CommuteEvent>, dayStart: Long): List<MinuteSpan> {
    fun minuteOf(timestamp: Long): Int =
        ((timestamp - dayStart) / 60_000L).coerceIn(0L, MINUTES_PER_DAY.toLong()).toInt()

    val sorted = dayEvents.sortedBy { it.timestamp }
    val spans = mutableListOf<MinuteSpan>()
    sorted.forEach { event ->
        if (event.type != CommuteEventType.AWAY) return@forEach
        val end = event.endTimestamp ?: return@forEach
        val span = MinuteSpan(minuteOf(event.timestamp), minuteOf(end))
        if (span.end > span.start) spans += span
    }
    var openLeaveAt: Long? = null
    sorted.forEach { event ->
        when (event.type) {
            CommuteEventType.LEAVE -> openLeaveAt = event.timestamp
            CommuteEventType.ARRIVE -> {
                openLeaveAt?.let { leftAt ->
                    val span = MinuteSpan(minuteOf(leftAt), minuteOf(event.timestamp))
                    if (span.end > span.start) spans += span
                }
                openLeaveAt = null
            }
            CommuteEventType.AWAY -> Unit
        }
    }
    return spans.sortedBy { it.start }
}

/**
 * [base] with every span in [cuts] removed — the parts of a day's bar that should actually be
 * drawn. Overlapping and out-of-order cuts are handled, so callers can pass
 * [absentMinuteSpans]' output straight through. Returns an empty list when the cuts cover
 * everything.
 */
fun subtractSpans(base: MinuteSpan, cuts: List<MinuteSpan>): List<MinuteSpan> {
    if (base.end <= base.start) return emptyList()
    val clipped = cuts
        .map { MinuteSpan(it.start.coerceAtLeast(base.start), it.end.coerceAtMost(base.end)) }
        .filter { it.end > it.start }
        .sortedBy { it.start }
    val remaining = mutableListOf<MinuteSpan>()
    var cursor = base.start
    clipped.forEach { cut ->
        if (cut.start > cursor) remaining += MinuteSpan(cursor, cut.start)
        cursor = maxOf(cursor, cut.end)
    }
    if (cursor < base.end) remaining += MinuteSpan(cursor, base.end)
    return remaining
}
