package com.hawwwran.waketracker.share

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What a phone needs to read another phone's record off the relay: the device's id there,
 * and its secret. The secret travels only inside the pairing code, from one screen to the
 * other phone's camera, and never to the relay. From it come the key the record is
 * encrypted with and the token the relay hands the files out for; the relay knows a hash
 * of that token and nothing of the key.
 */
class DeviceKey(val deviceId: String, secret: ByteArray) {
    val dataKey: ByteArray = ShareCrypto.derive(secret, "wake-tracker data key 1")
    val readToken: String = ShareCrypto.encode(ShareCrypto.derive(secret, "wake-tracker read token 1"))

    /** Ties a file to its device and its name, so the relay cannot hand out one day as another. */
    fun context(blobName: String): String = "wake-tracker/1/$deviceId/$blobName"

    fun seal(blobName: String, content: ByteArray, block: Int): ByteArray =
        ShareCrypto.seal(dataKey, context(blobName), content, block)

    @Throws(GeneralSecurityException::class)
    fun open(blobName: String, sealed: ByteArray): ByteArray = ShareCrypto.open(dataKey, context(blobName), sealed)
}

object ShareCrypto {
    private const val FORMAT: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    const val SECRET_BYTES = 32
    const val DEVICE_ID_BYTES = 16

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    /** URL-safe base64 without padding: 22 characters for a device id, 43 for a secret or token. */
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray? =
        try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun derive(secret: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(label.toByteArray())
    }

    /**
     * AES-256-GCM under a fresh random nonce. The content is first padded with zeros to a
     * whole number of [block]s, its true length in front, so the size the relay sees says
     * little about how much is inside. Layout: format byte, nonce, ciphertext with tag.
     */
    fun seal(key: ByteArray, context: String, content: ByteArray, block: Int): ByteArray {
        val padded = ByteArray((Int.SIZE_BYTES + content.size + block - 1) / block * block)
        ByteBuffer.wrap(padded).putInt(content.size).put(content)
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context.toByteArray())
        return byteArrayOf(FORMAT) + nonce + cipher.doFinal(padded)
    }

    /** The content of [sealed]. Throws when it was not sealed with this key for this context, or was changed since. */
    @Throws(GeneralSecurityException::class)
    fun open(key: ByteArray, context: String, sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + NONCE_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT) throw GeneralSecurityException("not a sealed file")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES))
        cipher.updateAAD(context.toByteArray())
        val padded = cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.size - 1 - NONCE_BYTES)
        if (padded.size < Int.SIZE_BYTES) throw GeneralSecurityException("no length")
        val length = ByteBuffer.wrap(padded).int
        if (length < 0 || length > padded.size - Int.SIZE_BYTES) throw GeneralSecurityException("bad length")
        return padded.copyOfRange(Int.SIZE_BYTES, Int.SIZE_BYTES + length)
    }
}
