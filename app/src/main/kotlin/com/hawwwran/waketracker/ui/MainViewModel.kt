package com.hawwwran.waketracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.waketracker.WakeTrackerApp
import com.hawwwran.waketracker.data.Background
import com.hawwwran.waketracker.data.EventStore
import com.hawwwran.waketracker.data.History
import com.hawwwran.waketracker.data.Preferences
import com.hawwwran.waketracker.data.UsageAccess
import com.hawwwran.waketracker.model.Coverage
import com.hawwwran.waketracker.model.DayClock
import com.hawwwran.waketracker.model.Period
import com.hawwwran.waketracker.model.PeriodStats
import com.hawwwran.waketracker.model.Stats
import com.hawwwran.waketracker.share.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Where a record stands. [source] says whose it is: null for this phone's own, otherwise
 * the id of a paired phone. [revision] changes with every read of the log and every fetch
 * from the relay; what was computed from the record is stale then.
 */
data class RecordState(
    val source: String? = null,
    val coverage: Coverage? = null,
    val lastReadAt: Long? = null,
    val events: Long = 0,
    val failure: String? = null,
    val revision: Int = 0,
    /** When the hourly job last ran; this phone's own record only. 0: not yet. */
    val lastBackgroundRun: Long = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WakeTrackerApp

    val preferences: Preferences get() = app.preferences
    val sharing: Sharing get() = app.sharing

    private val access = MutableStateFlow(UsageAccess.granted(app))
    val hasAccess: StateFlow<Boolean> = access

    private val background = MutableStateFlow(Background.unrestricted(app))
    val runsUnrestricted: StateFlow<Boolean> = background

    /** Whose record the first page shows: null for this phone's, or a paired phone's id. */
    private val selected = MutableStateFlow<String?>(null)

    private val shownRecord = MutableStateFlow(RecordState())
    val record: StateFlow<RecordState> = shownRecord

    private val own = MutableStateFlow(RecordState())
    val ownRecord: StateFlow<RecordState> = own

    private var revision = 0

    init {
        viewModelScope.launch {
            combine(app.reads, sharing.pulls, selected, sharing.state) { _, _, source, shared ->
                // A phone that was unpaired while its tab was open: back to this phone's own.
                source?.takeIf { id -> shared.paired.any { it.deviceId == id } }
            }.collect { source ->
                selected.value = source
                revision++
                own.value = withContext(Dispatchers.IO) { load(null) }
                shownRecord.value = if (source == null) own.value else withContext(Dispatchers.IO) { load(source) }
            }
        }
    }

    fun select(source: String?) {
        selected.value = source
        if (source != null) viewModelScope.launch(Dispatchers.IO) { sharing.pull(source) }
    }

    /** Called when the screen comes to the front, and every minute while it stays there. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            access.value = UsageAccess.granted(app)
            background.value = Background.unrestricted(app)
            app.readAndShare()
            selected.value?.let(sharing::pull)
        }
    }

    suspend fun stats(source: String?, period: Period, preferences: Preferences.Values, zone: ZoneId): PeriodStats =
        withContext(Dispatchers.IO) {
            val clock = DayClock(zone, preferences.dayStartHour)
            // A paired phone removed while its page was still being drawn has no record any more.
            val store = storeOf(source)
            val coverage = store?.coverage()
            val sessions = if (store == null || coverage == null) {
                emptyList()
            } else {
                History(store).sessions(clock.startOf(period.firstDay), clock.endOf(period.lastDay), coverage)
            }
            Stats.period(period, clock, sessions, coverage, preferences.countLockScreen)
        }

    private fun storeOf(source: String?): EventStore? = if (source == null) app.store else sharing.copyOf(source)

    private fun load(source: String?): RecordState {
        val store = storeOf(source) ?: return RecordState(source = source, revision = revision)
        return RecordState(
            source = source,
            coverage = store.coverage(),
            lastReadAt = if (source == null) store.lastReadAt() else null,
            events = store.eventCount(),
            failure = if (source == null) app.lastFailure else null,
            revision = revision,
            lastBackgroundRun = if (source == null) app.preferences.lastBackgroundRun else 0,
        )
    }
}
