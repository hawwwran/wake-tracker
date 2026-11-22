package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.model.EventType
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span

/** Android's usage log, as far as the tracker needs it. */
interface UsageLog {
    /** Whether the app is allowed to read the log right now. */
    fun readable(): Boolean

    /** Every entry of [from, until), of any type, oldest first. */
    fun read(from: Long, until: Long, onEvent: (ts: Long, type: Int) -> Unit)
}

/**
 * Copies screen events from Android's usage log into the app's store.
 *
 * Android records the events itself and keeps them for about ten days, whether this app runs
 * or not. So nothing is lost while the app is killed, restricted or not opened: the next read
 * picks up where the last one stopped. A read is safe to repeat and to interrupt:
 *
 * - it starts a little before the point the record reaches to, and the store drops what it
 *   already has;
 * - events and the new reach are stored in one transaction;
 * - a read that returns nothing at all moves nothing, because "no events" and "the log
 *   refused to answer" look the same;
 * - when the last read is so old that the log may have dropped events since, the stretch
 *   that cannot be recovered is stored as a gap instead of being passed off as screen-off
 *   time.
 */
class Synchronizer(
    private val log: UsageLog,
    private val store: EventStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data object NoAccess : Result

        /** The log answered with nothing; the record is as it was. */
        data object Empty : Result

        data class Read(val added: Int, val gap: Span?) : Result
    }

    @Synchronized
    fun sync(): Result {
        if (!log.readable()) return Result.NoAccess

        val readAt = now()
        val reached = store.coverage()?.until
        val from = if (reached == null) readAt - FIRST_READ_REACH else minOf(reached, readAt) - OVERLAP

        // The log deletes its oldest days. If it still has an entry from before the point the
        // record reaches to, nothing after that point is missing.
        var logLostTheStart = false
        if (reached != null && readAt - reached > CONTINUITY_CHECK_AFTER) {
            var older = false
            log.read(reached - CONTINUITY_PROBE, reached) { _, _ -> older = true }
            logLostTheStart = !older
        }

        val events = ArrayList<ScreenEvent>()
        var sawAny = false
        var firstNew: Long? = null
        log.read(from, readAt) { ts, type ->
            sawAny = true
            if (firstNew == null && (reached == null || ts >= reached)) firstNew = ts
            if (EventType.isTracked(type)) events += ScreenEvent(ts, type)
        }

        // Access can be taken away between the check and the read, and the read then comes
        // back empty without an error.
        if (!log.readable()) return Result.NoAccess
        if (!sawAny) {
            store.commit(ReadOutcome(emptyList(), readAt, reachesUntil = null))
            return Result.Empty
        }

        val gap = if (logLostTheStart) Span(reached!!, firstNew ?: readAt).takeIf { it.end > it.start } else null
        val added = store.commit(
            ReadOutcome(
                events = events,
                readAt = readAt,
                reachesUntil = readAt,
                recordStart = if (reached == null) firstNew else null,
                gap = gap,
            ),
        )
        return Result.Read(added, gap)
    }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR

        /** Further back than the log keeps anything, so the first read takes all there is. */
        const val FIRST_READ_REACH = 15 * DAY

        /**
         * The log takes an event in a moment after it happened, so a read can miss one from
         * its last instant. Re-reading this much catches it.
         */
        const val OVERLAP = 15 * 60 * 1000L

        /** Below this the log cannot have dropped anything; it keeps days, not hours. */
        const val CONTINUITY_CHECK_AFTER = DAY
        const val CONTINUITY_PROBE = DAY
    }
}
