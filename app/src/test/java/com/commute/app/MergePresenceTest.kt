package com.commute.app

import com.commute.app.ble.CompanyBeaconDetection
import com.commute.app.ble.mergePresence
import com.commute.app.wifi.CompanyWifiDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wi-Fi와 BLE 병행 감지 병합 규칙 — 둘 중 하나만 잡혀도 "자리에 있음"이고, 출근 시각은 가장 이른
 * 최초 감지, 퇴근 시각의 기준이 되는 마지막 감지는 가장 늦은 감지여야 한다.
 */
class MergePresenceTest {

    private fun wifi(nearby: Boolean, observedAt: Long? = null, lastObservedAt: Long? = null) =
        CompanyWifiDetection(nearby, observedAt, lastObservedAt)

    private fun ble(nearby: Boolean, observedAt: Long? = null, lastObservedAt: Long? = null) =
        CompanyBeaconDetection(nearby, observedAt, lastObservedAt)

    @Test
    fun `둘 다 없으면 자리 없음`() {
        val merged = mergePresence(wifi(false), ble(false))
        assertFalse(merged.nearby)
        assertEquals(null, merged.observedAt)
        assertEquals(null, merged.lastObservedAt)
    }

    @Test
    fun `와이파이만 잡혀도 자리 있음`() {
        assertTrue(mergePresence(wifi(true), ble(false)).nearby)
    }

    @Test
    fun `비콘만 잡혀도 자리 있음`() {
        assertTrue(mergePresence(wifi(false), ble(true)).nearby)
    }

    @Test
    fun `최초 감지는 두 신호 중 더 이른 쪽`() {
        val merged = mergePresence(
            wifi(true, observedAt = 1_000L, lastObservedAt = 5_000L),
            ble(true, observedAt = 800L, lastObservedAt = 4_000L)
        )
        assertEquals(800L, merged.observedAt)
    }

    @Test
    fun `마지막 감지는 두 신호 중 더 늦은 쪽`() {
        val merged = mergePresence(
            wifi(true, observedAt = 1_000L, lastObservedAt = 5_000L),
            ble(true, observedAt = 800L, lastObservedAt = 6_500L)
        )
        assertEquals(6_500L, merged.lastObservedAt)
    }

    @Test
    fun `한 쪽 시각이 null이면 다른 쪽 값을 쓴다`() {
        // 와이파이는 실시간 연결이라 시각이 null(=지금)일 수 있고, 비콘은 스캔 시각을 갖는다.
        val merged = mergePresence(
            wifi(true, observedAt = null, lastObservedAt = null),
            ble(true, observedAt = 3_000L, lastObservedAt = 3_200L)
        )
        assertEquals(3_000L, merged.observedAt)
        assertEquals(3_200L, merged.lastObservedAt)
    }
}
