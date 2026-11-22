package com.hawwwran.waketracker.share

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where the sharing settings are kept: the app's preferences, or a map in tests. */
interface KeyValues {
    fun string(key: String): String?
    fun long(key: String): Long
    fun put(key: String, value: String?)
    fun put(key: String, value: Long)
}

/**
 * This phone on a relay: its id there, the secret the pairing code carries, and the token
 * that lets it write. The write token is never shown or sent to anyone but the relay.
 */
data class Identity(val deviceId: String, val secret: String, val writeToken: String) {
    fun key(): DeviceKey = DeviceKey(deviceId, ShareCrypto.decode(secret)!!)

    companion object {
        fun create(): Identity = Identity(
            deviceId = ShareCrypto.encode(ShareCrypto.randomBytes(ShareCrypto.DEVICE_ID_BYTES)),
            secret = ShareCrypto.encode(ShareCrypto.randomBytes(ShareCrypto.SECRET_BYTES)),
            writeToken = ShareCrypto.encode(ShareCrypto.randomBytes(ShareCrypto.SECRET_BYTES)),
        )
    }
}

/** Another phone whose record this one reads. */
data class PairedDevice(
    val deviceId: String,
    /** What this phone's owner calls it; theirs to choose and to change. */
    val name: String,
    val server: String,
    val secret: String,
    /** When that phone last sent to the relay, by its own clock, as far as this phone has seen. 0: nothing seen yet. */
    val sentAt: Long = 0,
    /** When this phone last asked the relay with success. 0: never yet. */
    val fetchedAt: Long = 0,
    /** The relay no longer knows the device: that phone stopped sharing, or made itself a new code. */
    val gone: Boolean = false,
) {
    fun key(): DeviceKey = DeviceKey(deviceId, ShareCrypto.decode(secret)!!)
}

/** Everything sharing remembers between runs. */
class ShareSettings(private val values: KeyValues) {

    /** The relay this phone uploads to; null while it shares nothing. */
    var server: String?
        get() = values.string(SERVER)
        set(value) = values.put(SERVER, value)

    /** What the pairing code suggests as this phone's name; the phone that scans it can choose another. */
    var deviceName: String
        get() = values.string(NAME).orEmpty()
        set(value) = values.put(NAME, value.trim().take(PairingCode.MOST_NAME))

    var identity: Identity?
        get() {
            val deviceId = values.string(DEVICE_ID) ?: return null
            val secret = values.string(SECRET) ?: return null
            val writeToken = values.string(WRITE_TOKEN) ?: return null
            return Identity(deviceId, secret, writeToken)
        }
        set(value) {
            values.put(DEVICE_ID, value?.deviceId)
            values.put(SECRET, value?.secret)
            values.put(WRITE_TOKEN, value?.writeToken)
        }

    /** Whether the relay in [server] has been told of [identity]. */
    var registered: Boolean
        get() = values.long(REGISTERED) == 1L
        set(value) = values.put(REGISTERED, if (value) 1L else 0L)

    var lastUploadAt: Long
        get() = values.long(LAST_UPLOAD_AT)
        set(value) = values.put(LAST_UPLOAD_AT, value)

    /** How far the record reached in the meta file the relay has, and when that was sent. */
    var metaReach: Long
        get() = values.long(META_REACH)
        set(value) = values.put(META_REACH, value)
    var metaSentAt: Long
        get() = values.long(META_SENT_AT)
        set(value) = values.put(META_SENT_AT, value)

    /** Why the last upload did not go through; null when it did. */
    var failure: RelayException.Reason?
        get() = values.string(FAILURE)?.let { name -> RelayException.Reason.entries.firstOrNull { it.name == name } }
        set(value) = values.put(FAILURE, value?.name)

    var paired: List<PairedDevice>
        get() = try {
            val list = JSONArray(values.string(PAIRED) ?: "[]")
            List(list.length()) { index ->
                val json = list.getJSONObject(index)
                PairedDevice(
                    deviceId = json.getString("id"),
                    name = json.getString("name"),
                    server = json.getString("server"),
                    secret = json.getString("secret"),
                    sentAt = json.optLong("sentAt"),
                    fetchedAt = json.optLong("fetchedAt"),
                    gone = json.optBoolean("gone"),
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
        set(devices) {
            val list = JSONArray()
            for (device in devices) {
                list.put(
                    JSONObject()
                        .put("id", device.deviceId)
                        .put("name", device.name)
                        .put("server", device.server)
                        .put("secret", device.secret)
                        .put("sentAt", device.sentAt)
                        .put("fetchedAt", device.fetchedAt)
                        .put("gone", device.gone),
                )
            }
            values.put(PAIRED, list.toString())
        }

    fun paired(deviceId: String): PairedDevice? = paired.firstOrNull { it.deviceId == deviceId }

    /** Applies [change] to one paired device, if it is still paired. */
    @Synchronized
    fun updatePaired(deviceId: String, change: (PairedDevice) -> PairedDevice) {
        paired = paired.map { if (it.deviceId == deviceId) change(it) else it }
    }

    private companion object {
        const val SERVER = "server"
        const val NAME = "name"
        const val DEVICE_ID = "device_id"
        const val SECRET = "secret"
        const val WRITE_TOKEN = "write_token"
        const val REGISTERED = "registered"
        const val LAST_UPLOAD_AT = "last_upload_at"
        const val META_REACH = "meta_reach"
        const val META_SENT_AT = "meta_sent_at"
        const val FAILURE = "failure"
        const val PAIRED = "paired"
    }
}
