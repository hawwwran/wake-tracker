package com.hawwwran.waketracker.model

import com.hawwwran.waketracker.model.EventType.KEYGUARD_HIDDEN
import com.hawwwran.waketracker.model.EventType.KEYGUARD_SHOWN
import com.hawwwran.waketracker.model.EventType.RECORD_BREAK
import com.hawwwran.waketracker.model.EventType.RECORD_RESUME
import com.hawwwran.waketracker.model.EventType.SCREEN_OFF
import com.hawwwran.waketracker.model.EventType.SCREEN_ON
import com.hawwwran.waketracker.model.EventType.SHUTDOWN
import com.hawwwran.waketracker.model.EventType.STARTUP
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBuilderTest {

    private fun build(vararg events: Pair<Long, Int>, lockedBefore: Boolean? = false, openEnd: Long = 10_000) =
        SessionBuilder.build(events.map { ScreenEvent(it.first, it.second) }, lockedBefore, openEnd)

    private fun spans(sessions: List<Session>) = sessions.map { it.start to it.end }

    @Test
    fun `pairs of on and off become sessions`() {
        val sessions = build(100L to SCREEN_ON, 250L to SCREEN_OFF, 400L to SCREEN_ON, 900L to SCREEN_OFF)
        assertEquals(listOf(100L to 250L, 400L to 900L), spans(sessions))
    }

    @Test
    fun `a repeated event changes nothing`() {
        val sessions = build(
            100L to SCREEN_ON, 120L to SCREEN_ON, 250L to SCREEN_OFF, 260L to SCREEN_OFF,
        )
        assertEquals(listOf(100L to 250L), spans(sessions))
    }

    @Test
    fun `a long session read twice with a shifted clock is counted once`() {
        // The system moved its stored events by 3 ms and the overlap of the next read brought them again.
        val sessions = build(100L to SCREEN_ON, 103L to SCREEN_ON, 900L to SCREEN_OFF, 903L to SCREEN_OFF)
        assertEquals(listOf(100L to 900L), spans(sessions))
    }

    @Test
    fun `a session still open is cut where the record ends`() {
        val sessions = build(100L to SCREEN_ON, openEnd = 700)
        assertEquals(listOf(100L to 700L), spans(sessions))
    }

    @Test
    fun `an off with no on before it is ignored`() {
        assertEquals(emptyList<Session>(), build(100L to SCREEN_OFF))
    }

    @Test
    fun `a session is unlocked once the keyguard goes away during it`() {
        val sessions = build(
            100L to SCREEN_ON, 110L to SCREEN_OFF,
            200L to SCREEN_ON, 205L to KEYGUARD_HIDDEN, 300L to SCREEN_OFF, 301L to KEYGUARD_SHOWN,
            400L to SCREEN_ON, 410L to SCREEN_OFF,
            lockedBefore = true,
        )
        assertEquals(listOf(false, true, false), sessions.map { it.unlocked })
    }

    @Test
    fun `waking the phone again before it locked is unlocked use`() {
        val sessions = build(
            100L to SCREEN_ON, 105L to KEYGUARD_HIDDEN, 200L to SCREEN_OFF,
            210L to SCREEN_ON, 300L to SCREEN_OFF, 305L to KEYGUARD_SHOWN,
            lockedBefore = true,
        )
        assertEquals(listOf(true, true), sessions.map { it.unlocked })
    }

    @Test
    fun `the first keyguard event tells the state before it`() {
        val shownFirst = build(100L to SCREEN_ON, 200L to SCREEN_OFF, 201L to KEYGUARD_SHOWN, lockedBefore = null)
        assertEquals(listOf(true), shownFirst.map { it.unlocked })

        val hiddenFirst = build(
            100L to SCREEN_ON, 200L to SCREEN_OFF, 300L to SCREEN_ON, 301L to KEYGUARD_HIDDEN, 400L to SCREEN_OFF,
            lockedBefore = null,
        )
        assertEquals(listOf(false, true), hiddenFirst.map { it.unlocked })
    }

    @Test
    fun `a phone with no lock screen has only unlocked sessions`() {
        val sessions = build(100L to SCREEN_ON, 200L to SCREEN_OFF, lockedBefore = null)
        assertEquals(listOf(true), sessions.map { it.unlocked })
    }

    @Test
    fun `a shutdown ends the session`() {
        val sessions = build(100L to SCREEN_ON, 500L to SHUTDOWN, 9_000L to STARTUP, 9_100L to SCREEN_ON, 9_200L to SCREEN_OFF)
        assertEquals(listOf(100L to 500L, 9_100L to 9_200L), spans(sessions))
    }

    @Test
    fun `a startup without a shutdown ends the session at the last sign of life`() {
        val sessions = build(100L to SCREEN_ON, 150L to KEYGUARD_HIDDEN, 9_000L to STARTUP, lockedBefore = true)
        assertEquals(listOf(100L to 150L), spans(sessions))
    }

    @Test
    fun `the phone is locked after a restart`() {
        val sessions = build(
            100L to SCREEN_ON, 105L to KEYGUARD_HIDDEN, 500L to SHUTDOWN,
            9_000L to STARTUP, 9_100L to SCREEN_ON, 9_200L to SCREEN_OFF,
            lockedBefore = true,
        )
        assertEquals(listOf(true, false), sessions.map { it.unlocked })
    }

    @Test
    fun `off and on in one millisecond while on is a flip`() {
        // Stored order is by type number, so the "on" (15) comes before the "off" (16).
        val sessions = build(100L to SCREEN_ON, 500L to SCREEN_ON, 500L to SCREEN_OFF, 800L to SCREEN_OFF)
        assertEquals(listOf(100L to 500L, 500L to 800L), spans(sessions))
    }

    @Test
    fun `on and off in one millisecond while off leaves the screen off`() {
        val sessions = build(500L to SCREEN_ON, 500L to SCREEN_OFF, 800L to SCREEN_OFF)
        assertEquals(emptyList<Session>(), sessions)
    }

    @Test
    fun `where the record starts, an off means the screen was on until then`() {
        val sessions = build(1_000L to RECORD_RESUME, 1_400L to SCREEN_OFF, 2_000L to SCREEN_ON, 2_500L to SCREEN_OFF, lockedBefore = null)
        assertEquals(listOf(1_000L to 1_400L, 2_000L to 2_500L), spans(sessions))
    }

    @Test
    fun `where the record starts, an on means the screen was off until then`() {
        val sessions = build(1_000L to RECORD_RESUME, 1_400L to SCREEN_ON, 1_900L to SCREEN_OFF, lockedBefore = null)
        assertEquals(listOf(1_400L to 1_900L), spans(sessions))
    }

    @Test
    fun `a hole in the record cuts the session and nothing is counted inside it`() {
        val sessions = build(
            100L to SCREEN_ON, 300L to RECORD_BREAK, 5_000L to RECORD_RESUME, 5_200L to SCREEN_OFF,
        )
        assertEquals(listOf(100L to 300L, 5_000L to 5_200L), spans(sessions))
    }

    @Test
    fun `the keyguard state is read again after a hole`() {
        val sessions = build(
            100L to SCREEN_ON, 105L to KEYGUARD_HIDDEN, 300L to RECORD_BREAK,
            5_000L to RECORD_RESUME, 5_100L to SCREEN_ON, 5_200L to SCREEN_OFF, 5_300L to SCREEN_ON, 5_301L to KEYGUARD_HIDDEN,
            5_400L to SCREEN_OFF,
            lockedBefore = true,
        )
        assertEquals(listOf(true, false, true), sessions.map { it.unlocked })
    }
}
