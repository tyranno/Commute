package com.commute.app

import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.awayDisplayRange
import com.commute.app.data.awayMinutesExcludingLunch
import com.commute.app.data.computeDailyWorkStats
import com.commute.app.data.startOfDay
import com.commute.app.data.timestampAtMinuteOfDay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 점심시간을 걸치는 자리비움(AWAY) 처리 규칙 — 점심은 자리비움에서 먼저 도려내고, 점심 바깥 조각에만
 * 자리비움 임계값을 적용한다. 점심 앞뒤로 조금 삐져나온 정도(각 조각 < 임계값)는 근무로 인정된다.
 */
class LunchAwayWorkStatsTest {

    // 2024-01-15(월) 자정 기준 하루. 분(minute-of-day)으로 이벤트 시각을 만든다.
    private val dayBase = startOfDay(1_705_276_800_000L)
    private fun at(minuteOfDay: Int) = timestampAtMinuteOfDay(dayBase, minuteOfDay)
    private fun arrive(min: Int) = CommuteEvent(type = CommuteEventType.ARRIVE, ssid = "office", timestamp = at(min))
    private fun leave(min: Int) = CommuteEvent(type = CommuteEventType.LEAVE, ssid = "office", timestamp = at(min))
    private fun away(startMin: Int, endMin: Int) =
        CommuteEvent(type = CommuteEventType.AWAY, ssid = "office", timestamp = at(startMin), endTimestamp = at(endMin))

    private val lunchStart = 11 * 60 + 20 // 11:20
    private val lunchEnd = 12 * 60 + 20   // 12:20
    private val threshold = 10

    private fun workedMinutes(events: List<CommuteEvent>): Long =
        computeDailyWorkStats(events, lunchStart, lunchEnd, threshold, nowMillis = at(23 * 60))
            .single().workedMinutes

    @Test
    fun `점심을 걸친 짧은 삐져나옴은 자리비움으로 차감되지 않는다`() {
        // 09:00~18:00 = 540분, 점심 60분 차감 = 480분. 자리비움 11:15~12:25는 점심 바깥이 5분/5분뿐 → 차감 없음.
        val worked = workedMinutes(listOf(arrive(9 * 60), away(11 * 60 + 15, 12 * 60 + 25), leave(18 * 60)))
        assertEquals(480L, worked)
    }

    @Test
    fun `점심을 걸친 긴 자리비움은 점심 바깥 부분만 차감된다`() {
        // 자리비움 11:00~13:00: 점심 바깥 20분(11:00~11:20) + 40분(12:20~13:00) = 60분 차감. 540-60(점심)-60 = 420.
        val worked = workedMinutes(listOf(arrive(9 * 60), away(11 * 60, 13 * 60), leave(18 * 60)))
        assertEquals(420L, worked)
    }

    @Test
    fun `점심과 무관한 자리비움은 그대로 임계값 적용`() {
        // 14:00~14:15 = 15분 ≥ 10 → 차감. 540-60(점심)-15 = 465.
        val worked = workedMinutes(listOf(arrive(9 * 60), away(14 * 60, 14 * 60 + 15), leave(18 * 60)))
        assertEquals(465L, worked)
    }

    @Test
    fun `자리비움이 점심 안에 완전히 들어가면 차감 없음`() {
        // 11:30~12:00은 점심 안 → 도려내면 남는 조각 0. 540-60(점심) = 480.
        val worked = workedMinutes(listOf(arrive(9 * 60), away(11 * 60 + 30, 12 * 60), leave(18 * 60)))
        assertEquals(480L, worked)
    }

    // --- 표시용 자리비움 시간(점심 제외) — awayMinutesExcludingLunch ---

    private fun awayShown(startMin: Int, endMin: Int) =
        awayMinutesExcludingLunch(at(startMin), at(endMin), lunchStart, lunchEnd)

    @Test
    fun `표시 자리비움은 점심을 걸치면 점심 바깥 분만 보여준다`() {
        // 11:59~13:00 = 61분(벽시계). 점심 바깥: 11:59~12:20(21분) + 12:20~13:00(40분) = 61분? 아니, 점심 겹침 21분 제외.
        // 11:59~12:20(21분)은 점심 안이므로 도려냄 → 남는 건 12:20~13:00(40분).
        assertEquals(40L, awayShown(11 * 60 + 59, 13 * 60))
    }

    @Test
    fun `표시 자리비움이 점심 안에 완전히 들면 0분`() {
        assertEquals(0L, awayShown(11 * 60 + 30, 12 * 60))
    }

    @Test
    fun `표시 자리비움이 점심과 무관하면 벽시계 그대로`() {
        assertEquals(15L, awayShown(14 * 60, 14 * 60 + 15))
    }

    @Test
    fun `점심 설정이 없으면 표시 자리비움은 벽시계 그대로`() {
        assertEquals(61L, awayMinutesExcludingLunch(at(11 * 60 + 59), at(13 * 60), 0, 0))
    }

    // --- 표시용 자리비움 시각 범위(점심 경계로 자름) — awayDisplayRange ---

    private fun awayRange(startMin: Int, endMin: Int) =
        awayDisplayRange(at(startMin), at(endMin), lunchStart, lunchEnd)

    @Test
    fun `점심 안에서 시작하면 표시 시작이 점심 끝으로 밀린다`() {
        // 11:59~13:00, 점심 11:20~12:20 → 표시는 12:20~13:00.
        assertEquals(at(12 * 60 + 20) to at(13 * 60), awayRange(11 * 60 + 59, 13 * 60))
    }

    @Test
    fun `점심 안에서 끝나면 표시 끝이 점심 시작으로 당겨진다`() {
        // 11:00~12:00 → 점심 바깥은 11:00~11:20뿐 → 표시 11:00~11:20.
        assertEquals(at(11 * 60) to at(11 * 60 + 20), awayRange(11 * 60, 12 * 60))
    }

    @Test
    fun `점심을 완전히 걸치면 양쪽 실제 시각을 유지한다`() {
        // 11:00~13:00 → 점심이 가운데 → 양 끝 그대로.
        assertEquals(at(11 * 60) to at(13 * 60), awayRange(11 * 60, 13 * 60))
    }

    @Test
    fun `점심 안에 완전히 들면 표시할 범위가 없다`() {
        assertEquals(null, awayRange(11 * 60 + 30, 12 * 60))
    }

    @Test
    fun `점심과 무관하면 시각 범위는 그대로`() {
        assertEquals(at(14 * 60) to at(14 * 60 + 15), awayRange(14 * 60, 14 * 60 + 15))
    }

    @Test
    fun `점심 설정이 없으면 시각 범위도 벽시계 그대로`() {
        assertEquals(at(11 * 60 + 59) to at(13 * 60), awayDisplayRange(at(11 * 60 + 59), at(13 * 60), 0, 0))
    }
}
