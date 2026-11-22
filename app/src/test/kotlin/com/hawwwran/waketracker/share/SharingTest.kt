package com.hawwwran.waketracker.share

import com.hawwwran.waketracker.data.DAY_MS
import com.hawwwran.waketracker.data.MemoryEventStore
import com.hawwwran.waketracker.data.ReadOutcome
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType.SCREEN_OFF
import com.hawwwran.waketracker.model.EventType.SCREEN_ON
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One phone uploading and another reading, with the relay between them. */
class SharingTest {

    private val relay = FakeRelay()
    private var now = 20_800 * DAY_MS + 15 * HOUR

    // The phone whose record is shared.
    private val record = MemoryEventStore()
    private val sharing = ShareSettings(MapKeyValues()).apply {
        server = SERVER
        deviceName = "vivo V2145"
    }
    private val uploader = Uploader(record, record, sharing, { relay }, "0.2.0", { "Europe/Prague" }) { now }

    // The phone that reads it.
    private val copy = MemoryEventStore()
    private val viewing = ShareSettings(MapKeyValues())
    private val puller = Puller(viewing, { relay }, { copy }) { now }

    private val today = 20_800L
    private fun at(day: Long, minutes: Long) = day * DAY_MS + minutes * MINUTE

    private fun recorded(vararg events: ScreenEvent, gap: Span? = null) {
        record.commit(ReadOutcome(events.toList(), now, reachesUntil = now, recordStart = at(today - 2, 0), gap = gap))
    }

    private fun pair() {
        val identity = sharing.identity!!
        val scanned = PairingCode.parse(PairingCode(SERVER, identity.deviceId, identity.secret, sharing.deviceName).text())!!
        viewing.paired = listOf(PairedDevice(scanned.deviceId, "Anna", scanned.server, scanned.secret))
    }

    private val deviceId get() = sharing.identity!!.deviceId

    @Before
    fun `three days are recorded`() {
        recorded(
            ScreenEvent(at(today - 2, 600), SCREEN_ON), ScreenEvent(at(today - 2, 640), SCREEN_OFF),
            ScreenEvent(at(today - 1, 500), SCREEN_ON), ScreenEvent(at(today - 1, 505), SCREEN_OFF),
            ScreenEvent(at(today, 420), SCREEN_ON), ScreenEvent(at(today, 480), SCREEN_OFF),
        )
    }

    @Test
    fun `the paired phone ends up with the same record`() {
        assertEquals(Uploader.Result.Uploaded(days = 3), uploader.upload())
        pair()

        assertEquals(Puller.Result.Fetched(files = 4, unreadable = 0), puller.pull(deviceId))
        assertEquals(record.events(0, Long.MAX_VALUE), copy.events(0, Long.MAX_VALUE))
        assertEquals(record.coverage(), copy.coverage())
        assertEquals("Anna", viewing.paired(deviceId)!!.name)
    }

    @Test
    fun `the paired phone knows when the other one last sent and when it last fetched`() {
        uploader.upload()
        val sent = now
        pair()
        now += 2 * HOUR
        puller.pull(deviceId)
        assertEquals(sent, viewing.paired(deviceId)!!.sentAt)
        assertEquals(now, viewing.paired(deviceId)!!.fetchedAt)

        // Nothing new on the relay: the fetch is newer, what was sent is not.
        now += HOUR
        puller.pull(deviceId)
        assertEquals(sent, viewing.paired(deviceId)!!.sentAt)
        assertEquals(now, viewing.paired(deviceId)!!.fetchedAt)
    }

    @Test
    fun `the relay is given nothing it could read, in sizes that say little`() {
        uploader.upload()
        val files = relay.devices.getValue(deviceId).blobs
        assertEquals(setOf("d${today - 2}", "d${today - 1}", "d$today", "meta"), files.keys)
        // Format byte, nonce and tag around whole blocks: a day with two events is as large as one with two hundred.
        assertEquals(setOf(1 + 12 + 4096 + 16), files.filterKeys { it != "meta" }.values.map { it.second.size }.toSet())
        assertEquals(1 + 12 + 1024 + 16, files.getValue("meta").second.size)
        val everything = files.values.joinToString("") { it.second.decodeToString() }
        assertTrue("Prague" !in everything && "\"from\"" !in everything)
    }

    @Test
    fun `only a day that changed is sent and fetched again`() {
        uploader.upload()
        pair()
        puller.pull(deviceId)
        val sentBefore = relay.puts

        now += HOUR
        recorded(ScreenEvent(at(today, 900), SCREEN_ON), ScreenEvent(at(today, 930), SCREEN_OFF))
        assertEquals(Uploader.Result.Uploaded(days = 1), uploader.upload())
        assertEquals(sentBefore + 2, relay.puts)

        assertEquals(Puller.Result.Fetched(files = 2, unreadable = 0), puller.pull(deviceId))
        assertEquals(record.events(0, Long.MAX_VALUE), copy.events(0, Long.MAX_VALUE))
        assertEquals(now, copy.coverage()!!.until)
    }

    @Test
    fun `with nothing new there is nothing to send or fetch`() {
        uploader.upload()
        pair()
        puller.pull(deviceId)
        val sentBefore = relay.puts

        now += MINUTE
        assertEquals(Uploader.Result.Uploaded(days = 0), uploader.upload())
        assertEquals(sentBefore, relay.puts)
        assertEquals(Puller.Result.Fetched(files = 0, unreadable = 0), puller.pull(deviceId))
    }

