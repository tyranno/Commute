package com.commute.app

import com.commute.app.wifi.autoLeaveDue
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자리비움이 언제 퇴근으로 확정되는지 — 두 트리거(지속 시간 / 근무 인정 시간 종료)와 그 경계. */
class AutoLeaveDueTest {

    private val threeHours = 3 * 60
    private val tenPm = 22 * 60

    /** 2026-07-20(월) [hour]:[minute] */
    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 20, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `점심시간 길이의 자리비움은 퇴근이 아니다`() {
        assertFalse(autoLeaveDue(at(11, 30), at(12, 30), threeHours, tenPm))
    }

    @Test
    fun `3시간 직전까지는 퇴근이 아니고 3시간을 채우면 퇴근이다`() {
        val awaySince = at(14, 0)
        assertFalse(autoLeaveDue(awaySince, at(16, 59), threeHours, tenPm))
        assertTrue(autoLeaveDue(awaySince, at(17, 0), threeHours, tenPm))
    }

    @Test
    fun `3시간을 못 채웠어도 22시를 넘기면 퇴근이다`() {
        // 20:30 자리비움 시작 → 23:30이 되어야 3시간인데, 22:00에 이미 확정된다.
        val awaySince = at(20, 30)
        assertFalse(autoLeaveDue(awaySince, at(21, 59), threeHours, tenPm))
        assertTrue(autoLeaveDue(awaySince, at(22, 0), threeHours, tenPm))
    }

    @Test
    fun `이미 22시를 넘긴 뒤 시작된 자리비움은 곧바로 퇴근이다`() {
        assertTrue(autoLeaveDue(at(22, 40), at(22, 41), threeHours, tenPm))
    }

    @Test
    fun `도즈로 폴링이 통째로 밀려도 첫 깨어난 폴링에서 판정된다`() {
        // 15:00에 자리를 비웠고 폰이 자느라 19:00에야 처음 폴링 — 경과 시간은 awaySince 기준이라
        // 중간 폴링이 없었다는 사실이 판정에 영향을 주지 않는다.
        assertTrue(autoLeaveDue(at(15, 0), at(19, 0), threeHours, tenPm))
    }

    @Test
    fun `기준을 1시간으로 줄이면 그만큼 빨리 확정된다`() {
        val awaySince = at(14, 0)
        assertFalse(autoLeaveDue(awaySince, at(14, 59), 60, tenPm))
        assertTrue(autoLeaveDue(awaySince, at(15, 0), 60, tenPm))
    }
}
