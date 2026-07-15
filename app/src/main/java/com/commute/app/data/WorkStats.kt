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
    val open: Boolean = false
)

/**
 * Pairs ARRIVE→LEAVE events chronologically into sessions and sums each session's
 * duration per calendar day. A session's effective start is clamped forward to 07:00
 * (근무 인정 시간, [WORK_RECOGNITION_START_MINUTE]) — arriving earlier doesn't count toward
 * worked time or move the chart's bar earlier, matching the 가산 연구소 운영 방안's 근무 인정
 * 시간 07:00~22:00 lower bound. The configured lunch window is always subtracted from a
 * session that spans it — regardless of whether the person actually disconnected from
 * wifi during lunch — since it's unpaid break time either way; shorter, non-lunch
 * absences stay counted as work time, matching the 가산 연구소 운영 방안 자리비움 rule.
 * If the last ARRIVE has no matching LEAVE yet (still at work), the session is closed at
 * [nowMillis] so "today" reflects the ongoing session, and that day's [DailyWorkStat.open]
 * is true.
 */
fun computeDailyWorkStats(
    events: List<CommuteEvent>,
    lunchStartMinute: Int,
    lunchEndMinute: Int,
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

    fun closeSession(sessionStart: Long, sessionEnd: Long, stillOpen: Boolean) {
        if (sessionEnd <= sessionStart) return
        val workStart = timestampAtMinuteOfDay(sessionStart, WORK_RECOGNITION_START_MINUTE)
        val recognizedStart = maxOf(sessionStart, workStart)
        if (recognizedStart >= sessionEnd) return // entire session falls before 07:00 — nothing recognized

        val rawMinutes = (sessionEnd - recognizedStart) / 60_000
        val minutes = rawMinutes - lunchOverlapMinutes(recognizedStart, sessionEnd, lunchStartMinute, lunchEndMinute)
        val acc = byDay.getOrPut(startOfDay(sessionStart)) { DayAccum() }
        acc.minutes += minutes.coerceAtLeast(0)
        acc.rawMinutes += rawMinutes.coerceAtLeast(0)
        if (acc.firstArrive == null || recognizedStart < acc.firstArrive!!) acc.firstArrive = recognizedStart
        acc.open = stillOpen
        if (!stillOpen && (acc.lastLeave == null || sessionEnd > acc.lastLeave!!)) acc.lastLeave = sessionEnd
    }

    var pendingArriveAt: Long? = null
    for (event in sorted) {
        when (event.type) {
            CommuteEventType.ARRIVE -> pendingArriveAt = event.timestamp
            CommuteEventType.LEAVE -> {
                val arriveAt = pendingArriveAt ?: continue
                pendingArriveAt = null
                closeSession(arriveAt, event.timestamp, stillOpen = false)
            }
            CommuteEventType.AWAY -> Unit
        }
    }
    pendingArriveAt?.let { closeSession(it, nowMillis, stillOpen = true) }

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
