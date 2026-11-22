package com.hawwwran.waketracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.data.Preferences
import com.hawwwran.waketracker.model.DayStats
import com.hawwwran.waketracker.model.PeriodStats
import java.time.LocalDate

/**
 * A week, workweek or weekend: its total and averages, then a line per day with the day as
 * a chart. The charts share one hour axis, so the days stack into a picture of when the
 * phone wakes and when it rests.
 */
@Composable
fun DaysPage(
    stats: PeriodStats,
    today: LocalDate,
    live: Boolean,
    preferences: Preferences.Values,
    formats: Formats,
    onOpenDay: (LocalDate) -> Unit,
) {
    val ticks = remember(stats.days.first().bounds, formats.zone) { hourTicks(stats.days.first().bounds, formats.zone, every = 6) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
    ) {
        item(key = "summary") { PeriodSummary(stats, preferences, formats) }
        if (!stats.hasData) return@LazyColumn

        item(key = "axis") {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 28.dp)) {
                DayRowFrame(label = {}, figure = {}) { HourAxis(ticks) }
            }
        }
        // Days still to come have nothing to show yet.
        items(stats.days.filter { it.day <= today }, key = { it.day.toEpochDay() }) { day ->
            DayRow(day, ticks, live, formats, onClick = { onOpenDay(day.day) })
        }
    }
}

@Composable
private fun PeriodSummary(stats: PeriodStats, preferences: Preferences.Values, formats: Formats) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        if (!stats.hasData) {
            Notice(stringResource(R.string.no_record_title), stringResource(R.string.no_record_period_text))
            return
        }
        Hero(stats.total, pluralStringResource(R.plurals.screen_on_in_days, stats.recordedDays, stats.recordedDays))
        StatRow {
            Stat(stringResource(R.string.daily_average), stats.dailyAverage?.let(Durations::text) ?: MISSING, Modifier.weight(1f))
            Stat(stringResource(R.string.times_on_a_day), stats.dailyTimesOn?.toString() ?: MISSING, Modifier.weight(1f))
        }
        StatRow {
            Stat(
                stringResource(R.string.first_on_average),
                stats.averageFirstOn?.let { formats.clockAfter(preferences.dayStartHour, it) } ?: MISSING,
                Modifier.weight(1f),
            )
            Stat(
                stringResource(R.string.last_on_average),
                stats.averageLastOn?.let { formats.clockAfter(preferences.dayStartHour, it) } ?: MISSING,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayRow(day: DayStats, ticks: List<HourTick>, live: Boolean, formats: Formats, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = day.hasData, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        DayRowFrame(
            label = { Text(formats.dayShort(day.day), style = MaterialTheme.typography.bodyMedium, color = palette.ink) },
            figure = {
                Text(
                    if (day.hasData) Durations.text(day.total) else MISSING,
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    fontWeight = FontWeight.Medium,
                    color = palette.ink,
                )
            },
        ) {
            if (day.hasData) {
                DayStrip(day, ticks, Modifier.fillMaxWidth().height(22.dp))
            } else {
                Text(stringResource(R.string.no_record), style = MaterialTheme.typography.labelMedium, color = palette.muted)
            }
        }
        if (day.hasData) {
            DayRowFrame(
                label = {},
                figure = { Text("${day.timesOn}×", style = MaterialTheme.typography.labelMedium.tabular(), color = palette.muted) },
            ) {
                Text(
                    firstToLast(day, live, formats),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun firstToLast(day: DayStats, live: Boolean, formats: Formats): String {
    val first = day.firstOn ?: return stringResource(R.string.screen_stayed_off)
    val last = if (live && day.ongoing) stringResource(R.string.now) else formats.clock(day.lastOn ?: first)
    return "${formats.clock(first)} – $last"
}
