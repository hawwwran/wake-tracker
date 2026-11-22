package com.hawwwran.waketracker.model

import java.time.LocalDate

/** One of the tracker's days, as far as the record covers it. */
data class DayStats(
    val day: LocalDate,
    val bounds: Span,
    /** The parts of the day the record covers. Empty for a day before the record or in the future. */
    val known: List<Span>,
    /** The parts of the day that have passed and that the record does not cover. */
    val missing: List<Span>,
    /** Screen-on stretches, cut to the day. */
    val sessions: List<Session>,
    /** Sessions that began within the day; one running over from the day before counts there. */
    val timesOn: Int,
    /** The last session is still running at the end of the record. */
    val ongoing: Boolean,
) {
    val total: Long = sessions.sumOf { it.duration }
    val firstOn: Long? get() = sessions.firstOrNull()?.start
    val lastOn: Long? get() = sessions.lastOrNull()?.end

    val hasData: Boolean get() = known.isNotEmpty()

    /** Covered from its first to its last moment, so its total is the day's total. */
    val complete: Boolean get() = known.size == 1 && known[0] == bounds

    // A time is only the day's first or last if the record has no hole on that side of it.
    val firstOnCertain: Boolean
        get() = firstOn != null && known.isNotEmpty() && known.first().start == bounds.start && firstOn!! < known.first().end
    val lastOnCertain: Boolean
        get() = lastOn != null && known.isNotEmpty() && known.last().end == bounds.end && lastOn!! > known.last().start
}

data class PeriodStats(val period: Period, val days: List<DayStats>) {
    val total: Long = days.sumOf { it.total }
    val timesOn: Int = days.sumOf { it.timesOn }
    val recordedDays: Int = days.count { it.hasData }
    val hasData: Boolean get() = recordedDays > 0

    private val completeDays = days.filter { it.complete }

    /** Over the days recorded in full; a day still running or with a hole would drag it down. */
    val dailyAverage: Long? = completeDays.takeIf { it.isNotEmpty() }?.let { full -> full.sumOf { it.total } / full.size }

    val dailyTimesOn: Int? = completeDays.takeIf { it.isNotEmpty() }?.let { full -> full.sumOf { it.timesOn } / full.size }

    /** Milliseconds after the start of the day, averaged over the days where the time is certain. */
    val averageFirstOn: Long? = days.filter { it.firstOnCertain }.map { it.firstOn!! - it.bounds.start }.averageOrNull()
    val averageLastOn: Long? = days.filter { it.lastOnCertain }.map { it.lastOn!! - it.bounds.start }.averageOrNull()

    private fun List<Long>.averageOrNull(): Long? = if (isEmpty()) null else sum() / size
}

object Stats {

    /** @param sessions uncut, oldest first; at least those that touch the day. */
    fun day(day: LocalDate, clock: DayClock, sessions: List<Session>, coverage: Coverage?, countLockScreen: Boolean): DayStats {
        val bounds = Span(clock.startOf(day), clock.endOf(day))
        val counted = if (countLockScreen) sessions else sessions.filter { it.unlocked }
        val inDay = counted.mapNotNull { it.clip(bounds.start, bounds.end) }
        val known = coverage?.within(bounds.start, bounds.end).orEmpty()
        return DayStats(
            day = day,
            bounds = bounds,
            known = known,
            missing = if (coverage == null) emptyList() else holes(known, bounds.start, minOf(bounds.end, coverage.until)),
            sessions = inDay,
            timesOn = counted.count { it.start >= bounds.start && it.start < bounds.end },
            ongoing = coverage != null && coverage.until < bounds.end && inDay.lastOrNull()?.end == coverage.until,
        )
    }

    /** What [known], oldest first, leaves uncovered of [from, until). */
    private fun holes(known: List<Span>, from: Long, until: Long): List<Span> {
        val holes = ArrayList<Span>()
        var covered = from
        for (span in known) {
            if (span.start > covered) holes += Span(covered, minOf(span.start, until))
            covered = maxOf(covered, span.end)
        }
        if (until > covered) holes += Span(covered, until)
        return holes.filter { it.end > it.start }
    }

    fun period(period: Period, clock: DayClock, sessions: List<Session>, coverage: Coverage?, countLockScreen: Boolean): PeriodStats =
        PeriodStats(period, period.days.map { day(it, clock, sessions, coverage, countLockScreen) })
}
