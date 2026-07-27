package com.commute.app

import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.MinuteSpan
import com.commute.app.data.absentMinuteSpans
import com.commute.app.data.subtractSpans
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chart draws presence, not the raw span from a day's first 출근 to its last 퇴근 — these cover
 * the two ways an absence reaches it (a recorded 자리비움, and a 퇴근→출근 gap) and the geometry
 * that cuts them out of the bar.
 */
class AbsentMinuteSpansTest {

    private val dayStart: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 27, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun at(hour: Int, minute: Int): Long = dayStart + (hour * 60 + minute) * 60_000L

    private fun event(type: CommuteEventType, start: Long, end: Long? = null) =
        CommuteEvent(type = type, ssid = "iptime5G", timestamp = start, endTimestamp = end)

    @Test
    fun `a recorded away becomes a hole`() {
        val events = listOf(
            event(CommuteEventType.ARRIVE, at(7, 38)),
            event(CommuteEventType.AWAY, at(10, 28), at(16, 15))
        )

        assertEquals(listOf(MinuteSpan(10 * 60 + 28, 16 * 60 + 15)), absentMinuteSpans(events, dayStart))
    }

    @Test
    fun `a leave followed by a return the same day becomes a hole`() {
        val events = listOf(
            event(CommuteEventType.ARRIVE, at(7, 38)),
            event(CommuteEventType.LEAVE, at(10, 28)),
            event(CommuteEventType.ARRIVE, at(16, 15))
        )

        assertEquals(listOf(MinuteSpan(10 * 60 + 28, 16 * 60 + 15)), absentMinuteSpans(events, dayStart))
    }

    @Test
    fun `a closing leave with no return is not a hole`() {
        val events = listOf(
            event(CommuteEventType.ARRIVE, at(7, 38)),
            event(CommuteEventType.LEAVE, at(18, 0))
        )

        assertEquals(emptyList<MinuteSpan>(), absentMinuteSpans(events, dayStart))
    }

    @Test
    fun `an away running past midnight is cut at the day end`() {
        val events = listOf(event(CommuteEventType.AWAY, at(22, 0), dayStart + 26 * 60 * 60_000L))

        assertEquals(listOf(MinuteSpan(22 * 60, 24 * 60)), absentMinuteSpans(events, dayStart))
    }

    @Test
    fun `an away with no end is ignored`() {
        val events = listOf(event(CommuteEventType.AWAY, at(10, 0)))

        assertEquals(emptyList<MinuteSpan>(), absentMinuteSpans(events, dayStart))
    }

    @Test
    fun `subtracting one hole splits the bar in two`() {
        val result = subtractSpans(MinuteSpan(458, 1020), listOf(MinuteSpan(628, 975)))

        assertEquals(listOf(MinuteSpan(458, 628), MinuteSpan(975, 1020)), result)
    }

    @Test
    fun `overlapping holes are merged`() {
        val result = subtractSpans(MinuteSpan(0, 100), listOf(MinuteSpan(20, 60), MinuteSpan(40, 80)))

        assertEquals(listOf(MinuteSpan(0, 20), MinuteSpan(80, 100)), result)
    }

    @Test
    fun `holes are clipped to the bar and out-of-order input is handled`() {
        val result = subtractSpans(MinuteSpan(50, 100), listOf(MinuteSpan(80, 120), MinuteSpan(0, 60)))

        assertEquals(listOf(MinuteSpan(60, 80)), result)
    }

    @Test
    fun `a hole covering the whole bar leaves nothing to draw`() {
        assertEquals(emptyList<MinuteSpan>(), subtractSpans(MinuteSpan(50, 100), listOf(MinuteSpan(10, 200))))
    }

    @Test
    fun `no holes leaves the bar intact`() {
        assertEquals(listOf(MinuteSpan(458, 1020)), subtractSpans(MinuteSpan(458, 1020), emptyList()))
    }
}
