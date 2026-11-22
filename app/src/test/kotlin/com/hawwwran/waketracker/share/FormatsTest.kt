package com.hawwwran.waketracker.share

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.hawwwran.waketracker.data.DAY_MS
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType.KEYGUARD_HIDDEN
import com.hawwwran.waketracker.model.EventType.SCREEN_OFF
import com.hawwwran.waketracker.model.EventType.SCREEN_ON
import com.hawwwran.waketracker.model.EventType.STARTUP
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/** The encryption, the file formats and the pairing code. */
class FormatsTest {

    private val key = ShareCrypto.randomBytes(32)

    @Test
    fun `what is sealed opens again`() {
        for (content in listOf(ByteArray(0), "x".toByteArray(), ShareCrypto.randomBytes(4092), ShareCrypto.randomBytes(4093), ShareCrypto.randomBytes(10_000))) {
            assertArrayEquals(content, ShareCrypto.open(key, "c", ShareCrypto.seal(key, "c", content, 4096)))
        }
    }

    @Test
    fun `sealing pads to whole blocks and never repeats itself`() {
        assertEquals(1 + 12 + 4096 + 16, ShareCrypto.seal(key, "c", ByteArray(10), 4096).size)
        assertEquals(1 + 12 + 4096 + 16, ShareCrypto.seal(key, "c", ByteArray(4092), 4096).size)
        assertEquals(1 + 12 + 8192 + 16, ShareCrypto.seal(key, "c", ByteArray(4093), 4096).size)
        assertNotEquals(ShareCrypto.seal(key, "c", ByteArray(10), 4096).toList(), ShareCrypto.seal(key, "c", ByteArray(10), 4096).toList())
    }

    @Test
    fun `another key, another context or a changed byte does not open`() {
        val sealed = ShareCrypto.seal(key, "wake-tracker/1/device/d1", "content".toByteArray(), 64)
        assertThrows(GeneralSecurityException::class.java) { ShareCrypto.open(ShareCrypto.randomBytes(32), "wake-tracker/1/device/d1", sealed) }
        assertThrows(GeneralSecurityException::class.java) { ShareCrypto.open(key, "wake-tracker/1/device/d2", sealed) }
        for (index in listOf(0, 5, 20, sealed.size - 1)) {
            val changed = sealed.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { ShareCrypto.open(key, "wake-tracker/1/device/d1", changed) }
        }
        assertThrows(GeneralSecurityException::class.java) { ShareCrypto.open(key, "c", ByteArray(5)) }
    }

    @Test
    fun `a secret gives its key and read token, and they are not each other`() {
        val secret = ShareCrypto.randomBytes(32)
        val one = DeviceKey("d".repeat(22), secret)
        val again = DeviceKey("d".repeat(22), secret)
        assertArrayEquals(one.dataKey, again.dataKey)
        assertEquals(one.readToken, again.readToken)
        assertEquals(43, one.readToken.length)
        assertNotEquals(ShareCrypto.encode(one.dataKey), one.readToken)
        assertNotEquals(one.readToken, DeviceKey("d".repeat(22), ShareCrypto.randomBytes(32)).readToken)
    }

    @Test
    fun `a day survives its file`() {
        val day = 20_800L
        val events = listOf(
            ScreenEvent(day * DAY_MS, STARTUP),
            ScreenEvent(day * DAY_MS + 5, SCREEN_ON),
            ScreenEvent(day * DAY_MS + 5, KEYGUARD_HIDDEN),
            ScreenEvent(day * DAY_MS + 3_600_000, SCREEN_OFF),
            ScreenEvent((day + 1) * DAY_MS - 1, SCREEN_ON),
        )
        assertEquals(events, DayCodec.decode(day, DayCodec.encode(day, events)))
        assertEquals(emptyList<ScreenEvent>(), DayCodec.decode(day, DayCodec.encode(day, emptyList())))
    }

    @Test
    fun `a busy day stays small`() {
        val day = 20_800L
        val events = (0 until 600).map { ScreenEvent(day * DAY_MS + it * 120_000L + it % 7, if (it % 2 == 0) SCREEN_ON else SCREEN_OFF) }
        assertTrue(DayCodec.encode(day, events).size < 4092)
    }

