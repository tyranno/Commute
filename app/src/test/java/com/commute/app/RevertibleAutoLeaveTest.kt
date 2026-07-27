package com.commute.app

import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.wifi.revertibleAutoLeave
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An auto-leave 퇴근 is a guess made while an absence is still running; coming back the same day
 * disproves it. These cover both that undo and the cases where the guess must be left alone.
 */
class RevertibleAutoLeaveTest {

    private fun at(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun leave(id: Long, timestamp: Long) = CommuteEvent(
        id = id,
        type = CommuteEventType.LEAVE,
        ssid = "iptime5G",
        timestamp = timestamp,
        endTimestamp = null
    )

    @Test
    fun `same-day return reverts the auto-leave`() {
        val leftAt = at(27, 10, 28)
        val event = leave(29L, leftAt)

        val result = revertibleAutoLeave(event, autoLeaveEventId = 29L, autoLeaveEventAt = leftAt, now = at(27, 16, 15))

        assertEquals(event, result)
    }

    @Test
    fun `returning the next day is a new session, not an undo`() {
        val leftAt = at(27, 10, 28)

        val result = revertibleAutoLeave(
            leave(29L, leftAt),
            autoLeaveEventId = 29L,
            autoLeaveEventAt = leftAt,
            now = at(28, 8, 5)
        )

        assertNull(result)
    }

    @Test
    fun `no marker means the leave was not an auto-leave`() {
        val leftAt = at(27, 10, 28)

        assertNull(
            revertibleAutoLeave(leave(29L, leftAt), autoLeaveEventId = null, autoLeaveEventAt = null, now = at(27, 16, 15))
        )
    }

    @Test
    fun `a user-retimed leave is left alone`() {
        // The row still has the id auto-leave wrote, but the user corrected its time — their edit wins.
        val result = revertibleAutoLeave(
            leave(29L, at(27, 11, 0)),
            autoLeaveEventId = 29L,
            autoLeaveEventAt = at(27, 10, 28),
            now = at(27, 16, 15)
        )

        assertNull(result)
    }

    @Test
    fun `a newer event since the auto-leave blocks the undo`() {
        val leftAt = at(27, 10, 28)
        val newer = CommuteEvent(
            id = 30L,
            type = CommuteEventType.ARRIVE,
            ssid = "iptime5G",
            timestamp = at(27, 12, 0),
            endTimestamp = null
        )

        assertNull(revertibleAutoLeave(newer, autoLeaveEventId = 29L, autoLeaveEventAt = leftAt, now = at(27, 16, 15)))
    }

    @Test
    fun `a leave retyped by the user is left alone`() {
        val leftAt = at(27, 10, 28)
        val retyped = leave(29L, leftAt).copy(type = CommuteEventType.AWAY, endTimestamp = at(27, 12, 0))

        assertNull(revertibleAutoLeave(retyped, autoLeaveEventId = 29L, autoLeaveEventAt = leftAt, now = at(27, 16, 15)))
    }

    @Test
    fun `a return stamped at or before the leave is rejected`() {
        val leftAt = at(27, 10, 28)

        assertNull(revertibleAutoLeave(leave(29L, leftAt), autoLeaveEventId = 29L, autoLeaveEventAt = leftAt, now = leftAt))
    }

    @Test
    fun `nothing on record means nothing to undo`() {
        assertNull(revertibleAutoLeave(null, autoLeaveEventId = 29L, autoLeaveEventAt = at(27, 10, 28), now = at(27, 16, 15)))
    }
}
