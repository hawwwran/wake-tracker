package com.hawwwran.waketracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.model.DayStats
import com.hawwwran.waketracker.model.Session
import kotlinx.coroutines.launch

/**
 * One day: its total, first and last time on, the day as a chart and every stretch as a
 * line. The chart stays in place while the lines scroll; tapping a stretch on either marks
 * it on the other.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayPage(day: DayStats, live: Boolean, formats: Formats) {
    var picked by remember(day.day) { mutableStateOf<Session?>(null) }
    // A newer read can lengthen the running stretch; a mark on what is no longer there is dropped.
    val selected = picked?.takeIf { it in day.sessions }
    val ticks = remember(day.bounds, formats.zone) { hourTicks(day.bounds, formats.zone, every = 4) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var chartHeight by remember { mutableIntStateOf(0) }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
    ) {
        item(key = "summary") { DaySummary(day, live, formats) }
        if (!day.hasData) return@LazyColumn

        stickyHeader(key = "chart") {
            DayChart(
                day = day,
                ticks = ticks,
                selected = selected,
                formats = formats,
                modifier = Modifier.onSizeChanged { chartHeight = it.height },
                onPick = { session ->
                    picked = session
                    val index = day.sessions.indexOf(session)
                    // Two items sit above the stretches; the offset keeps the line clear of the pinned chart.
                    if (index >= 0) scope.launch { list.animateScrollToItem(index + 2, -chartHeight) }
                },
            )
        }
        itemsIndexed(day.sessions, key = { _, session -> session.start }) { index, session ->
            SessionRow(
                session = session,
                isSelected = session == selected,
                stillOn = live && day.ongoing && index == day.sessions.lastIndex,
                formats = formats,
                onClick = { picked = if (session == selected) null else session },
            )
        }
    }
}

@Composable
private fun DaySummary(day: DayStats, live: Boolean, formats: Formats) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        if (!day.hasData) {
            Notice(stringResource(R.string.no_record_title), stringResource(R.string.no_record_day_text))
            return
        }
        Hero(day.total, stringResource(R.string.screen_on))
        StatRow {
            Stat(stringResource(R.string.first_on), day.firstOn?.let(formats::clock) ?: MISSING, Modifier.weight(1f))
            Stat(
                stringResource(R.string.last_on),
                if (live && day.ongoing) stringResource(R.string.now) else day.lastOn?.let(formats::clock) ?: MISSING,
                Modifier.weight(1f),
            )
            Stat(stringResource(R.string.times_on), day.timesOn.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun DayChart(
    day: DayStats,
    ticks: List<HourTick>,
    selected: Session?,
    formats: Formats,
    modifier: Modifier,
    onPick: (Session?) -> Unit,
) {
    val palette = LocalPalette.current
    val description = stringResource(
        R.string.chart_description,
        Durations.text(day.total),
        day.firstOn?.let(formats::clock) ?: MISSING,
        day.lastOn?.let(formats::clock) ?: MISSING,
    )
    Column(modifier.fillMaxWidth().background(palette.ground).padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            if (selected == null) {
                Text(stringResource(R.string.chart_hint), style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 1)
            } else {
                Text(
                    "${formats.clock(selected.start)} – ${formats.clock(selected.end)}",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(Durations.text(selected.duration), style = MaterialTheme.typography.bodyMedium, color = palette.ink)
            }
        }
        DayStrip(
            day = day,
            ticks = ticks,
            selected = selected,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = description }
                .pointerInput(day) {
                    detectTapGestures { tap ->
                        onPick(day.sessionNear(tap.x / size.width, reach = 14.dp.toPx() / size.width))
                    }
                },
        )
        HourAxis(ticks, Modifier.padding(top = 4.dp))

        if (day.sessions.any { !it.unlocked }) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendEntry(stringResource(R.string.legend_in_use), unlocked = true)
                LegendEntry(stringResource(R.string.legend_lock_screen), unlocked = false)
            }
        }
        if (day.missing.isNotEmpty()) {
            val stretches = day.missing.joinToString(", ") { "${formats.clock(it.start)} – ${formats.clock(it.end)}" }
            Text(
                stringResource(R.string.not_recorded, stretches),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** The stretch at [fraction] of the day's width, or the nearest one within [reach]; a fingertip is wider than most stretches. */
private fun DayStats.sessionNear(fraction: Float, reach: Float): Session? {
    val ts = bounds.start + (fraction.toDouble() * bounds.duration).toLong()
    val reachMillis = (reach.toDouble() * bounds.duration).toLong()
    fun distance(session: Session) = when {
        ts < session.start -> session.start - ts
        ts > session.end -> ts - session.end
        else -> 0L
    }
    return sessions.minByOrNull(::distance)?.takeIf { distance(it) <= reachMillis }
}

/** A swatch shaped like the chart's own mark: full height for use, half for the lock screen. */
@Composable
private fun Mark(unlocked: Boolean) {
    val palette = LocalPalette.current
    Box(Modifier.size(width = 6.dp, height = 18.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .width(6.dp)
                .height(if (unlocked) 18.dp else 9.dp)
                .background(if (unlocked) palette.lit else palette.litDim, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun LegendEntry(label: String, unlocked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark(unlocked)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalPalette.current.muted,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun SessionRow(session: Session, isSelected: Boolean, stillOn: Boolean, formats: Formats, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) palette.track else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mark(session.unlocked)
        Spacer(Modifier.width(12.dp))
        val end = if (stillOn) stringResource(R.string.now) else formats.clock(session.end)
        Text("${formats.clock(session.start)} – $end", style = MaterialTheme.typography.bodyLarge.tabular(), color = palette.ink)
        if (!session.unlocked) {
            Text(
                stringResource(R.string.lock_screen),
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(Durations.text(session.duration), style = MaterialTheme.typography.bodyLarge.tabular(), color = palette.ink)
    }
}

/** Digits of one width, for figures that stand in a column. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
