package com.commute.app

import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.encodeJournalLine
import com.commute.app.data.journalKey
import com.commute.app.data.parseJournalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 복구 저널(RecoveryJournal)의 순수 로직 — 한 줄 인코딩/디코딩이 왕복하는지, 손상된 줄이 전체를
 * 무너뜨리지 않는지, 그리고 DB에 없는 기록을 (type+timestamp)로 골라내는 매칭이 맞는지.
 */
class RecoveryJournalTest {

    private fun ev(type: CommuteEventType, ts: Long, id: Long = 0L, end: Long? = null) =
        CommuteEvent(id = id, type = type, ssid = "iptime5G", timestamp = ts, endTimestamp = end)

    @Test
    fun `한 줄 인코딩 후 디코딩하면 원래 이벤트로 돌아온다`() {
        val original = ev(CommuteEventType.AWAY, ts = 1_700_000_000_000L, id = 42L, end = 1_700_000_600_000L)
        val parsed = parseJournalLine(encodeJournalLine(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `endTimestamp가 null이어도 왕복한다`() {
        val original = ev(CommuteEventType.ARRIVE, ts = 123L, id = 1L)
        assertEquals(original, parseJournalLine(encodeJournalLine(original)))
    }

    @Test
    fun `빈 줄이나 손상된 줄은 null로 걸러진다`() {
        assertNull(parseJournalLine(""))
        assertNull(parseJournalLine("   "))
        assertNull(parseJournalLine("{ this is not json"))
        assertNull(parseJournalLine("{\"t\":\"NOPE\",\"ts\":1}"))
    }

    @Test
    fun `키는 type과 timestamp로만 정해져 id와 무관하다`() {
        // 복원 시 Room이 id를 새로 발급하므로 식별은 내용(type+timestamp)이어야 한다.
        val a = ev(CommuteEventType.ARRIVE, ts = 500L, id = 7L)
        val b = ev(CommuteEventType.ARRIVE, ts = 500L, id = 999L)
        assertEquals(journalKey(a), journalKey(b))
    }

    @Test
    fun `type이 다르면 같은 시각이라도 키가 다르다`() {
        val arrive = ev(CommuteEventType.ARRIVE, ts = 500L)
        val leave = ev(CommuteEventType.LEAVE, ts = 500L)
        assertTrue(journalKey(arrive) != journalKey(leave))
    }
}
