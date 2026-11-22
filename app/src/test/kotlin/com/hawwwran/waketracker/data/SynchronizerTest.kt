package com.hawwwran.waketracker.data

import com.hawwwran.waketracker.data.FakeUsageLog.Companion.OTHER
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType.SCREEN_OFF
import com.hawwwran.waketracker.model.EventType.SCREEN_ON
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SynchronizerTest {

    private val log = FakeUsageLog()
    private val store = MemoryEventStore()
    private var now = 100 * DAY
    private val sync = Synchronizer(log, store) { now }

    @Test
    fun `the first read takes what the log still has, from before the app existed`() {
        log.add(now - 9 * DAY, OTHER)
        log.add(now - 8 * DAY, SCREEN_ON)
        log.add(now - 8 * DAY + HOUR, SCREEN_OFF)
        log.add(now - MINUTE, SCREEN_ON)

        assertEquals(Synchronizer.Result.Read(added = 3, gap = null), sync.sync())
        assertEquals(Coverage(now - 9 * DAY, now, emptyList()), store.coverage())
        assertEquals(3, store.eventCount())
    }

    @Test
    fun `only screen, keyguard and power events are kept`() {
        log.add(now - HOUR, OTHER)
        log.add(now - HOUR + 1, SCREEN_ON)
        log.add(now - HOUR + 2, 7)
        sync.sync()
        assertEquals(listOf(ScreenEvent(now - HOUR + 1, SCREEN_ON)), store.events(0, now))
    }

    @Test
    fun `reading again adds only what is new`() {
        log.add(now - HOUR, SCREEN_ON)
        log.add(now - 5 * MINUTE, SCREEN_OFF)
        sync.sync()

        now += 10 * MINUTE
        log.add(now - MINUTE, SCREEN_ON)
        assertEquals(Synchronizer.Result.Read(added = 1, gap = null), sync.sync())
        assertEquals(3, store.eventCount())
        assertEquals(now, store.coverage()!!.until)
    }

    @Test
    fun `an event the log took in late is picked up by the next read`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()
        val firstRead = now

        // Happened just before the first read, but the log had not stored it yet.
        log.add(firstRead - 20, SCREEN_OFF)
        now += 2 * HOUR
        sync.sync()
        assertEquals(listOf(SCREEN_ON, SCREEN_OFF), store.events(0, now).map { it.type })
    }

    @Test
    fun `without access nothing is read and nothing moves`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()
        val reached = store.coverage()!!.until

        log.readable = false
        now += 5 * HOUR
        assertEquals(Synchronizer.Result.NoAccess, sync.sync())
        assertEquals(reached, store.coverage()!!.until)
    }

    @Test
    fun `access taken away during the read does not move the record`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()
        val reached = store.coverage()!!.until

        now += 5 * HOUR
        log.add(now - HOUR, SCREEN_OFF)
        log.afterRead = { log.readable = false }
        assertEquals(Synchronizer.Result.NoAccess, sync.sync())
        assertEquals(reached, store.coverage()!!.until)
        assertEquals(1, store.eventCount())
    }

    @Test
    fun `a read that returns nothing at all does not move the record`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()
        val reached = store.coverage()!!.until

        log.entries.clear()
        now += 5 * HOUR
        assertEquals(Synchronizer.Result.Empty, sync.sync())
        assertEquals(reached, store.coverage()!!.until)
        assertEquals(now, store.lastReadAt())
    }

    @Test
    fun `nothing found on the very first read leaves the record unstarted`() {
        assertEquals(Synchronizer.Result.Empty, sync.sync())
        assertNull(store.coverage())
    }

    @Test
    fun `a week without a read loses nothing while the log still reaches back`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()
        val reached = now

        now += 7 * DAY
        log.add(reached + DAY, SCREEN_OFF)
        log.add(now - HOUR, SCREEN_ON)
        log.keepsFrom = reached - 2 * HOUR

        assertEquals(Synchronizer.Result.Read(added = 2, gap = null), sync.sync())
        assertEquals(emptyList<Span>(), store.coverage()!!.gaps)
    }

    @Test
    fun `what the log dropped before it was read is stored as a gap`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()
        val reached = now

        // Two weeks later the log keeps only its last ten days.
        now += 14 * DAY
        log.add(reached + 2 * DAY, SCREEN_OFF)
        log.add(now - 10 * DAY + HOUR, OTHER)
        log.add(now - 9 * DAY, SCREEN_ON)
        log.keepsFrom = now - 10 * DAY

        val gap = Span(reached, now - 10 * DAY + HOUR)
        assertEquals(Synchronizer.Result.Read(added = 1, gap = gap), sync.sync())
        assertEquals(listOf(gap), store.coverage()!!.gaps)
        assertEquals(now, store.coverage()!!.until)
    }

    @Test
    fun `the clock set back does not break the next read`() {
        log.add(now - HOUR, SCREEN_ON)
        sync.sync()

        now -= 3 * HOUR
        log.add(now - MINUTE, SCREEN_OFF)
        assertEquals(Synchronizer.Result.Read(added = 1, gap = null), sync.sync())
        assertEquals(now, store.coverage()!!.until)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
