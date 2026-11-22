package com.hawwwran.waketracker.share

import com.hawwwran.waketracker.data.Replica
import java.security.GeneralSecurityException

/**
 * Brings this phone's copy of a paired phone's record up to what the relay has: asks which
 * files changed since the last look, fetches and decrypts those, and replaces the days they
 * hold. A file that does not decrypt, or is not what its name says, is left out: the copy
 * then keeps what it had for that day.
 */
class Puller(
    private val settings: ShareSettings,
    private val relayFor: (server: String) -> Relay,
    private val replicaFor: (deviceId: String) -> Replica?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Fetched(val files: Int, val unreadable: Int) : Result

        /** The relay does not know the device any more. */
        data object Gone : Result

        data class Failed(val reason: RelayException.Reason) : Result

        data object NotPaired : Result
    }

    fun pull(deviceId: String): Result {
        val device = settings.paired(deviceId) ?: return Result.NotPaired
        val relay = relayFor(device.server)
        val key = device.key()
        val replica = replicaFor(deviceId) ?: return Result.NotPaired
        try {
            var changes = relay.changes(deviceId, key.readToken, replica.cursor())
            // A relay that lost the device and was given it again counts its versions from
            // the start. Everything there is newer than this copy, whatever the numbers say.
            if (changes.version < replica.cursor()) changes = relay.changes(deviceId, key.readToken, 0)
            var sentAt = device.sentAt
            var unreadable = 0
            for (change in changes.blobs) {
                val sealed = relay.get(deviceId, change.name, key.readToken)
                try {
                    val content = key.open(change.name, sealed)
                    val day = Blobs.dayOf(change.name)
                    when {
                        day != null -> replica.replaceDay(day, DayCodec.decode(day, content))
                        change.name == Blobs.META -> RecordMeta.decode(content).let {
                            replica.setCoverage(it.coverage)
                            sentAt = it.sentAt
                        }
                    }
                } catch (_: GeneralSecurityException) {
                    unreadable++
                } catch (_: IllegalArgumentException) {
                    unreadable++
                }
            }
            // Last, so that a fetch cut off halfway is simply made again.
            replica.advanceCursor(changes.version)
            settings.updatePaired(deviceId) { it.copy(sentAt = sentAt, fetchedAt = now(), gone = false) }
            return Result.Fetched(changes.blobs.size, unreadable)
        } catch (e: RelayException) {
            if (e.reason == RelayException.Reason.UNKNOWN_DEVICE || e.reason == RelayException.Reason.REFUSED) {
                settings.updatePaired(deviceId) { it.copy(gone = true) }
                return Result.Gone
            }
            return Result.Failed(e.reason)
        }
    }
}
