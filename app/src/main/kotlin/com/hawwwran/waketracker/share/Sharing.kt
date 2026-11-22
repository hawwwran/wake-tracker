package com.hawwwran.waketracker.share

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.hawwwran.waketracker.BuildConfig
import com.hawwwran.waketracker.data.SqliteEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

/**
 * Sharing a record between phones, through a relay the owner names (server/api.php).
 *
 * A phone with a relay set uploads its record there, encrypted on the phone; the relay
 * holds files it cannot read. A second phone gets to read them by scanning the first one's
 * pairing code, which carries the key from screen to camera. Any phone can do both: share
 * its own record, and follow several others, each in a tab of its own.
 *
 * Nothing of this is hidden on the phone that shares: its settings page says that it
 * uploads, where to, and has the switch to stop and the button for a new code, which cuts
 * off everyone holding the old one.
 */
class Sharing(private val app: Application, private val store: SqliteEventStore, private val scope: CoroutineScope) {

    data class State(
        val server: String? = null,
        val name: String = "",
        val lastUploadAt: Long = 0,
        val pendingDays: Int = 0,
        val failure: RelayException.Reason? = null,
        val paired: List<PairedDevice> = emptyList(),
    )

    enum class ServerResult { SAVED, INVALID, UNREACHABLE, NOT_A_RELAY }

    sealed interface Scan {
        /** A pairing code of another phone; [PairingCode.name] is what that phone suggests calling it. */
        data class Code(val code: PairingCode) : Scan

        data object NotACode : Scan

        data object OwnCode : Scan
    }

    private val settings = ShareSettings(PrefsKeyValues(app.getSharedPreferences("sharing", Context.MODE_PRIVATE)))
    private val uploader = Uploader(store, store, settings, ::HttpRelay, BuildConfig.VERSION_NAME, { ZoneId.systemDefault().id })
    private val puller = Puller(settings, ::HttpRelay, ::copyOf)
    private val copies = HashMap<String, SqliteEventStore>()

    private val current = MutableStateFlow(State())
    val state: StateFlow<State> = current

    /** Goes up whenever the copy of a paired phone's record may have changed. */
    private val pullCount = MutableStateFlow(0)
    val pulls: StateFlow<Int> = pullCount

    init {
        if (settings.deviceName.isEmpty()) {
            settings.deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        }
        scope.launch(Dispatchers.IO) {
            removeStrayCopies()
            publish()
        }
    }

    /** Sends what is pending to the relay, if one is set. Blocking. */
    fun push(): Uploader.Result = uploader.upload().also { publish() }

    /** Brings the copy of [deviceId]'s record up to date. Blocking. */
    fun pull(deviceId: String): Puller.Result = puller.pull(deviceId).also {
        pullCount.update { count -> count + 1 }
        publish()
    }

    /**
     * The copy of a paired phone's record on this phone; null when no such phone is paired.
     * Asking must never bring back the copy of a phone that was just removed.
     */
    @Synchronized
    fun copyOf(deviceId: String): SqliteEventStore? {
        if (settings.paired(deviceId) == null) return null
        return copies.getOrPut(deviceId) { SqliteEventStore(app, copyFile(deviceId).path) }
    }

    /**
     * Sets, or with an empty address clears, the relay this phone uploads to. An address is
     * kept only once a relay answered at it. Blocking.
     */
    fun setServer(typed: String): ServerResult {
        if (typed.isBlank()) {
            stopSharing()
            return ServerResult.SAVED
        }
        val server = ServerAddress.normalize(typed) ?: return ServerResult.INVALID
        try {
            HttpRelay(server).hello()
        } catch (e: RelayException) {
            return if (e.reason == RelayException.Reason.NOT_A_RELAY) ServerResult.NOT_A_RELAY else ServerResult.UNREACHABLE
        }
        if (server != settings.server) {
            settings.server = server
            startOver()
        }
        push()
        return ServerResult.SAVED
    }

