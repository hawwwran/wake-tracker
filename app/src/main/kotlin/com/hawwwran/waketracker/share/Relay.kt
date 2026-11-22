package com.hawwwran.waketracker.share

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RelayException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason {
        /** No network, or no answer. Worth trying again later. */
        UNREACHABLE,

        /** Something answers at the address, but it is not a Wake Tracker relay. */
        NOT_A_RELAY,

        /** The relay has no such device: never registered, or forgotten since. */
        UNKNOWN_DEVICE,

        /** The relay knows the device and does not accept the token. */
        REFUSED,

        FULL,
        FAILED,
    }
}

data class Change(val name: String, val version: Long)

/** The files of a device written after some version, and the version the device is at now. */
data class Changes(val version: Long, val blobs: List<Change>)

/** The relay (server/api.php), as the app talks to it. Every call throws [RelayException] when it does not go through. */
interface Relay {
    fun hello()
    fun register(deviceId: String, writeToken: String, readToken: String)
    fun put(deviceId: String, name: String, writeToken: String, content: ByteArray)
    fun changes(deviceId: String, readToken: String, since: Long): Changes
    fun get(deviceId: String, name: String, readToken: String): ByteArray
    fun forget(deviceId: String, writeToken: String)
}

class HttpRelay(private val server: String) : Relay {

    override fun hello() {
        val answer = json(call("GET", "a=hello"))
        if (answer.optString("service") != "wake-tracker") throw RelayException(RelayException.Reason.NOT_A_RELAY)
    }

    override fun register(deviceId: String, writeToken: String, readToken: String) {
        val body = JSONObject().put("write", writeToken).put("read", readToken).toString().toByteArray()
        call("POST", "a=register&device=$deviceId", body = body)
    }

    override fun put(deviceId: String, name: String, writeToken: String, content: ByteArray) {
        call("PUT", "a=blob&device=$deviceId&name=$name", token = writeToken, body = content)
    }

    override fun changes(deviceId: String, readToken: String, since: Long): Changes {
        val answer = json(call("GET", "a=changes&device=$deviceId&since=$since", token = readToken))
        try {
            val blobs = answer.getJSONArray("blobs")
            return Changes(
                version = answer.getLong("version"),
                blobs = List(blobs.length()) { index -> blobs.getJSONObject(index).let { Change(it.getString("name"), it.getLong("version")) } },
            )
        } catch (e: JSONException) {
            throw RelayException(RelayException.Reason.NOT_A_RELAY, e)
        }
    }

    override fun get(deviceId: String, name: String, readToken: String): ByteArray =
        call("GET", "a=blob&device=$deviceId&name=$name", token = readToken)

    override fun forget(deviceId: String, writeToken: String) {
        call("DELETE", "a=device&device=$deviceId", token = writeToken)
    }

    private fun json(bytes: ByteArray): JSONObject =
        try {
            JSONObject(bytes.decodeToString())
        } catch (e: JSONException) {
            // A web page where the relay should be: a typo in the address, a captive portal.
            throw RelayException(RelayException.Reason.NOT_A_RELAY, e)
        }

    private fun call(method: String, query: String, token: String? = null, body: ByteArray? = null): ByteArray {
        try {
            val connection = URL("$server/api.php?$query").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "wake-tracker")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_OK) return connection.inputStream.use { it.readBytes() }
                val error = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                throw RelayException(reasonFor(status, error))
            } finally {
                connection.disconnect()
            }
        } catch (e: RelayException) {
            throw e
        } catch (e: IOException) {
            throw RelayException(RelayException.Reason.UNREACHABLE, e)
        }
    }

    private fun reasonFor(status: Int, error: String): RelayException.Reason = when {
        // The relay's own "no such device". Any other 404 is a web server that has no relay at this address.
        status == 404 && error.contains("unknown_device") -> RelayException.Reason.UNKNOWN_DEVICE
        status == 404 && !error.contains("\"error\"") -> RelayException.Reason.NOT_A_RELAY
        status == 403 || status == 409 -> RelayException.Reason.REFUSED
        status == 507 -> RelayException.Reason.FULL
        else -> RelayException.Reason.FAILED
    }
}
