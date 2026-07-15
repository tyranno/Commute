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

/**
 * Worked minutes for the day starting at [dayStart], plus that day's overall span — the
 * earliest ARRIVE and the latest LEAVE (or null/[open] if the last session hasn't closed
 * yet) — so a UI can plot both "how long" and "when" from one value.
 */
data class DailyWorkStat(
    val dayStart: Long,
    val workedMinutes: Long,
    val firstArriveAt: Long? = null,
    val lastLeaveAt: Long? = null,
    val open: Boolean = false
)

/**
 * Pairs ARRIVE→LEAVE events chronologically into sessions and sums each session's
 * duration per calendar day. The configured lunch window is always subtracted from a
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
        var firstArrive: Long? = null,
        var lastLeave: Long? = null,
        var open: Boolean = false
    )
    val byDay = linkedMapOf<Long, DayAccum>()

    fun closeSession(sessionStart: Long, sessionEnd: Long, stillOpen: Boolean) {
        if (sessionEnd <= sessionStart) return
        val minutes = (sessionEnd - sessionStart) / 60_000 -
            lunchOverlapMinutes(sessionStart, sessionEnd, lunchStartMinute, lunchEndMinute)
        val acc = byDay.getOrPut(startOfDay(sessionStart)) { DayAccum() }
        acc.minutes += minutes.coerceAtLeast(0)
        if (acc.firstArrive == null || sessionStart < acc.firstArrive!!) acc.firstArrive = sessionStart
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
        DailyWorkStat(day, acc.minutes, acc.firstArrive, acc.lastLeave, acc.open)
    }.sortedBy { it.dayStart }
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
