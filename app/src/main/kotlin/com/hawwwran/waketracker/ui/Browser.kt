package com.hawwwran.waketracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.data.Preferences
import com.hawwwran.waketracker.model.DayClock
import com.hawwwran.waketracker.model.Period
import com.hawwwran.waketracker.model.PeriodKind
import com.hawwwran.waketracker.model.PeriodStats
import com.hawwwran.waketracker.share.PairedDevice
import com.hawwwran.waketracker.share.syncLines
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The record, a period at a time: pick day, week, workweek or weekend, then swipe (or use the
 * arrows) back through time. Tapping a day in one of the longer periods opens that day; the
 * back button returns from it.
 */
@Composable
fun Browser(model: MainViewModel, needsAttention: Boolean, onOpenStatus: () -> Unit) {
    val record by model.record.collectAsStateWithLifecycle()
    val shared by model.sharing.state.collectAsStateWithLifecycle()
    val preferences by model.preferences.values.collectAsStateWithLifecycle()
    val formats = rememberFormats()

    val clock = remember(formats.zone, preferences.dayStartHour) { DayClock(formats.zone, preferences.dayStartHour) }
    val today = remember(clock, record.revision) { clock.dayOf(System.currentTimeMillis()) }
    val firstDay = remember(clock, record.coverage?.from) { record.coverage?.from?.let(clock::dayOf) }

    var kind by rememberSaveable { mutableStateOf(PeriodKind.DAY) }
    // A day inside the period on show. Survives a change of kind, so "week" opens the week of the day that was shown.
    var anchor by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    // Where "back" returns to after a day was opened from a longer period.
    var cameFrom by rememberSaveable { mutableStateOf<Pair<PeriodKind, Long>?>(null) }

    BackHandler(enabled = cameFrom != null) {
        cameFrom?.let { (previousKind, previousAnchor) ->
            kind = previousKind
            anchor = previousAnchor
        }
        cameFrom = null
    }

    val latest = Period.latest(kind, today)
    val first = Period.containing(kind, firstDay ?: today).let { if (it.firstDay > latest.firstDay) latest else it }
    val count = latest.stepsFrom(first) + 1
    val shown = Period.containing(kind, LocalDate.ofEpochDay(anchor)).let {
        when {
            it.firstDay < first.firstDay -> first
            it.firstDay > latest.firstDay -> latest
            else -> it
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shared.paired.isEmpty()) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalPalette.current.muted,
                    modifier = Modifier.weight(1f),
                )
            } else {
                SourceTabs(shared.paired, record.source, model::select, Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = onOpenStatus) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.open_status))
                }
                if (needsAttention) {
                    Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(8.dp).background(Amber, CircleShape))
                }
            }
        }

        shared.paired.firstOrNull { it.deviceId == record.source }?.let { device ->
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) {
                for (line in syncLines(device, formats)) {
                    Text(line, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted)
                }
            }
        }

        KindSelector(kind) {
            kind = it
            cameFrom = null
        }

        // A new pager whenever the set of pages changes: another kind, a record that now
        // starts earlier, a new day. It opens on the period that holds the anchor.
        key(kind, first.firstDay, count) {
            val pager = rememberPagerState(initialPage = shown.stepsFrom(first)) { count }
            val scope = rememberCoroutineScope()
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { page ->
                    anchor = minOf((first + page).lastDay, today).toEpochDay()
                }
            }

            PeriodNavigator(
                period = first + pager.currentPage,
                today = today,
                formats = formats,
                canGoBack = pager.currentPage > 0,
                canGoForward = pager.currentPage < count - 1,
                onStep = { steps -> scope.launch { pager.animateScrollToPage(pager.currentPage + steps) } },
                onLatest = { scope.launch { pager.animateScrollToPage(count - 1) } },
            )

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                key = { (first + it).firstDay.toEpochDay() },
            ) { page ->
                PeriodPage(
                    period = first + page,
                    today = today,
                    model = model,
                    source = record.source,
                    preferences = preferences,
                    revision = record.revision,
                    formats = formats,
                    onOpenDay = { day ->
                        cameFrom = kind to anchor
                        kind = PeriodKind.DAY
                        anchor = day.toEpochDay()
                    },
                )
            }
        }
    }
}

