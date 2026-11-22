package com.hawwwran.waketracker.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Maps moments to the tracker's days. A day runs from [startHour] to the same hour of the
 * next date, so an evening that ends after midnight stays in one day and "last on" is the
 * time the phone was put down for the night.
 */
class DayClock(val zone: ZoneId, val startHour: Int) {

    fun startOf(day: LocalDate): Long = day.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()

    fun endOf(day: LocalDate): Long = startOf(day.plusDays(1))

    fun dayOf(ts: Long): LocalDate {
        val guess = Instant.ofEpochMilli(ts).atZone(zone).toLocalDateTime().minusHours(startHour.toLong()).toLocalDate()
        // Local arithmetic can be an hour off around a clock change; the bounds decide.
        return when {
            ts < startOf(guess) -> guess.minusDays(1)
            ts >= endOf(guess) -> guess.plusDays(1)
            else -> guess
        }
    }
}

enum class PeriodKind(val dayCount: Int) {
    DAY(1),
    WEEK(7),
    WORKWEEK(5),
    WEEKEND(2),
}

/** A day, or the Monday-to-Sunday week, its Monday-to-Friday or its Saturday and Sunday. */
data class Period(val kind: PeriodKind, val firstDay: LocalDate) {

    val days: List<LocalDate> get() = List(kind.dayCount) { firstDay.plusDays(it.toLong()) }

    val lastDay: LocalDate get() = firstDay.plusDays(kind.dayCount - 1L)

    operator fun plus(steps: Int): Period =
        copy(firstDay = if (kind == PeriodKind.DAY) firstDay.plusDays(steps.toLong()) else firstDay.plusWeeks(steps.toLong()))

    /** How many steps after [other] this period is; both of the same kind. */
    fun stepsFrom(other: Period): Int {
        val unit = if (kind == PeriodKind.DAY) ChronoUnit.DAYS else ChronoUnit.WEEKS
        return unit.between(other.firstDay, firstDay).toInt()
    }

    companion object {
        fun containing(kind: PeriodKind, day: LocalDate): Period {
            val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return when (kind) {
                PeriodKind.DAY -> Period(kind, day)
                PeriodKind.WEEK, PeriodKind.WORKWEEK -> Period(kind, monday)
                PeriodKind.WEEKEND -> Period(kind, monday.plusDays(5))
            }
        }

        /** The newest period of [kind] that has begun by [today]. On a Wednesday that is last weekend. */
        fun latest(kind: PeriodKind, today: LocalDate): Period {
            val current = containing(kind, today)
            return if (current.firstDay > today) current + -1 else current
        }
    }
}