    @Test
    fun `how far the record reaches is sent on, but not every minute`() {
        uploader.upload()
        val sentBefore = relay.puts

        // A read found no new events, but the record now reaches further: a stretch still running.
        now += 5 * MINUTE
        record.commit(ReadOutcome(emptyList(), now, reachesUntil = now))
        uploader.upload()
        assertEquals(sentBefore, relay.puts)

        now += 6 * MINUTE
        record.commit(ReadOutcome(emptyList(), now, reachesUntil = now))
        uploader.upload()
        assertEquals(sentBefore + 1, relay.puts)

        pair()
        puller.pull(deviceId)
        assertEquals(now, copy.coverage()!!.until)
    }

    @Test
    fun `without a connection the days wait for the next run`() {
        relay.reachable = false
        assertEquals(Uploader.Result.Failed(RelayException.Reason.UNREACHABLE), uploader.upload())
        assertEquals(RelayException.Reason.UNREACHABLE, sharing.failure)
        assertEquals(3, record.pendingDays().size)

        relay.reachable = true
        assertEquals(Uploader.Result.Uploaded(days = 3), uploader.upload())
        assertEquals(null, sharing.failure)
        assertEquals(0, record.pendingDays().size)
    }

    @Test
    fun `a connection lost halfway keeps the rest pending`() {
        relay.beforePut = { name -> if (name == "d${today - 1}") relay.reachable = false }
        assertEquals(Uploader.Result.Failed(RelayException.Reason.UNREACHABLE), uploader.upload())
        assertEquals(listOf(today - 1, today), record.pendingDays().map { it.day })

        relay.beforePut = {}
        relay.reachable = true
        assertEquals(Uploader.Result.Uploaded(days = 2), uploader.upload())
    }

    @Test
    fun `a day that gains events while it is being sent stays pending`() {
        relay.beforePut = { name ->
            if (name == "d$today") {
                relay.beforePut = {}
                recorded(ScreenEvent(at(today, 1000), SCREEN_ON))
            }
        }
        uploader.upload()
        assertEquals(listOf(today), record.pendingDays().map { it.day })

        uploader.upload()
        assertEquals(emptyList<Long>(), record.pendingDays().map { it.day })
        pair()
        puller.pull(deviceId)
        assertEquals(record.events(0, Long.MAX_VALUE), copy.events(0, Long.MAX_VALUE))
    }

    @Test
    fun `a relay that lost the device gets everything again, and the pairing still works`() {
        uploader.upload()
        pair()
        relay.devices.clear()

        now += HOUR
        recorded(ScreenEvent(at(today, 900), SCREEN_ON))
        assertEquals(Uploader.Result.Uploaded(days = 3), uploader.upload())
        puller.pull(deviceId)
        assertEquals(record.events(0, Long.MAX_VALUE), copy.events(0, Long.MAX_VALUE))
    }

    @Test
    fun `a paired phone that was up to date follows a relay that started over`() {
        uploader.upload()
        pair()
        puller.pull(deviceId)
        now += HOUR
        recorded(ScreenEvent(at(today, 700), SCREEN_ON), ScreenEvent(at(today, 710), SCREEN_OFF))
        uploader.upload()
        puller.pull(deviceId)

        // The relay's versions begin again at 1, far below where this copy had got to.
        relay.devices.clear()
        now += HOUR
        recorded(ScreenEvent(at(today, 900), SCREEN_ON))
        uploader.upload()
        puller.pull(deviceId)
        assertEquals(record.events(0, Long.MAX_VALUE), copy.events(0, Long.MAX_VALUE))
        assertEquals(now, copy.coverage()!!.until)
    }

    @Test
    fun `a gap in the record travels with it`() {
        val gap = Span(at(today - 1, 600), at(today - 1, 900))
        recorded(gap = gap)
        uploader.upload()
        pair()
        puller.pull(deviceId)
        assertEquals(Coverage(at(today - 2, 0), now, listOf(gap)), copy.coverage())
    }

    @Test
    fun `a phone that stopped sharing shows as gone, and what was fetched stays`() {
        uploader.upload()
        pair()
        puller.pull(deviceId)
        uploader.forgetOnRelay(SERVER, sharing.identity!!)

        assertEquals(Puller.Result.Gone, puller.pull(deviceId))
        assertTrue(viewing.paired(deviceId)!!.gone)
        assertEquals(6, copy.eventCount())
    }

    @Test
    fun `a file changed on the relay is left out and the rest is taken`() {
        uploader.upload()
        pair()
        val device = relay.devices.getValue(deviceId)
        val (version, content) = device.blobs.getValue("d$today")
        device.blobs["d$today"] = version to content.copyOf().also { it[40] = (it[40] + 1).toByte() }

        assertEquals(Puller.Result.Fetched(files = 4, unreadable = 1), puller.pull(deviceId))
        assertEquals(4, copy.eventCount())
    }

    @Test
    fun `one day's file served under another day's name is refused`() {
        uploader.upload()
        pair()
        val device = relay.devices.getValue(deviceId)
        device.blobs["d$today"] = device.blobs.getValue("d$today").first to device.blobs.getValue("d${today - 1}").second

        assertEquals(Puller.Result.Fetched(files = 4, unreadable = 1), puller.pull(deviceId))
        assertEquals(emptyList<ScreenEvent>(), copy.events(today * DAY_MS, (today + 1) * DAY_MS))
    }

    @Test
    fun `without the secret the files do not open`() {
        uploader.upload()
        val stranger = Identity.create()
        viewing.paired = listOf(PairedDevice(deviceId, "?", SERVER, stranger.secret))
        // The relay does not even hand them out: the read token comes from the secret.
        assertEquals(Puller.Result.Gone, puller.pull(deviceId))

        val file = relay.devices.getValue(deviceId).blobs.getValue("d$today").second
        val opened = runCatching { DeviceKey(deviceId, ShareCrypto.decode(stranger.secret)!!).open("d$today", file) }
        assertTrue(opened.isFailure)
    }

    private companion object {
        const val SERVER = "https://relay.example"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
