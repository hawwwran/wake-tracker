package com.hawwwran.waketracker.share

import com.hawwwran.waketracker.data.DAY_MS
import com.hawwwran.waketracker.data.MemoryEventStore
import com.hawwwran.waketracker.data.ReadOutcome
import com.hawwwran.waketracker.model.EventType.KEYGUARD_HIDDEN
import com.hawwwran.waketracker.model.EventType.SCREEN_OFF
import com.hawwwran.waketracker.model.EventType.SCREEN_ON
import com.hawwwran.waketracker.model.ScreenEvent
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The app's own relay client against a real relay, which the other tests replace with a
 * fake. Skipped unless the relay's address is given:
 *
 *   WAKE_TRACKER_RELAY=https://relay.example ./gradlew :app:testDebugUnitTest --tests '*LiveRelayCheck*' --rerun-tasks
 *
 * One phone uploads a made-up record, a second one reads it and must end up with the same,
 * and the device is removed from the relay again.
 *
 * With WAKE_TRACKER_KEEP=1 the device stays and its pairing code is printed, for looking at
 * a paired phone's tab on a real phone without a second phone at hand.
 */
class LiveRelayCheck {

    @Test
    fun `a record goes up to the relay and comes down the same`() {
        val server = System.getenv("WAKE_TRACKER_RELAY")?.let(ServerAddress::normalize)
        assumeTrue("no relay given", server != null)
        server!!
        HttpRelay(server).hello()

        val now = System.currentTimeMillis()
        val today = Math.floorDiv(now, DAY_MS)
        val record = MemoryEventStore()
        val events = ArrayList<ScreenEvent>()
        // Five days of a plausible phone: up around seven, down around eleven, in between on and off.
        for (day in today - 4..today) {
            var at = day * DAY_MS + 6 * HOUR + (day % 5) * 7 * MINUTE
            var use = 0
            while (at < day * DAY_MS + 22 * HOUR && at < now - 10 * MINUTE) {
                val length = (2 + (use * 7 + day) % 40) * MINUTE
                events += ScreenEvent(at, SCREEN_ON)
                if (use % 4 != 3) events += ScreenEvent(at + 1_500, KEYGUARD_HIDDEN)
                events += ScreenEvent(minOf(at + length, now - 5 * MINUTE), SCREEN_OFF)
                at += length + (5 + (use * 13 + day) % 55) * MINUTE
                use++
            }
        }
        record.commit(ReadOutcome(events, now, reachesUntil = now, recordStart = (today - 4) * DAY_MS))

        val sharing = ShareSettings(MapKeyValues()).apply { this.server = server }
        val uploader = Uploader(record, record, sharing, ::HttpRelay, "check", { "Europe/Prague" })
        assertEquals(Uploader.Result.Uploaded(days = 5), uploader.upload())
        val identity = sharing.identity!!

        try {
            val copy = MemoryEventStore()
            val viewing = ShareSettings(MapKeyValues())
            viewing.paired = listOf(PairedDevice(identity.deviceId, "check", server, identity.secret))
            val fetched = Puller(viewing, ::HttpRelay, { copy }).pull(identity.deviceId)

            assertEquals(Puller.Result.Fetched(files = 6, unreadable = 0), fetched)
            assertEquals(record.events(0, Long.MAX_VALUE), copy.events(0, Long.MAX_VALUE))
            assertEquals(record.coverage(), copy.coverage())
            assertEquals(Uploader.Result.Uploaded(days = 0), uploader.upload())
        } finally {
            if (System.getenv("WAKE_TRACKER_KEEP") == "1") {
                println("PAIRING " + PairingCode(server, identity.deviceId, identity.secret, "Made-up phone").text())
                println("FORGET curl -X DELETE -H 'Authorization: Bearer ${identity.writeToken}' '$server/api.php?a=device&device=${identity.deviceId}'")
            } else {
                uploader.forgetOnRelay(server, identity)
            }
        }
    }

    /**
     * Reads what a phone shares, given its pairing code, and prints what came out: for
     * checking that a real phone's upload opens on another machine.
     *
     *   WAKE_TRACKER_PAIRING='waketracker:pair?...' ./gradlew :app:testDebugUnitTest --tests '*LiveRelayCheck*' --rerun-tasks -i
     */
    @Test
    fun `a pairing code opens that phone's record`() {
        val code = System.getenv("WAKE_TRACKER_PAIRING")?.let(PairingCode::parse)
        assumeTrue("no pairing code given", code != null)
        code!!

        val copy = MemoryEventStore()
        val viewing = ShareSettings(MapKeyValues())
        viewing.paired = listOf(PairedDevice(code.deviceId, "check", code.server, code.secret))
        val fetched = Puller(viewing, ::HttpRelay, { copy }).pull(code.deviceId)
        val events = copy.events(0, Long.MAX_VALUE)
        println("FETCHED $fetched")
        println("EVENTS ${events.size} first ${events.firstOrNull()?.ts} last ${events.lastOrNull()?.ts}")
        println("COVERAGE ${copy.coverage()}")
        println("SENT ${viewing.paired(code.deviceId)!!.sentAt}")
        assertEquals(0, (fetched as Puller.Result.Fetched).unreadable)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
