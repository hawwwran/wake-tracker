package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span

/** The store's contract in memory, for testing what is built on it. */
class MemoryEventStore : EventStore {
    private val stored = sortedSetOf(compareBy<ScreenEvent>({ it.ts }, { it.type }))
    private val gaps = ArrayList<Span>()
    private var recordStart: Long? = null
    private var reachesUntil: Long? = null
    private var lastReadAt: Long? = null

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
        val added = outcome.events.count { stored.add(it) }
        outcome.gap?.let { gaps += it }
        if (recordStart == null) recordStart = outcome.recordStart
        outcome.reachesUntil?.let { reachesUntil = it }
        lastReadAt = outcome.readAt
        return added
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
