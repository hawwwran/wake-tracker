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
import java.io.File

/**
 * A record of screen events, in SQLite: this phone's own (history.db), or the copy of a
 * paired phone's. The primary key (ts, type) is what makes a repeated read harmless: an
 * event that is already there is skipped.
 */
class SqliteEventStore(context: Context, name: String = OWN_RECORD) :
    SQLiteOpenHelper(context, name, null, 2), EventStore, Outbox, Replica {

    init {
        // Readers (the screen) are not held up by the writer (a read of the log).
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (ts INTEGER NOT NULL, type INTEGER NOT NULL, PRIMARY KEY (ts, type)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE gaps (from_ts INTEGER NOT NULL PRIMARY KEY, to_ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL(CREATE_OUTBOX)
    }

    // Migrations never drop a table: the system keeps its log for ten days, the rest of the
    // record exists only in this file.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2 brought sharing. Everything recorded so far is still to be uploaded.
            db.execSQL(CREATE_OUTBOX)
            db.execSQL(MARK_ALL_PENDING)
        }
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
            val grownDays = HashSet<Long>()
            compileStatement("INSERT OR IGNORE INTO events (ts, type) VALUES (?, ?)").use { insert ->
                for (event in outcome.events) {
                    insert.bindLong(1, event.ts)
                    insert.bindLong(2, event.type.toLong())
                    if (insert.executeUpdateDelete() > 0) {
                        added++
                        grownDays += Math.floorDiv(event.ts, DAY_MS)
                    }
                }
            }
            if (grownDays.isNotEmpty()) {
                val stamp = (meta(OUTBOX_STAMP) ?: 0) + 1
                setMeta(OUTBOX_STAMP, stamp)
                for (day in grownDays) execSQL("INSERT OR REPLACE INTO outbox (day, stamp) VALUES (?, ?)", arrayOf(day, stamp))
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

    override fun pendingDays(): List<PendingDay> =
        readableDatabase.rawQuery("SELECT day, stamp FROM outbox ORDER BY day", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PendingDay(cursor.getLong(0), cursor.getLong(1))) }
        }

    override fun clearPending(day: Long, stamp: Long) {
        writableDatabase.execSQL("DELETE FROM outbox WHERE day = ? AND stamp = ?", arrayOf(day, stamp))
    }

    override fun markAllPending() {
        writableDatabase.execSQL(MARK_ALL_PENDING)
    }

    override fun replaceDay(day: Long, events: List<ScreenEvent>) {
        writableDatabase.transaction {
            execSQL("DELETE FROM events WHERE ts >= ? AND ts < ?", arrayOf(day * DAY_MS, (day + 1) * DAY_MS))
            compileStatement("INSERT OR IGNORE INTO events (ts, type) VALUES (?, ?)").use { insert ->
                for (event in events) {
                    insert.bindLong(1, event.ts)
                    insert.bindLong(2, event.type.toLong())
                    insert.executeInsert()
                }
            }
        }
    }

    override fun setCoverage(coverage: Coverage) {
        writableDatabase.transaction {
            setMeta(RECORD_START, coverage.from)
            setMeta(REACHES_UNTIL, coverage.until)
            execSQL("DELETE FROM gaps")
            for (gap in coverage.gaps) execSQL("INSERT OR REPLACE INTO gaps (from_ts, to_ts) VALUES (?, ?)", arrayOf(gap.start, gap.end))
        }
    }

    override fun cursor(): Long = readableDatabase.meta(RELAY_CURSOR) ?: 0

    override fun advanceCursor(version: Long) {
        writableDatabase.setMeta(RELAY_CURSOR, version)
    }

    private fun SQLiteDatabase.meta(key: String): Long? =
        rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun SQLiteDatabase.setMeta(key: String, value: Long) {
        execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf<Any>(key, value))
    }

    private fun Cursor.event() = ScreenEvent(getLong(0), getInt(1))

    companion object {
        const val OWN_RECORD = "history.db"

        /** Removes a record's file with what SQLite keeps beside it. Close the store first. */
        fun delete(file: File) {
            SQLiteDatabase.deleteDatabase(file)
        }

        private const val RECORD_START = "record_start"
        private const val REACHES_UNTIL = "reaches_until"
        private const val LAST_READ_AT = "last_read_at"
        private const val OUTBOX_STAMP = "outbox_stamp"
        private const val RELAY_CURSOR = "relay_cursor"

        private const val CREATE_OUTBOX = "CREATE TABLE outbox (day INTEGER NOT NULL PRIMARY KEY, stamp INTEGER NOT NULL)"

        // Stamp 0 is below every stamp a commit hands out, so a day that gains events
        // while this upload is on its way is not cleared by it.
        private const val MARK_ALL_PENDING = "INSERT OR REPLACE INTO outbox (day, stamp) SELECT DISTINCT ts / $DAY_MS, 0 FROM events"
    }
}
