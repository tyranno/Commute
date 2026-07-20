package com.commute.app

import com.commute.app.wifi.MAX_ARRIVE_BACKDATE_MS
import com.commute.app.wifi.arriveTimestamp
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 출근 시각이 "폴링이 깨어난 시각"이 아니라 "OS가 실제로 AP를 본 시각"으로 찍히는지 —
 * 그리고 그 소급이 넘지 말아야 할 두 하한(최대 소급 폭 / 직전 기록).
 */
class ArriveTimestampTest {

    /** 2026-07-20(월) [hour]:[minute] */
    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 20, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `도즈로 늦게 깨어난 폴링은 스캔이 본 시각으로 소급된다`() {
        // 실제로 신고된 사례: 08:23 도착, 08:30에야 폴링이 깨어남.
        assertEquals(at(8, 23), arriveTimestamp(observedAt = at(8, 23), now = at(8, 30), lastEventAt = null))
    }

    @Test
    fun `스캔 스탬프가 없으면 폴링 시각을 그대로 쓴다`() {
        assertEquals(at(8, 30), arriveTimestamp(observedAt = null, now = at(8, 30), lastEventAt = null))
    }

    @Test
    fun `30분보다 오래된 스캔 결과는 신선한 증거가 아니므로 30분까지만 소급된다`() {
        assertEquals(
            at(8, 30) - MAX_ARRIVE_BACKDATE_MS,
            arriveTimestamp(observedAt = at(7, 0), now = at(8, 30), lastEventAt = null)
        )
    }

    @Test
    fun `직전 기록보다 앞선 시각으로는 소급하지 않는다`() {
        // 18:00 자동 퇴근 후 18:20 복귀 — 소급이 퇴근 시각을 앞지르면 세션이 겹친다.
        assertEquals(
            at(18, 0),
            arriveTimestamp(observedAt = at(17, 50), now = at(18, 20), lastEventAt = at(18, 0))
        )
    }

    @Test
    fun `직전 기록이 폴링 시각보다 미래면 소급을 포기한다`() {
        // 수기 입력이나 시계 변경으로 미래 기록이 있을 수 있다. 하한이 상한을 넘으면 그냥 현재 시각.
        assertEquals(
            at(8, 30),
            arriveTimestamp(observedAt = at(8, 23), now = at(8, 30), lastEventAt = at(9, 0))
        )
    }

    @Test
    fun `미래를 가리키는 스캔 스탬프는 현재 시각으로 잘린다`() {
        assertEquals(
            at(8, 30),
            arriveTimestamp(observedAt = at(8, 35), now = at(8, 30), lastEventAt = null)
        )
    }
}
