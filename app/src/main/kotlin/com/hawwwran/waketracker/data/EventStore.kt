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

/** A day of the record that has events the relay has not been given yet. [stamp] changes every time the day gains events. */
data class PendingDay(val day: Long, val stamp: Long)

/**
 * The days still to be uploaded to a relay (share/Uploader.kt). Days are numbered in UTC,
 * the count of days since 1970, which is the same on every phone whatever its time zone.
 * The store notes a day here in the very transaction that adds events to it, so a day
 * cannot gain events without being due for upload.
 */
interface Outbox {
    fun pendingDays(): List<PendingDay>

    /** Done with [day] as it stood at [stamp]. If it gained events since, it stays pending. */
    fun clearPending(day: Long, stamp: Long)

    /** Everything again: a new relay, or a new identity on it, starts with nothing. */
    fun markAllPending()
}

/** A copy of another phone's record, written as it arrives from the relay (share/Puller.kt). */
interface Replica {
    /** Sets [day]'s events to exactly [events]. */
    fun replaceDay(day: Long, events: List<ScreenEvent>)

    fun setCoverage(coverage: Coverage)

    /**
     * The relay's version of that phone's files up to which this copy has taken them over.
     * Kept with the copy itself: a copy that is lost starts again from 0 and is fetched whole.
     */
    fun cursor(): Long

    fun advanceCursor(version: Long)
}

const val DAY_MS = 24 * 60 * 60 * 1000L

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
