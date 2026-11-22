package com.hawwwran.waketracker.share

import com.hawwwran.waketracker.data.DAY_MS
import com.hawwwran.waketracker.data.EventStore
import com.hawwwran.waketracker.data.Outbox

/**
 * Puts this phone's record on the relay, encrypted, for the phones paired with it.
 *
 * Only days that gained events since the last upload are sent, each as one file with the
 * whole day in it, so sending a day twice or being cut off halfway does no harm: the store
 * keeps a day pending until an upload of it, as it stands now, went through. With no
 * network the days simply stay pending and the next run takes them.
 */
class Uploader(
    private val store: EventStore,
    private val outbox: Outbox,
    private val settings: ShareSettings,
    private val relayFor: (server: String) -> Relay,
    private val appVersion: String,
    private val zone: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        /** No relay is set, or there is no record yet. */
        data object Idle : Result

        data class Uploaded(val days: Int) : Result

        data class Failed(val reason: RelayException.Reason) : Result
    }

    @Synchronized
    fun upload(): Result {
        val server = settings.server ?: return Result.Idle
        val coverage = store.coverage() ?: return Result.Idle
        val identity = settings.identity ?: Identity.create().also {
            settings.identity = it
            settings.registered = false
        }
        val relay = relayFor(server)
        val key = identity.key()

        try {
            if (!settings.registered) register(relay, identity)
            var days = sendPendingDays(relay, identity, key)
            if (days == RELAY_FORGOT) {
                // The relay lost this device (its data folder was emptied, say). It gets
                // everything again under the same identity, so pairings keep working.
                register(relay, identity)
                outbox.markAllPending()
                days = sendPendingDays(relay, identity, key)
                if (days == RELAY_FORGOT) return failed(RelayException.Reason.UNKNOWN_DEVICE)
            }

            // The meta file says how far the record reaches. Without new events that only
            // matters for a stretch still running, so it is not worth a request every minute.
            val reachMoved = coverage.until != settings.metaReach
            if (days > 0 || (reachMoved && now() - settings.metaSentAt >= META_AT_MOST_EVERY)) {
                val meta = RecordMeta(coverage, zone(), appVersion, sentAt = now())
                relay.put(identity.deviceId, Blobs.META, identity.writeToken, key.seal(Blobs.META, meta.encode(), Blobs.META_BLOCK))
                settings.metaReach = coverage.until
                settings.metaSentAt = now()
            }
            settings.lastUploadAt = now()
            settings.failure = null
            return Result.Uploaded(days)
        } catch (e: RelayException) {
            return failed(e.reason)
        }
    }

    /** Tells the relay to forget this phone's record; with a new identity the old pairing codes stop working. */
    @Synchronized
    fun forgetOnRelay(server: String, identity: Identity) {
        try {
            relayFor(server).forget(identity.deviceId, identity.writeToken)
        } catch (_: RelayException) {
            // Best effort. What stays on the relay is ciphertext nobody is given a new key
            // for; a paired phone that still has the old code can read up to here, no further.
        }
    }

    /** Sends the meta file at the next upload whatever else happened: the relay has none yet. */
    fun metaChanged() {
        settings.metaSentAt = 0
        settings.metaReach = 0
    }

    private fun register(relay: Relay, identity: Identity) {
        relay.register(identity.deviceId, identity.writeToken, identity.key().readToken)
        settings.registered = true
    }

    /** How many days went up, or [RELAY_FORGOT]. */
    private fun sendPendingDays(relay: Relay, identity: Identity, key: DeviceKey): Int {
        var sent = 0
        for (pending in outbox.pendingDays()) {
            val events = store.events(pending.day * DAY_MS, (pending.day + 1) * DAY_MS)
            val name = Blobs.dayName(pending.day)
            try {
                relay.put(identity.deviceId, name, identity.writeToken, key.seal(name, DayCodec.encode(pending.day, events), Blobs.DAY_BLOCK))
            } catch (e: RelayException) {
                if (e.reason == RelayException.Reason.UNKNOWN_DEVICE) return RELAY_FORGOT
                throw e
            }
            outbox.clearPending(pending.day, pending.stamp)
            sent++
        }
        return sent
    }

    private fun failed(reason: RelayException.Reason): Result {
        settings.failure = reason
        return Result.Failed(reason)
    }

    private companion object {
        const val RELAY_FORGOT = -1
        const val META_AT_MOST_EVERY = 10 * 60 * 1000L
    }
}