    @Test
    fun `a file that is not this day's is refused`() {
        val day = 20_800L
        val file = DayCodec.encode(day, listOf(ScreenEvent(day * DAY_MS + DAY_MS / 2, SCREEN_ON), ScreenEvent(day * DAY_MS + DAY_MS - 5, SCREEN_OFF)))
        assertThrows(IllegalArgumentException::class.java) { DayCodec.decode(day, file.copyOf(file.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { DayCodec.decode(day, byteArrayOf(9, 0)) }
        assertThrows(IllegalArgumentException::class.java) { DayCodec.decode(day, byteArrayOf()) }
        // Two gaps of most of a day each run past the day's end.
        val overlong = DayCodec.encode(day, listOf(ScreenEvent(day * DAY_MS + DAY_MS - 5, SCREEN_ON)))
        val twice = byteArrayOf(overlong[0], 2) + overlong.drop(2) + overlong.drop(2)
        assertThrows(IllegalArgumentException::class.java) { DayCodec.decode(day, twice) }
        assertThrows(IllegalArgumentException::class.java) { DayCodec.encode(day, listOf(ScreenEvent((day + 1) * DAY_MS, SCREEN_ON))) }
    }

    @Test
    fun `what the record covers survives its file`() {
        val meta = RecordMeta(Coverage(1_000, 9_000, listOf(Span(2_000, 3_000), Span(5_000, 5_500))), "Europe/Prague", "0.2.0", sentAt = 9_100)
        assertEquals(meta, RecordMeta.decode(meta.encode()))
        assertThrows(IllegalArgumentException::class.java) { RecordMeta.decode("{}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { RecordMeta.decode("not json".toByteArray()) }
    }

    @Test
    fun `a pairing code survives being shown and scanned`() {
        val identity = Identity.create()
        val code = PairingCode("https://relay.example/tracker", identity.deviceId, identity.secret, "Anna & máma: telefon")
        assertEquals(code, PairingCode.parse(code.text()))
        assertEquals(identity.key().readToken, PairingCode.parse(code.text())!!.key().readToken)
    }

    @Test
    fun `the pairing code fits a QR code and reads back from it`() {
        val identity = Identity.create()
        val code = PairingCode("https://relay.example/some/longer/folder", identity.deviceId, identity.secret, "Žluťoučký kůň, telefon dcery".take(PairingCode.MOST_NAME))
        val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(code.text(), BarcodeFormat.QR_CODE, 0, 0, hints)
        // Small enough to scan off a phone screen from a hand's distance.
        assertTrue("${matrix.width} modules", matrix.width <= 85)

        val scale = 4
        val pixels = IntArray(matrix.width * scale * matrix.height * scale) { index ->
            val x = index % (matrix.width * scale) / scale
            val y = index / (matrix.width * scale) / scale
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val picture = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width * scale, matrix.height * scale, pixels)))
        assertEquals(code, PairingCode.parse(QRCodeReader().decode(picture).text))
    }

    @Test
    fun `other codes are not pairing codes`() {
        val identity = Identity.create()
        val good = PairingCode("https://relay.example", identity.deviceId, identity.secret, "A").text()
        assertNull(PairingCode.parse("https://example.com/?d=1"))
        assertNull(PairingCode.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(PairingCode.parse(""))
        assertNull(PairingCode.parse(good.replace("v=1", "v=2")))
        assertNull(PairingCode.parse(good.replace(identity.deviceId, "short")))
        assertNull(PairingCode.parse(good.replace(identity.secret, identity.secret.dropLast(4))))
        assertNull(PairingCode.parse(good.replace("https%3A%2F%2Frelay.example", "ftp%3A%2F%2Frelay.example")))
    }

    @Test
    fun `a relay address is taken as typed on a phone, as long as it is https`() {
        assertEquals("https://relay.example", ServerAddress.normalize("relay.example"))
        assertEquals("https://relay.example", ServerAddress.normalize("  https://Relay.Example/ "))
        assertEquals("https://relay.example", ServerAddress.normalize("https://relay.example/api.php"))
        assertEquals("https://relay.example/tracker", ServerAddress.normalize("relay.example/tracker/"))
        assertEquals("https://relay.example:8443/a/b", ServerAddress.normalize("https://relay.example:8443/a/b/api.php"))
        assertEquals("http://localhost:8765", ServerAddress.normalize("http://localhost:8765/"))
        assertNull(ServerAddress.normalize("http://relay.example"))
        assertNull(ServerAddress.normalize("https://user:pass@relay.example"))
        assertNull(ServerAddress.normalize("https://relay.example/?a=hello"))
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("not an address"))
    }
}
