package com.hawwwran.waketracker

import android.app.Application
import android.util.Log
import com.hawwwran.waketracker.data.History
import com.hawwwran.waketracker.data.Preferences
import com.hawwwran.waketracker.data.SqliteEventStore
import com.hawwwran.waketracker.data.SyncJob
import com.hawwwran.waketracker.data.Synchronizer
import com.hawwwran.waketracker.data.SystemUsageLog
import com.hawwwran.waketracker.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wake Tracker shows when the phone's screen was on. It does not watch the screen itself:
 * Android records every switch in its usage log, and the app copies that log into its own
 * database (data/Synchronizer.kt says why that is the reliable way round). The copy is only
 * ever added to; the screens read it and change nothing.
 */
class WakeTrackerApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var store: SqliteEventStore
        private set
    lateinit var history: History
        private set
    lateinit var preferences: Preferences
        private set
    lateinit var updater: Updater
        private set
    private lateinit var synchronizer: Synchronizer

    /** Goes up after every read of the log; what shows the record loads it again. */
    private val readCount = MutableStateFlow(0)
    val reads: StateFlow<Int> = readCount

    /** Why the last read failed, for the record's status page; null when it worked. */
    @Volatile
    var lastFailure: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        store = SqliteEventStore(this)
        history = History(store)
        preferences = Preferences(this)
        synchronizer = Synchronizer(SystemUsageLog(this), store)
        updater = Updater(this, scope)

        SyncJob.schedule(this)
        scope.launch(Dispatchers.IO) { readLog() }
    }

    /** Brings the record up to date. Blocking; call it off the main thread. */
    fun readLog(): Synchronizer.Result? {
        val result = try {
            synchronizer.sync().also { lastFailure = null }
        } catch (e: RuntimeException) {
            // Wide on purpose: whatever the log or the database throws must not end a
            // background process. The record's status page shows it instead.
            Log.w(TAG, "reading the usage log failed", e)
            lastFailure = e.message ?: e.javaClass.simpleName
            null
        }
        readCount.update { it + 1 }
        return result
    }

    private companion object {
        const val TAG = "WakeTracker"
    }
}
