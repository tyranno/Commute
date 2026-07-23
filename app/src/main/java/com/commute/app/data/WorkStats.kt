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
 */
fun mergeLeaveStats(
    base: List<DailyWorkStat>,
    leaves: List<LeaveEntry>,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): List<DailyWorkStat> {
    if (leaves.isEmpty()) return base
    val creditByDay = leaves
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
 * disconnected from wifi during lunch — since it's unpaid break time either way. Any AWAY span
 * within the session that's at least [absenceThresholdMinutes] long is *also* subtracted (its
 * portion outside the lunch window, to avoid double-deducting time already covered by the lunch
 * subtraction) — shorter AWAY spans stay counted as work time, matching the 가산 연구소 운영
 * 방안 자리비움 rule. If the last ARRIVE has no matching LEAVE yet (still at work), the session
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
 * Each span is clamped to the session's *recognized* window before being measured: time outside
 * it (before the 07:00 recognition start, or past the session end) was never counted as worked in
 * the first place, so deducting it would remove it twice — that cost ~45 minutes on a day with an
 * early arrival and a pre-07:00 absence. The threshold test uses the span's real duration, since
 * "was this a 자리비움" is about how long the person was actually away, not how much of it happens
 * to be recognized work time.
 *
 * Overlapping spans are merged so shared time is only deducted once, and the lunch window is
 * derived from the session's own day so it lines up exactly with the session-level lunch
 * subtraction — computing it per-span instead would use the wrong day for an overnight session
 * and credit unpaid lunch as worked time.
 */
private fun deductibleAwayMinutes(
    awaySpans: List<Pair<Long, Long>>,
    recognizedStart: Long,
    sessionEnd: Long,
    absenceThresholdMinutes: Int,
    lunchStartMinute: Int,
    lunchEndMinute: Int
): Long {
    val clamped = awaySpans
        .filter { (start, end) -> (end - start) / 60_000 >= absenceThresholdMinutes }
        .map { (start, end) -> maxOf(start, recognizedStart) to minOf(end, sessionEnd) }
        .filter { (start, end) -> end > start }
        .sortedBy { it.first }
    if (clamped.isEmpty()) return 0

    val merged = mutableListOf<Pair<Long, Long>>()
    for ((start, end) in clamped) {
        val last = merged.lastOrNull()
        if (last != null && start <= last.second) {
            merged[merged.lastIndex] = last.first to maxOf(last.second, end)
        } else {
            merged.add(start to end)
        }
    }

    val lunchStart = timestampAtMinuteOfDay(recognizedStart, lunchStartMinute)
    val lunchEnd = timestampAtMinuteOfDay(recognizedStart, lunchEndMinute)
    // Summed in millis and divided once, so per-span truncation doesn't compound into
    // several minutes of drift across a day with many absences.
    val totalMs = merged.sumOf { (start, end) ->
        val lunchOverlap = if (lunchStartMinute < lunchEndMinute) {
            (minOf(end, lunchEnd) - maxOf(start, lunchStart)).coerceAtLeast(0)
        } else {
            0L
        }
        (end - start - lunchOverlap).coerceAtLeast(0)
    }
    return totalMs / 60_000
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
