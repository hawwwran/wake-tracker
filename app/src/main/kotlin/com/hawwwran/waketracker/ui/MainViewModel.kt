package com.hawwwran.waketracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.waketracker.WakeTrackerApp
import com.hawwwran.waketracker.data.Preferences
import com.hawwwran.waketracker.data.UsageAccess
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.DayClock
import com.hawwwran.waketracker.model.Period
import com.hawwwran.waketracker.model.PeriodStats
import com.hawwwran.waketracker.model.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Where the record stands. [revision] changes with every read of the log; what was computed from the record is stale then. */
data class RecordState(
    val coverage: Coverage? = null,
    val lastReadAt: Long? = null,
    val events: Long = 0,
    val failure: String? = null,
    val revision: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WakeTrackerApp

    val preferences: Preferences get() = app.preferences

    private val access = MutableStateFlow(UsageAccess.granted(app))
    val hasAccess: StateFlow<Boolean> = access

    private val recordState = MutableStateFlow(RecordState())
    val record: StateFlow<RecordState> = recordState

    init {
        viewModelScope.launch {
            app.reads.collect { revision ->
                recordState.value = withContext(Dispatchers.IO) {
                    RecordState(app.store.coverage(), app.store.lastReadAt(), app.store.eventCount(), app.lastFailure, revision)
                }
            }
        }
    }

    /** Called when the screen comes to the front, and every minute while it stays there. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            access.value = UsageAccess.granted(app)
            app.readLog()
        }
    }

    suspend fun stats(period: Period, preferences: Preferences.Values, zone: ZoneId): PeriodStats = withContext(Dispatchers.IO) {
        val clock = DayClock(zone, preferences.dayStartHour)
        val coverage = app.store.coverage()
        val sessions = if (coverage == null) {
            emptyList()
        } else {
            app.history.sessions(clock.startOf(period.firstDay), clock.endOf(period.lastDay), coverage)
        }
        Stats.period(period, clock, sessions, coverage, preferences.countLockScreen)
    }
}
