package com.hawwwran.waketracker.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the record is read, never what is in it: both settings can be changed back and forth
 * and the stored events stay as they are.
 */
class Preferences(context: Context) {
    data class Values(
        /** The hour a day starts at; time after midnight and before it belongs to the day before. */
        val dayStartHour: Int,
        /** Whether a wake that never left the lock screen counts as the screen being on. */
        val countLockScreen: Boolean,
    )

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        Values(
            dayStartHour = prefs.getInt(DAY_START_HOUR, DEFAULT_DAY_START_HOUR).coerceIn(DAY_START_HOURS),
            countLockScreen = prefs.getBoolean(COUNT_LOCK_SCREEN, true),
        ),
    )
    val values: StateFlow<Values> = state

    fun setDayStartHour(hour: Int) {
        val valid = hour.coerceIn(DAY_START_HOURS)
        prefs.edit { putInt(DAY_START_HOUR, valid) }
        state.value = state.value.copy(dayStartHour = valid)
    }

    fun setCountLockScreen(count: Boolean) {
        prefs.edit { putBoolean(COUNT_LOCK_SCREEN, count) }
        state.value = state.value.copy(countLockScreen = count)
    }

    companion object {
        private const val DAY_START_HOUR = "day_start_hour"
        private const val COUNT_LOCK_SCREEN = "count_lock_screen"

        // 04:00: late enough that an evening running past midnight stays one day, early
        // enough that an early riser's morning is not cut in two.
        const val DEFAULT_DAY_START_HOUR = 4
        val DAY_START_HOURS = 0..6
    }
}
