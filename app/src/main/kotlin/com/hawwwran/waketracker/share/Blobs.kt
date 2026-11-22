package com.hawwwran.waketracker.share

import com.hawwwran.waketracker.data.DAY_MS
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The files a shared record is made of, before encryption: one per UTC day with that day's
 * events, and "meta" with what the record covers. A day's file always holds the whole day,
 * so uploading it again replaces it and nothing has to be merged.
 */
object Blobs {
    const val META = "meta"

    // Padding sizes (ShareCrypto.seal). A usual day's events fit one block of 4 KiB.
    const val DAY_BLOCK = 4096
    const val META_BLOCK = 1024

    fun dayName(day: Long): String = "d$day"

    /** The day a file name stands for; null for "meta" or anything else. */
    fun dayOf(name: String): Long? = name.takeIf { it.startsWith("d") }?.drop(1)?.toLongOrNull()
}

/** A day's events, a few bytes each: the gap since the event before as a varint, then the type. */
object DayCodec {
    private const val FORMAT = 1
    private const val MOST_EVENTS = 200_000

    /** @param events of [day], oldest first */
    fun encode(day: Long, events: List<ScreenEvent>): ByteArray {
        val out = ByteArrayOutputStream(2 + events.size * 4)
        out.write(FORMAT)
        writeVarint(out, events.size.toLong())
        var previous = day * DAY_MS
        for (event in events) {
            require(event.ts >= previous && event.ts < (day + 1) * DAY_MS) { "event outside day $day, or out of order" }
            writeVarint(out, event.ts - previous)
            out.write(event.type)
            previous = event.ts
        }
        return out.toByteArray()
    }

    /** Throws IllegalArgumentException when [bytes] is not a file of [day]. */
    fun decode(day: Long, bytes: ByteArray): List<ScreenEvent> {
        val input = Reader(bytes)
        require(input.byte() == FORMAT) { "unknown format" }
        val count = input.varint()
        require(count in 0..MOST_EVENTS) { "implausible count" }
        val events = ArrayList<ScreenEvent>(count.toInt())
        var previous = day * DAY_MS
        repeat(count.toInt()) {
            val ts = previous + input.varint()
            val type = input.byte()
            require(ts < (day + 1) * DAY_MS && EventType.isTracked(type)) { "event outside day $day, or of no known type" }
            events += ScreenEvent(ts, type)
            previous = ts
        }
        return events
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var rest = value
        while (rest >= 0x80) {
            out.write((rest and 0x7F).toInt() or 0x80)
            rest = rest ushr 7
        }
        out.write(rest.toInt())
    }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        fun byte(): Int {
            require(at < bytes.size) { "file ends early" }
            return bytes[at++].toInt() and 0xFF
        }

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val next = byte()
                value = value or ((next and 0x7F).toLong() shl shift)
                if (next < 0x80) return value
                shift += 7
                require(shift < 63) { "number too long" }
            }
        }
    }
}

/**
 * What the record covers, and when the phone sent this; small, and uploaded whenever the
 * record moves. [sentAt] is by the sending phone's clock, like every time in its record.
 */
data class RecordMeta(val coverage: Coverage, val zone: String, val appVersion: String, val sentAt: Long) {

    fun encode(): ByteArray =
        JSONObject()
            .put("v", 1)
            .put("sent", sentAt)
            .put("from", coverage.from)
            .put("until", coverage.until)
            .put("gaps", JSONArray().apply { coverage.gaps.forEach { put(JSONArray().put(it.start).put(it.end)) } })
            .put("zone", zone)
            .put("app", appVersion)
            .toString()
            .toByteArray()

    companion object {
        /** Throws IllegalArgumentException when [bytes] is not a meta file. */
        fun decode(bytes: ByteArray): RecordMeta =
            try {
                val json = JSONObject(bytes.decodeToString())
                val gaps = json.getJSONArray("gaps")
                RecordMeta(
                    coverage = Coverage(
                        from = json.getLong("from"),
                        until = json.getLong("until"),
                        gaps = List(gaps.length()) { index -> gaps.getJSONArray(index).let { Span(it.getLong(0), it.getLong(1)) } },
                    ),
                    zone = json.optString("zone"),
                    appVersion = json.optString("app"),
                    sentAt = json.getLong("sent"),
                )
            } catch (e: JSONException) {
                throw IllegalArgumentException("not a meta file", e)
            }
    }
}
