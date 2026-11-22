package com.hawwwran.waketracker.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.EventType
import com.hawwwran.waketracker.model.ScreenEvent
import com.hawwwran.waketracker.model.Span

/**
 * The history, in SQLite. The primary key (ts, type) is what makes a repeated read harmless:
 * an event that is already there is skipped.
 */
class SqliteEventStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1), EventStore {

    init {
        // Readers (the screen) are not held up by the writer (a read of the log).
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (ts INTEGER NOT NULL, type INTEGER NOT NULL, PRIMARY KEY (ts, type)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE gaps (from_ts INTEGER NOT NULL PRIMARY KEY, to_ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only one. A later version migrates here and never drops a table:
        // the system keeps its log for ten days, the rest exists only in this file.
    }

    override fun coverage(): Coverage? {
        val db = readableDatabase
        val from = db.meta(RECORD_START) ?: return null
        val until = db.meta(REACHES_UNTIL) ?: return null
        val gaps = db.rawQuery("SELECT from_ts, to_ts FROM gaps ORDER BY from_ts", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Span(cursor.getLong(0), cursor.getLong(1))) }
        }
        return Coverage(from, until, gaps)
    }

    override fun events(from: Long, until: Long): List<ScreenEvent> =
        readableDatabase.rawQuery(
            "SELECT ts, type FROM events WHERE ts >= ? AND ts < ? ORDER BY ts, type",
            arrayOf(from.toString(), until.toString()),
        ).use { cursor ->
            buildList(cursor.count) { while (cursor.moveToNext()) add(cursor.event()) }
        }

    override fun lastScreenOffBefore(ts: Long): ScreenEvent? =
        lastBefore(ts, "${EventType.SCREEN_OFF}, ${EventType.SHUTDOWN}, ${EventType.STARTUP}")

    override fun lastKeyguardBefore(ts: Long): ScreenEvent? =
        lastBefore(ts, "${EventType.KEYGUARD_SHOWN}, ${EventType.KEYGUARD_HIDDEN}")

    private fun lastBefore(ts: Long, types: String): ScreenEvent? =
        readableDatabase.rawQuery(
            "SELECT ts, type FROM events WHERE ts < ? AND type IN ($types) ORDER BY ts DESC, type DESC LIMIT 1",
            arrayOf(ts.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.event() else null }

    override fun eventCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    override fun lastReadAt(): Long? = readableDatabase.meta(LAST_READ_AT)

    override fun commit(outcome: ReadOutcome): Int {
        var added = 0
        writableDatabase.transaction {
            compileStatement("INSERT OR IGNORE INTO events (ts, type) VALUES (?, ?)").use { insert ->
                for (event in outcome.events) {
                    insert.bindLong(1, event.ts)
                    insert.bindLong(2, event.type.toLong())
                    added += insert.executeUpdateDelete()
                }
            }
            outcome.gap?.let {
                execSQL("INSERT OR REPLACE INTO gaps (from_ts, to_ts) VALUES (?, ?)", arrayOf(it.start, it.end))
            }
            // The start of the record is written once and stays.
            if (outcome.recordStart != null && meta(RECORD_START) == null) setMeta(RECORD_START, outcome.recordStart)
            outcome.reachesUntil?.let { setMeta(REACHES_UNTIL, it) }
            setMeta(LAST_READ_AT, outcome.readAt)
        }
        return added
    }

    private fun SQLiteDatabase.meta(key: String): Long? =
        rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun SQLiteDatabase.setMeta(key: String, value: Long) {
        execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf<Any>(key, value))
    }

    private fun Cursor.event() = ScreenEvent(getLong(0), getInt(1))

    private companion object {
        const val RECORD_START = "record_start"
        const val REACHES_UNTIL = "reaches_until"
        const val LAST_READ_AT = "last_read_at"
    }
}
