package com.hawwwran.waketracker.share

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a phone shows as a QR code so that another phone can read its record: where the
 * record is (the relay and the device's id there) and the secret that decrypts it. Whoever
 * holds this text can read the record until the phone makes itself a new code.
 */
data class PairingCode(val server: String, val deviceId: String, val secret: String, val name: String) {

    fun key(): DeviceKey = DeviceKey(deviceId, ShareCrypto.decode(secret)!!)

    fun text(): String = "$PREFIX?v=1&s=${escape(server)}&d=$deviceId&k=$secret&n=${escape(name)}"

    companion object {
        private const val PREFIX = "waketracker:pair"
        private val ID = Regex("[A-Za-z0-9_-]{22}")

        /** Null for anything that is not a pairing code of this app: another QR code, a damaged one. */
        fun parse(text: String): PairingCode? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("$PREFIX?")) return null
            val fields = trimmed.substringAfter('?').split('&').mapNotNull { field ->
                val name = field.substringBefore('=', "")
                if (name.isEmpty()) null else name to unescape(field.substringAfter('='))
            }.toMap()
            if (fields["v"] != "1") return null
            val server = fields["s"]?.let(ServerAddress::normalize) ?: return null
            val deviceId = fields["d"]?.takeIf(ID::matches) ?: return null
            val secret = fields["k"]?.takeIf { ShareCrypto.decode(it)?.size == ShareCrypto.SECRET_BYTES } ?: return null
            return PairingCode(server, deviceId, secret, fields["n"].orEmpty().take(MOST_NAME))
        }

        const val MOST_NAME = 40

        private fun escape(text: String) = URLEncoder.encode(text, "UTF-8")
        private fun unescape(text: String) =
            try {
                URLDecoder.decode(text, "UTF-8")
            } catch (_: IllegalArgumentException) {
                ""
            }
    }
}

object ServerAddress {
    /**
     * The relay's address as the app keeps it: `https://host/folder`, no slash at the end.
     * Forgiving about what gets typed on a phone (no scheme, a trailing slash, the script's
     * name), strict about the rest: only https, because the tokens travel in the request.
     * Plain http is let through for this computer's own address, for trying a relay locally.
     */
    fun normalize(typed: String): String? {
        var text = typed.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "https://$text"
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host ?: return null
        val local = host == "localhost" || host == "127.0.0.1"
        if (uri.scheme != "https" && !(uri.scheme == "http" && local)) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val path = uri.path.orEmpty().removeSuffix("/").removeSuffix("/api.php").removeSuffix("/")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${host.lowercase()}$port$path"
    }
}
