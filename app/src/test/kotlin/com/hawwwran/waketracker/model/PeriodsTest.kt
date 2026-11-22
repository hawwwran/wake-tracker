package com.hawwwran.waketracker.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PeriodsTest {

    private val prague = ZoneId.of("Europe/Prague")
    private val clock = DayClock(prague, startHour = 4)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(prague).toInstant().toEpochMilli()

    // 2026-11-22 is a Sunday.
    private val sunday = LocalDate.of(2026, 11, 22)
    private val wednesday = LocalDate.of(2026, 11, 18)

    @Test
    fun `the small hours belong to the day before`() {
        assertEquals(LocalDate.of(2026, 11, 21), clock.dayOf(at("2026-11-22T00:36:00")))
        assertEquals(LocalDate.of(2026, 11, 21), clock.dayOf(at("2026-11-22T03:59:59")))
        assertEquals(sunday, clock.dayOf(at("2026-11-22T04:00:00")))
        assertEquals(sunday, clock.dayOf(at("2026-11-22T23:59:59")))
    }

    @Test
    fun `a day starting at midnight is the calendar day`() {
        val midnight = DayClock(prague, startHour = 0)
        assertEquals(sunday, midnight.dayOf(at("2026-11-22T00:00:00")))
        assertEquals(LocalDate.of(2026, 11, 21), midnight.dayOf(at("2026-11-21T23:59:59")))
    }

    @Test
    fun `every moment lies inside the bounds of its day, also around a clock change`() {
        // Prague: clocks go forward on 2026-03-29 at 02:00 and back on 2026-10-25 at 03:00.
        for (startHour in 0..6) {
            val dayClock = DayClock(prague, startHour)
            for (start in listOf(at("2026-03-28T12:00:00"), at("2026-10-24T12:00:00"))) {
                var ts = start
                while (ts < start + 48 * HOUR) {
                    val day = dayClock.dayOf(ts)
                    assert(ts >= dayClock.startOf(day) && ts < dayClock.endOf(day)) { "hour $startHour, moment $ts, day $day" }
                    ts += 7 * MINUTE
                }
            }
        }
    }

    @Test
    fun `the day of a clock change is 23 or 25 hours long`() {
        assertEquals(23 * HOUR, clock.endOf(LocalDate.of(2026, 3, 28)) - clock.startOf(LocalDate.of(2026, 3, 28)))
        assertEquals(25 * HOUR, clock.endOf(LocalDate.of(2026, 10, 24)) - clock.startOf(LocalDate.of(2026, 10, 24)))
    }

    @Test
    fun `periods of a week`() {
        assertEquals(LocalDate.of(2026, 11, 16), Period.containing(PeriodKind.WEEK, wednesday).firstDay)
        assertEquals(LocalDate.of(2026, 11, 22), Period.containing(PeriodKind.WEEK, wednesday).lastDay)
        assertEquals(LocalDate.of(2026, 11, 16), Period.containing(PeriodKind.WEEK, sunday).firstDay)

        val workweek = Period.containing(PeriodKind.WORKWEEK, sunday)
        assertEquals(LocalDate.of(2026, 11, 16), workweek.firstDay)
        assertEquals(LocalDate.of(2026, 11, 20), workweek.lastDay)

        val weekend = Period.containing(PeriodKind.WEEKEND, wednesday)
        assertEquals(listOf(LocalDate.of(2026, 11, 21), sunday), weekend.days)
    }

    @Test
    fun `the latest period is one that has begun`() {
        assertEquals(LocalDate.of(2026, 11, 14), Period.latest(PeriodKind.WEEKEND, wednesday).firstDay)
        assertEquals(LocalDate.of(2026, 11, 21), Period.latest(PeriodKind.WEEKEND, sunday).firstDay)
        assertEquals(LocalDate.of(2026, 11, 16), Period.latest(PeriodKind.WORKWEEK, wednesday).firstDay)
        assertEquals(wednesday, Period.latest(PeriodKind.DAY, wednesday).firstDay)
    }

    @Test
    fun `stepping and counting steps agree`() {
        for (kind in PeriodKind.entries) {
            val start = Period.containing(kind, wednesday)
            assertEquals(5, (start + 5).stepsFrom(start))
            assertEquals(-3, (start + -3).stepsFrom(start))
            assertEquals(start, start + 4 + -4)
        }
        assertEquals(LocalDate.of(2026, 11, 28), (Period.containing(PeriodKind.WEEKEND, wednesday) + 1).firstDay)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
