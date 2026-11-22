package com.hawwwran.waketracker.model

/**
 * One stretch with the screen on. [unlocked] says whether the phone was unlocked at any point
 * of it; false is a wake that never left the lock screen (a notification lighting it up, a
 * glance at the clock).
 */
data class Session(val start: Long, val end: Long, val unlocked: Boolean) {
    val duration: Long get() = end - start

    fun clip(from: Long, until: Long): Session? {
        val clipped = copy(start = maxOf(start, from), end = minOf(end, until))
        return clipped.takeIf { it.end > it.start }
    }
}

/**
 * Turns stored events into sessions. Pure, and tolerant of what a real log does: the same
 * event twice (a re-read after the system shifted its clock), an "on" with no "off" before a
 * restart, holes in the record.
 */
object SessionBuilder {

    /**
     * @param events oldest first; the screen is off before the first one unless it is a
     *   RECORD_RESUME, which makes the state unknown.
     * @param lockedBefore whether the lock screen was up before the first event; null when
     *   that is not known, and the first keyguard event then tells.
     * @param openEnd where a session that is still open after the last event is cut: the
     *   moment the record reaches to.
     */
    fun build(events: List<ScreenEvent>, lockedBefore: Boolean?, openEnd: Long): List<Session> {
        val run = Run(events, lockedBefore)
        var index = 0
        while (index < events.size) {
            var last = index
            while (last + 1 < events.size && events[last + 1].ts == events[index].ts) last++
            if (last == index) {
                run.apply(index)
            } else {
                run.applyTied(index, last)
            }
            index = last + 1
        }
        run.close(openEnd)
        return run.sessions
    }

    private class Run(private val events: List<ScreenEvent>, lockedBefore: Boolean?) {
        val sessions = ArrayList<Session>()

        // A phone without a lock screen logs no keyguard events at all; every session of it
        // counts as unlocked.
        private val hasKeyguard = lockedBefore != null || events.any { EventType.isKeyguard(it.type) }
        private var locked = lockedBefore ?: lockedAsToldFrom(0)
        private var onSince: Long? = null
        private var unlocked = false

        // Set while the state is unknown (the record starts or resumes here): if the screen
        // then goes off before it goes on, it was on since this moment.
        private var unknownSince: Long? = null
        private var lastSeen = Long.MIN_VALUE

        fun apply(index: Int) {
            val event = events[index]
            when (event.type) {
                EventType.SCREEN_ON -> {
                    unknownSince = null
                    if (onSince == null) {
                        onSince = event.ts
                        unlocked = !locked
                    }
                }

                EventType.SCREEN_OFF, EventType.SHUTDOWN -> {
                    unknownSince?.let {
                        onSince = it
                        unlocked = !locked
                    }
                    unknownSince = null
                    close(event.ts)
                }

                EventType.STARTUP -> {
                    unknownSince = null
                    // Android writes a shutdown in front of every startup, so a session is
                    // closed by now. If one is not, its end is not known; the last stored
                    // event is the last moment the phone is known to have been running.
                    close(lastSeen)
                    if (hasKeyguard) locked = true
                }

                EventType.KEYGUARD_HIDDEN -> {
                    locked = false
                    if (onSince != null) unlocked = true
                }

                EventType.KEYGUARD_SHOWN -> locked = true

                EventType.RECORD_BREAK -> close(event.ts)

                EventType.RECORD_RESUME -> {
                    close(event.ts)
                    unknownSince = event.ts
                    locked = lockedAsToldFrom(index + 1)
                }
            }
            lastSeen = event.ts
        }

        /**
         * Events of one millisecond come sorted by type number, which is not the order they
         * happened in. An "off" and an "on" sharing a millisecond are a flip, so the one that
         * changes the present state goes first.
         */
        fun applyTied(first: Int, last: Int) {
            val wasOn = onSince != null
            (first..last).sortedBy { rank(events[it].type, wasOn) }.forEach(::apply)
        }

        private fun rank(type: Int, wasOn: Boolean): Int = when (type) {
            EventType.RECORD_BREAK -> -2
            EventType.RECORD_RESUME -> -1
            EventType.SCREEN_ON -> if (wasOn) 3 else 1
            EventType.SCREEN_OFF, EventType.SHUTDOWN -> if (wasOn) 0 else 3
            EventType.STARTUP -> if (wasOn) 1 else 0
            else -> 2
        }

        fun close(end: Long) {
            val start = onSince ?: return
            if (end > start) sessions += Session(start, end, unlocked)
            onSince = null
        }

        /** The keyguard state before [from], read off the first keyguard event at or after it: it reports a change, so the state was the opposite. */
        private fun lockedAsToldFrom(from: Int): Boolean {
            for (i in from until events.size) {
                when (events[i].type) {
                    EventType.KEYGUARD_HIDDEN -> return true
                    EventType.KEYGUARD_SHOWN -> return false
                    EventType.RECORD_RESUME -> return false
                }
            }
            return false
        }
    }
}
