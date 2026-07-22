package com.commute.app

import com.commute.app.wifi.leaveTimestamp
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 퇴근 시각이 "마지막으로 감지된 시각"(lastSeenAt)에서 마진만큼 앞당겨지는지 —
 * 그리고 그 보정이 넘지 말아야 할 하한(직전 기록, 곧 이 세션의 출근).
 */
class LeaveTimestampTest {

    /** 2026-07-20(월) [hour]:[minute] */
    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 20, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `퇴근 시각은 마진만큼 앞당겨진다`() {
        // 실제 신고 사례: 19:14 이탈, 전파 꼬리로 19:18까지 잡힘 → 3분 마진이면 19:15.
        assertEquals(
            at(19, 15),
            leaveTimestamp(lastSeenAt = at(19, 18), marginMinutes = 3, floorAt = at(9, 0))
        )
    }

    @Test
    fun `마진이 0이면 마지막 감지 시각을 그대로 쓴다`() {
        assertEquals(
            at(19, 18),
            leaveTimestamp(lastSeenAt = at(19, 18), marginMinutes = 0, floorAt = at(9, 0))
        )
    }

    @Test
    fun `보정이 이 세션 출근보다 앞서지 않는다`() {
        // 08:58 출근 후 09:00에 곧바로 이탈이 잡혀도 3분을 빼면 08:57 — 출근을 앞질러 세션이 음수가 된다.
        assertEquals(
            at(8, 58),
            leaveTimestamp(lastSeenAt = at(9, 0), marginMinutes = 3, floorAt = at(8, 58))
        )
    }

    @Test
    fun `직전 기록이 없으면 마진만큼 그대로 앞당긴다`() {
        assertEquals(
            at(19, 15),
            leaveTimestamp(lastSeenAt = at(19, 18), marginMinutes = 3, floorAt = null)
        )
    }

    @Test
    fun `하한이 마지막 감지 시각을 넘으면 보정을 포기한다`() {
        // 수기 입력 등으로 마지막 기록이 lastSeenAt보다 미래면, 앞당기지 말고 lastSeenAt 그대로.
        assertEquals(
            at(19, 18),
            leaveTimestamp(lastSeenAt = at(19, 18), marginMinutes = 3, floorAt = at(19, 30))
        )
    }
}