/**
 * Whose record is on show: this phone's, or one of the phones paired with it. Only there
 * once a phone is paired.
 */
@Composable
private fun SourceTabs(paired: List<PairedDevice>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val tabs = listOf<Pair<String?, String>>(null to stringResource(R.string.this_phone)) + paired.map { it.deviceId to it.name }
        for ((source, name) in tabs) {
            val isSelected = source == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Tab) { onSelect(source) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .width(IntrinsicSize.Max),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) palette.ink else palette.muted,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) palette.lit else Color.Transparent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun KindSelector(selected: PeriodKind, onSelect: (PeriodKind) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (kind in PeriodKind.entries) {
            val isSelected = kind == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) palette.ground else palette.track)
                    .clickable(role = Role.Tab) { onSelect(kind) }
                    .semantics { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(kind.label()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) palette.ink else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun PeriodKind.label(): Int = when (this) {
    PeriodKind.DAY -> R.string.kind_day
    PeriodKind.WEEK -> R.string.kind_week
    PeriodKind.WORKWEEK -> R.string.kind_workweek
    PeriodKind.WEEKEND -> R.string.kind_weekend
}

@Composable
private fun PeriodNavigator(
    period: Period,
    today: LocalDate,
    formats: Formats,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onStep: (Int) -> Unit,
    onLatest: () -> Unit,
) {
    val palette = LocalPalette.current
    val title = if (period.kind == PeriodKind.DAY) formats.dayTitle(period.firstDay) else formats.range(period.firstDay, period.lastDay)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.earlier))
        }
        // The title is also the way back to now from anywhere in the past.
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canGoForward, onClickLabel = stringResource(R.string.go_to_latest), onClick = onLatest)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink, textAlign = TextAlign.Center, maxLines = 1)
            Text(
                relativeName(period, today) ?: if (canGoForward) stringResource(R.string.go_to_latest) else "",
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
                maxLines = 1,
            )
        }
        IconButton(onClick = { onStep(1) }, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.later))
        }
    }
}

@Composable
private fun relativeName(period: Period, today: LocalDate): String? {
    val stepsBack = Period.containing(period.kind, today).stepsFrom(period)
    val name = when (period.kind) {
        PeriodKind.DAY -> when (stepsBack) {
            0 -> R.string.today
            1 -> R.string.yesterday
            else -> null
        }

        PeriodKind.WEEK, PeriodKind.WORKWEEK -> when (stepsBack) {
            0 -> R.string.this_week
            1 -> R.string.last_week
            else -> null
        }

        PeriodKind.WEEKEND -> when (stepsBack) {
            0 -> R.string.this_weekend
            1 -> R.string.last_weekend
            else -> null
        }
    }
    return name?.let { stringResource(it) }
}

@Composable
private fun PeriodPage(
    period: Period,
    today: LocalDate,
    model: MainViewModel,
    source: String?,
    preferences: Preferences.Values,
    revision: Int,
    formats: Formats,
    onOpenDay: (LocalDate) -> Unit,
) {
    // Keeps showing what it has while a newer read is being worked in, so nothing flickers.
    val stats by produceState<PeriodStats?>(initialValue = null, source, period, preferences, revision, formats.zone) {
        value = model.stats(source, period, preferences, formats.zone)
    }
    val loaded = stats ?: return
    // Only this phone's own record is known up to this minute. A paired phone's ends where
    // its last upload did, so a stretch still running there has an end time, not "now".
    val live = source == null
    if (period.kind == PeriodKind.DAY) {
        DayPage(loaded.days.first(), live, formats)
    } else {
        DaysPage(loaded, today, live, preferences, formats, onOpenDay)
    }
}
