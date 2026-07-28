package com.commute.app

import com.commute.app.data.DailyWorkStat
import com.commute.app.data.LeaveEntry
import com.commute.app.data.LeaveType
import com.commute.app.data.mergeLeaveStats
import com.commute.app.data.startOfDay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A 연차 declared for a day that hasn't arrived yet is a plan, not time taken — it must not
 * inflate 이번주 총 근무시간 before the week gets there. Only the day's own arrival flips it into
 * credited time.
 */
class MergeLeaveStatsTest {

    // 2026-07-27(월) 00:00 KST 기준. Tuesday = +1 day, Friday = +4 days.
    private val monday = startOfDay(1785110400000L)
    private val tuesday = monday + 24 * 60 * 60 * 1000L
    private val friday = monday + 4 * 24 * 60 * 60 * 1000L

    private fun annualLeave(day: Long) =
        LeaveEntry(type = LeaveType.ANNUAL, date = day, startMinute = null, endMinute = null, note = "")

    @Test
    fun `a future day's leave earns no credit yet`() {
        val result = mergeLeaveStats(
            base = emptyList(),
            leaves = listOf(annualLeave(friday)),
            lunchStartMinute = 680,
            lunchEndMinute = 740,
            nowMillis = tuesday + 12 * 60 * 60 * 1000L // 화요일 정오
        )

        val fridayStat = result.singleOrNull { it.dayStart == friday }
        assertEquals(0L, fridayStat?.leaveMinutes ?: 0L)
    }

    @Test
    fun `today's leave is credited immediately`() {
        val result = mergeLeaveStats(
            base = emptyList(),
            leaves = listOf(annualLeave(tuesday)),
            lunchStartMinute = 680,
            lunchEndMinute = 740,
            nowMillis = tuesday + 12 * 60 * 60 * 1000L
        )

        assertEquals(480L, result.single { it.dayStart == tuesday }.leaveMinutes)
    }

    @Test
    fun `a past day's leave is credited`() {
        val result = mergeLeaveStats(
            base = emptyList(),
            leaves = listOf(annualLeave(monday)),
            lunchStartMinute = 680,
            lunchEndMinute = 740,
            nowMillis = tuesday + 12 * 60 * 60 * 1000L
        )

        assertEquals(480L, result.single { it.dayStart == monday }.leaveMinutes)
    }

    @Test
    fun `a future leave does not appear in the merged stats at all when there is no RF day for it`() {
        // No stat entry means no bar and no credit — matching a plain future-only 연차 not yet reachable.
        val result = mergeLeaveStats(
            base = emptyList(),
            leaves = listOf(annualLeave(friday)),
            lunchStartMinute = 680,
            lunchEndMinute = 740,
            nowMillis = tuesday
        )

        assertEquals(true, result.none { it.dayStart == friday && it.leaveMinutes > 0 })
    }

    @Test
    fun `an RF-attended day keeps its worked minutes when its leave is still in the future`() {
        // Guards against a regression where filtering leaves also dropped the day's existing RF stat.
        val base = listOf(DailyWorkStat(dayStart = friday, workedMinutes = 120))
        val result = mergeLeaveStats(
            base = base,
            leaves = listOf(annualLeave(friday)),
            lunchStartMinute = 680,
            lunchEndMinute = 740,
            nowMillis = tuesday
        )

        val fridayStat = result.single { it.dayStart == friday }
        assertEquals(120L, fridayStat.workedMinutes)
        assertEquals(0L, fridayStat.leaveMinutes)
    }
}
