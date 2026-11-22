package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType.KEYGUARD_HIDDEN
import com.hawwwran.waketracker.model.EventType.KEYGUARD_SHOWN
import com.hawwwran.waketracker.model.EventType.SCREEN_OFF
import com.hawwwran.waketracker.model.EventType.SCREEN_ON
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Session
import com.hawwwran.waketracker.model.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {

    private val store = MemoryEventStore()
    private val history = History(store)

    private fun stored(recordStart: Long, reachesUntil: Long, gap: Span? = null, vararg events: Pair<Long, Int>): Coverage {
        store.commit(ReadOutcome(events.map { ScreenEvent(it.first, it.second) }, reachesUntil, reachesUntil, recordStart, gap))
        return store.coverage()!!
    }

    @Test
    fun `a session that began before the range is found whole`() {
        val coverage = stored(
            0, 10_000, null,
            100L to SCREEN_ON, 200L to SCREEN_OFF, 201L to KEYGUARD_SHOWN,
            900L to SCREEN_ON, 905L to KEYGUARD_HIDDEN, 1_500L to SCREEN_OFF,
            3_000L to SCREEN_ON, 3_100L to SCREEN_OFF,
        )
        assertEquals(
            listOf(Session(900, 1_500, unlocked = true)),
            history.sessions(1_000, 2_000, coverage),
        )
    }

    @Test
    fun `the keyguard state is carried into the range`() {
        val coverage = stored(
            0, 10_000, null,
            100L to SCREEN_ON, 105L to KEYGUARD_HIDDEN, 200L to SCREEN_OFF, 201L to KEYGUARD_SHOWN,
            1_100L to SCREEN_ON, 1_200L to SCREEN_OFF,
        )
        assertEquals(listOf(Session(1_100, 1_200, unlocked = false)), history.sessions(1_000, 2_000, coverage))
    }

    @Test
    fun `a session still open ends where the record does`() {
        val coverage = stored(0, 5_000, null, 4_000L to SCREEN_ON)
        assertEquals(listOf(Session(4_000, 5_000, unlocked = true)), history.sessions(3_000, 9_000, coverage))
    }

    @Test
    fun `a session running past the range ends at its end`() {
        val coverage = stored(0, 9_000, null, 4_000L to SCREEN_ON, 8_000L to SCREEN_OFF)
        assertEquals(listOf(Session(4_000, 5_000, unlocked = true)), history.sessions(3_000, 5_000, coverage))
    }

    @Test
    fun `the screen was on when the record started`() {
        val coverage = stored(1_000, 9_000, null, 1_300L to SCREEN_OFF, 2_000L to SCREEN_ON, 2_100L to SCREEN_OFF)
        assertEquals(
            listOf(Session(1_000, 1_300, unlocked = true), Session(2_000, 2_100, unlocked = true)),
            history.sessions(0, 9_000, coverage),
        )
    }

    @Test
    fun `a gap cuts a session that was open when it began`() {
        val gap = Span(2_000, 6_000)
        val coverage = stored(
            0, 9_000, gap,
            1_500L to SCREEN_ON,
            6_500L to SCREEN_OFF, 7_000L to SCREEN_ON, 7_100L to SCREEN_OFF,
        )
        assertEquals(
            listOf(Session(1_500, 2_000, true), Session(6_000, 6_500, true), Session(7_000, 7_100, true)),
            history.sessions(0, 9_000, coverage),
        )
    }

    @Test
    fun `a range after a gap does not look back across it`() {
        val gap = Span(2_000, 6_000)
        val coverage = stored(
            0, 9_000, gap,
            1_500L to SCREEN_ON, 6_500L to SCREEN_OFF,
        )
        assertEquals(listOf(Session(6_000, 6_500, true)), history.sessions(6_200, 9_000, coverage))
    }

    @Test
    fun `a range outside the record is empty`() {
        val coverage = stored(1_000, 5_000, null, 1_500L to SCREEN_ON, 1_600L to SCREEN_OFF)
        assertEquals(emptyList<Session>(), history.sessions(0, 900, coverage))
        assertEquals(emptyList<Session>(), history.sessions(6_000, 9_000, coverage))
    }
}
