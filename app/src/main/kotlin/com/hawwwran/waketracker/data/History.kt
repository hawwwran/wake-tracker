package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Session
import com.hawwwran.waketracker.model.SessionBuilder

/** Reads sessions out of the stored events. */
class History(private val store: EventStore) {

    /** The sessions that touch [from, until), uncut, oldest first. */
    fun sessions(from: Long, until: Long, coverage: Coverage): List<Session> {
        val end = minOf(until, coverage.until)
        if (end <= coverage.from || from >= end) return emptyList()

        // Building starts at the newest moment before the range where nothing earlier matters:
        // an event that leaves the screen off, or else the point the record starts or resumes
        // at, where the state is unknown.
        val resume = (coverage.gaps.map { it.end } + coverage.from).filter { it <= from }.maxOrNull() ?: coverage.from
        val off = store.lastScreenOffBefore(from)?.takeIf { it.ts >= resume }
        val anchor = off?.ts ?: resume

        val marks = coverage.gaps.filter { it.start >= anchor && it.start < end }.flatMap {
            listOf(ScreenEvent(it.start, EventType.RECORD_BREAK), ScreenEvent(it.end, EventType.RECORD_RESUME))
        }
        val stream = buildList {
            if (off == null) add(ScreenEvent(resume, EventType.RECORD_RESUME))
            addAll((store.events(anchor, end) + marks).sortedWith(compareBy({ it.ts }, { it.type })))
        }
        val lockedBefore = if (off == null) {
            null
        } else {
            store.lastKeyguardBefore(anchor)?.takeIf { it.ts >= resume }?.let { it.type == EventType.KEYGUARD_SHOWN }
        }
        return SessionBuilder.build(stream, lockedBefore, openEnd = end).filter { it.end > from }
    }
}