    /** Stops uploading and has the relay forget this phone; the phones paired with it see that it stopped. Blocking. */
    fun stopSharing() {
        forgetOnRelay()
        settings.server = null
        settings.identity = null
        settings.registered = false
        settings.lastUploadAt = 0
        settings.failure = null
        publish()
    }

    /** A new identity on the relay: every pairing code handed out so far stops working. Blocking. */
    fun newCode() {
        forgetOnRelay()
        settings.identity = Identity.create()
        startOver()
        push()
    }

    /** What this phone shows for another to scan. Null while it does not share. */
    fun pairingCode(): PairingCode? {
        val server = settings.server ?: return null
        val identity = settings.identity ?: return null
        return PairingCode(server, identity.deviceId, identity.secret, settings.deviceName)
    }

    /** What a scanned QR code is. */
    fun read(scanned: String): Scan {
        val code = PairingCode.parse(scanned) ?: return Scan.NotACode
        return if (code.deviceId == settings.identity?.deviceId) Scan.OwnCode else Scan.Code(code)
    }

    /** Starts following the phone [code] belongs to, under the name its reader gave it. */
    fun pair(code: PairingCode, name: String) {
        val device = PairedDevice(code.deviceId, cleanName(name).ifEmpty { code.name }, code.server, code.secret)
        // Scanned again: the same phone, in the same place among the tabs.
        val known = settings.paired
        settings.paired = if (known.any { it.deviceId == code.deviceId }) {
            known.map { if (it.deviceId == code.deviceId) device.copy(sentAt = it.sentAt, fetchedAt = it.fetchedAt) else it }
        } else {
            known + device
        }
        publish()
        scope.launch(Dispatchers.IO) { pull(code.deviceId) }
    }

    fun rename(deviceId: String, name: String) {
        val clean = cleanName(name)
        if (clean.isEmpty()) return
        settings.updatePaired(deviceId) { it.copy(name = clean) }
        publish()
    }

    private fun cleanName(name: String) = name.trim().take(PairingCode.MOST_NAME)

    fun unpair(deviceId: String) {
        // Out of the list first: from then on nothing opens the copy again.
        settings.paired = settings.paired.filter { it.deviceId != deviceId }
        publish()
        synchronized(this) {
            copies.remove(deviceId)?.close()
            SqliteEventStore.delete(copyFile(deviceId))
        }
        pullCount.update { it + 1 }
    }

    /** Copies of phones that are not paired: left by a removal that was cut short. */
    private fun removeStrayCopies() {
        val paired = settings.paired.map { copyFile(it.deviceId).name }.toSet()
        app.noBackupFilesDir.listFiles { file -> file.name.startsWith(COPY_PREFIX) && file.name.endsWith(COPY_SUFFIX) }
            ?.filter { it.name !in paired }
            ?.forEach(SqliteEventStore::delete)
    }

    private fun forgetOnRelay() {
        val server = settings.server ?: return
        val identity = settings.identity ?: return
        uploader.forgetOnRelay(server, identity)
    }

    /** The relay, or this phone's identity on it, is new: it has nothing of this record yet. */
    private fun startOver() {
        settings.registered = false
        store.markAllPending()
        uploader.metaChanged()
    }

    private fun publish() {
        current.value = State(
            server = settings.server,
            name = settings.deviceName,
            lastUploadAt = settings.lastUploadAt,
            pendingDays = if (settings.server == null) 0 else store.pendingDays().size,
            failure = settings.failure,
            paired = settings.paired,
        )
    }

    // Outside what Android backs up: a copy can be fetched again, and it is another person's record.
    private fun copyFile(deviceId: String) = File(app.noBackupFilesDir, "$COPY_PREFIX$deviceId$COPY_SUFFIX")

    private companion object {
        const val COPY_PREFIX = "paired-"
        const val COPY_SUFFIX = ".db"
    }
}

private class PrefsKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun string(key: String): String? = prefs.getString(key, null)
    override fun long(key: String): Long = prefs.getLong(key, 0)
    override fun put(key: String, value: String?) = prefs.edit { putString(key, value) }
    override fun put(key: String, value: Long) = prefs.edit { putLong(key, value) }
}
