package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span

/** What one read of the system's log brought. */
data class ReadOutcome(
    val events: List<ScreenEvent>,
    val readAt: Long,
    /** The record is complete up to here. Stays where it was when the read came back empty. */
    val reachesUntil: Long?,
    /** The first moment the record covers; set by the first read that found anything. */
    val recordStart: Long? = null,
    /** A stretch the system's log had already dropped. */
    val gap: Span? = null,
)

/** The app's own copy of the screen events. Rows are only ever added. */
interface EventStore {

    /** Null until a read of the system's log found something. */
    fun coverage(): Coverage?

    /** Events of [from, until), by time and then by type number. */
    fun events(from: Long, until: Long): List<ScreenEvent>

    /** The newest event before [ts] that leaves the screen off. */
    fun lastScreenOffBefore(ts: Long): ScreenEvent?

    fun lastKeyguardBefore(ts: Long): ScreenEvent?

    fun eventCount(): Long

    fun lastReadAt(): Long?

    /** Stores all of [outcome] or none of it. Returns how many events were new. */
    fun commit(outcome: ReadOutcome): Int
}
