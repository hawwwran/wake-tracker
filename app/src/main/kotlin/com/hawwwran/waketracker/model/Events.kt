package com.hawwwran.waketracker.model

/**
 * The entries of Android's usage log the tracker keeps, under the numbers
 * android.app.usage.UsageEvents.Event gives them. They are stored as they come, so these
 * numbers must never change.
 */
object EventType {
    const val SCREEN_ON = 15 // SCREEN_INTERACTIVE
    const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val SHUTDOWN = 26 // DEVICE_SHUTDOWN
    const val STARTUP = 27 // DEVICE_STARTUP

    // Never stored. History puts them into the stream it hands to SessionBuilder where the
    // record has a hole: nothing is known between a BREAK and the RESUME after it. Negative,
    // so that they sort in front of a real event of the same millisecond.
    const val RECORD_BREAK = -2
    const val RECORD_RESUME = -1

    fun isTracked(type: Int): Boolean =
        type == SCREEN_ON || type == SCREEN_OFF || type == KEYGUARD_SHOWN || type == KEYGUARD_HIDDEN ||
            type == SHUTDOWN || type == STARTUP

    fun isKeyguard(type: Int): Boolean = type == KEYGUARD_SHOWN || type == KEYGUARD_HIDDEN

    /** After one of these the screen is off, whatever it was before. */
    fun endsScreenOn(type: Int): Boolean = type == SCREEN_OFF || type == SHUTDOWN || type == STARTUP
}

data class ScreenEvent(val ts: Long, val type: Int)

/** A stretch of time: [start, end) in epoch milliseconds. */
data class Span(val start: Long, val end: Long) {
    val duration: Long get() = end - start

    fun clip(from: Long, until: Long): Span? {
        val clipped = Span(maxOf(start, from), minOf(end, until))
        return clipped.takeIf { it.end > it.start }
    }
}

/**
 * What the record covers: from its first moment to the last read of the system's log, minus
 * the stretches that log had already dropped when the app came to read them.
 */
data class Coverage(val from: Long, val until: Long, val gaps: List<Span>) {

    /** The parts of [start, end) the record covers, oldest first. */
    fun within(start: Long, end: Long): List<Span> {
        var known = listOfNotNull(Span(from, until).clip(start, end))
        for (gap in gaps) {
            known = known.flatMap { span ->
                listOfNotNull(
                    Span(span.start, minOf(span.end, gap.start)).takeIf { it.end > it.start },
                    Span(maxOf(span.start, gap.end), span.end).takeIf { it.end > it.start },
                )
            }
        }
        return known
    }
}
