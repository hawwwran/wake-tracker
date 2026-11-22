package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span

/** The store's contract in memory, for testing what is built on it. */
class MemoryEventStore : EventStore, Outbox, Replica {
    private val stored = sortedSetOf(compareBy<ScreenEvent>({ it.ts }, { it.type }))
    private val gaps = ArrayList<Span>()
    private var recordStart: Long? = null
    private var reachesUntil: Long? = null
    private var lastReadAt: Long? = null
    private val pending = sortedMapOf<Long, Long>()
    private var stamp = 0L
    private var cursor = 0L

    override fun coverage(): Coverage? {
        val from = recordStart ?: return null
        val until = reachesUntil ?: return null
        return Coverage(from, until, gaps.sortedBy { it.start })
    }

    override fun events(from: Long, until: Long) = stored.filter { it.ts >= from && it.ts < until }

    override fun lastScreenOffBefore(ts: Long) = stored.lastOrNull { it.ts < ts && EventType.endsScreenOn(it.type) }

    override fun lastKeyguardBefore(ts: Long) = stored.lastOrNull { it.ts < ts && EventType.isKeyguard(it.type) }

    override fun eventCount() = stored.size.toLong()

    override fun lastReadAt() = lastReadAt

    override fun commit(outcome: ReadOutcome): Int {
        val new = outcome.events.filter { stored.add(it) }
        val added = new.size
        if (new.isNotEmpty()) {
            stamp++
            new.forEach { pending[Math.floorDiv(it.ts, DAY_MS)] = stamp }
        }
        outcome.gap?.let { gaps += it }
        if (recordStart == null) recordStart = outcome.recordStart
        outcome.reachesUntil?.let { reachesUntil = it }
        lastReadAt = outcome.readAt
        return added
    }

    override fun pendingDays() = pending.map { PendingDay(it.key, it.value) }

    override fun clearPending(day: Long, stamp: Long) {
        if (pending[day] == stamp) pending.remove(day)
    }

    override fun markAllPending() {
        stored.forEach { pending[Math.floorDiv(it.ts, DAY_MS)] = 0 }
    }

    override fun replaceDay(day: Long, events: List<ScreenEvent>) {
        stored.removeIf { it.ts >= day * DAY_MS && it.ts < (day + 1) * DAY_MS }
        stored.addAll(events)
    }

    override fun cursor() = cursor

    override fun advanceCursor(version: Long) {
        cursor = version
    }

    override fun setCoverage(coverage: Coverage) {
        recordStart = coverage.from
        reachesUntil = coverage.until
        gaps.clear()
        gaps.addAll(coverage.gaps)
    }
}

/** A usage log that holds what the test puts in and forgets what is older than [keepsFrom]. */
class FakeUsageLog : UsageLog {
    val entries = ArrayList<Pair<Long, Int>>()
    var readable = true
    var keepsFrom = Long.MIN_VALUE

    /** Called once after each read, as a hook for "access was revoked meanwhile". */
    var afterRead: () -> Unit = {}

    fun add(ts: Long, type: Int) {
        entries += ts to type
    }

    override fun readable() = readable

    override fun read(from: Long, until: Long, onEvent: (ts: Long, type: Int) -> Unit) {
        entries.sortedBy { it.first }
            .filter { it.first >= maxOf(from, keepsFrom) && it.first < until }
            .forEach { onEvent(it.first, it.second) }
        afterRead()
    }

    companion object {
        /** Some entry the tracker does not keep: an app coming to the front. */
        const val OTHER = 1
    }
}
