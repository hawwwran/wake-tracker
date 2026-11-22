package com.hawwwran.waketracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StatsTest {

    private val prague = ZoneId.of("Europe/Prague")
    private val clock = DayClock(prague, startHour = 4)
    private val saturday = LocalDate.of(2026, 11, 21)
    private val sunday = LocalDate.of(2026, 11, 22)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(prague).toInstant().toEpochMilli()

    private fun session(from: String, to: String, unlocked: Boolean = true) = Session(at(from), at(to), unlocked)

    private val wholeWeekend = Coverage(at("2026-11-20T00:00:00"), at("2026-11-23T12:00:00"), emptyList())

    @Test
    fun `a day sums its sessions and knows its first and last`() {
        val sessions = listOf(
            session("2026-11-21T07:10:00", "2026-11-21T07:25:00"),
            session("2026-11-21T12:00:00", "2026-11-21T13:00:00"),
            session("2026-11-21T23:50:00", "2026-11-22T00:30:00"),
        )
        val day = Stats.day(saturday, clock, sessions, wholeWeekend, countLockScreen = true)

        assertEquals((15 + 60 + 40) * MINUTE, day.total)
        assertEquals(3, day.timesOn)
        assertEquals(at("2026-11-21T07:10:00"), day.firstOn)
        assertEquals(at("2026-11-22T00:30:00"), day.lastOn)
        assertTrue(day.complete)
        assertTrue(day.firstOnCertain && day.lastOnCertain)
        assertFalse(day.ongoing)
    }

    @Test
    fun `a session over the day boundary is split and counted where it began`() {
        val sessions = listOf(session("2026-11-22T03:30:00", "2026-11-22T04:45:00"))
        val before = Stats.day(saturday, clock, sessions, wholeWeekend, countLockScreen = true)
        val after = Stats.day(sunday, clock, sessions, wholeWeekend, countLockScreen = true)

        assertEquals(30 * MINUTE, before.total)
        assertEquals(1, before.timesOn)
        assertEquals(45 * MINUTE, after.total)
        assertEquals(0, after.timesOn)
        assertEquals(at("2026-11-22T04:00:00"), after.firstOn)
    }

    @Test
    fun `lock-screen wakes can be left out`() {
        val sessions = listOf(
            session("2026-11-21T09:00:00", "2026-11-21T09:30:00"),
            session("2026-11-21T23:00:00", "2026-11-21T23:10:00"),
            session("2026-11-22T00:36:41", "2026-11-22T00:36:49", unlocked = false),
        )
        val all = Stats.day(saturday, clock, sessions, wholeWeekend, countLockScreen = true)
        val used = Stats.day(saturday, clock, sessions, wholeWeekend, countLockScreen = false)

        assertEquals(at("2026-11-22T00:36:49"), all.lastOn)
        assertEquals(3, all.timesOn)
        assertEquals(at("2026-11-21T23:10:00"), used.lastOn)
        assertEquals(2, used.timesOn)
        assertEquals(40 * MINUTE, used.total)
    }

    @Test
    fun `the day the record starts in is not complete and its first on is not certain`() {
        val coverage = Coverage(at("2026-11-21T16:40:00"), at("2026-11-23T12:00:00"), emptyList())
        val sessions = listOf(session("2026-11-21T16:42:00", "2026-11-21T16:44:00"), session("2026-11-21T23:00:00", "2026-11-21T23:05:00"))
        val day = Stats.day(saturday, clock, sessions, coverage, countLockScreen = true)

        assertFalse(day.complete)
        assertTrue(day.hasData)
        assertFalse(day.firstOnCertain)
        assertTrue(day.lastOnCertain)
        assertEquals(listOf(Span(at("2026-11-21T16:40:00"), at("2026-11-22T04:00:00"))), day.known)
        assertEquals(listOf(Span(at("2026-11-21T04:00:00"), at("2026-11-21T16:40:00"))), day.missing)
    }

    @Test
    fun `today is known up to the last read and can still be running`() {
        val coverage = Coverage(at("2026-11-20T00:00:00"), at("2026-11-22T17:00:00"), emptyList())
        val sessions = listOf(session("2026-11-22T09:12:00", "2026-11-22T09:15:00"), session("2026-11-22T16:31:00", "2026-11-22T17:00:00"))
        val day = Stats.day(sunday, clock, sessions, coverage, countLockScreen = true)

        assertFalse(day.complete)
        // The hours of today still to come are not missing from the record.
        assertEquals(emptyList<Span>(), day.missing)
        assertTrue(day.ongoing)
        assertTrue(day.firstOnCertain)
        assertFalse(day.lastOnCertain)
    }

    @Test
    fun `a day outside the record has no data`() {
        val future = Stats.day(LocalDate.of(2026, 11, 25), clock, emptyList(), wholeWeekend, countLockScreen = true)
        assertFalse(future.hasData)
        val noRecord = Stats.day(sunday, clock, emptyList(), null, countLockScreen = true)
        assertFalse(noRecord.hasData)
    }

    @Test
    fun `a hole splits what is known of the day`() {
        val gap = Span(at("2026-11-21T10:00:00"), at("2026-11-21T15:00:00"))
        val coverage = wholeWeekend.copy(gaps = listOf(gap))
        val day = Stats.day(saturday, clock, emptyList(), coverage, countLockScreen = true)

        assertEquals(
            listOf(Span(at("2026-11-21T04:00:00"), gap.start), Span(gap.end, at("2026-11-22T04:00:00"))),
            day.known,
        )
        assertEquals(listOf(gap), day.missing)
        assertFalse(day.complete)
    }

    @Test
    fun `a period averages over its complete days only`() {
        val coverage = Coverage(at("2026-11-20T00:00:00"), at("2026-11-22T17:00:00"), emptyList())
        val sessions = listOf(
            session("2026-11-21T08:00:00", "2026-11-21T10:00:00"),
            session("2026-11-21T22:00:00", "2026-11-21T23:00:00"),
            session("2026-11-22T09:00:00", "2026-11-22T09:30:00"),
        )
        val weekend = Stats.period(Period(PeriodKind.WEEKEND, saturday), clock, sessions, coverage, countLockScreen = true)

        assertEquals(210 * MINUTE, weekend.total)
        assertEquals(2, weekend.recordedDays)
        // Sunday is still running, so only Saturday counts.
        assertEquals(180 * MINUTE, weekend.dailyAverage)
        // First on: Saturday 08:00 and Sunday 09:00, measured from 04:00.
        assertEquals((4 * 60 + 30) * MINUTE, weekend.averageFirstOn)
        // Last on: only Saturday's is final.
        assertEquals(19 * 60 * MINUTE, weekend.averageLastOn)
    }

    @Test
    fun `a period with no complete day has no averages`() {
        val coverage = Coverage(at("2026-11-22T10:00:00"), at("2026-11-22T17:00:00"), emptyList())
        val weekend = Stats.period(Period(PeriodKind.WEEKEND, saturday), clock, emptyList(), coverage, countLockScreen = true)
        assertNull(weekend.dailyAverage)
        assertNull(weekend.averageFirstOn)
        assertEquals(1, weekend.recordedDays)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
