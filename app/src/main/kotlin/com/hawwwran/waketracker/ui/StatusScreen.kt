package com.hawwwran.waketracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hawwwran.waketracker.BuildConfig
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.data.Background
import com.hawwwran.waketracker.data.Preferences
import com.hawwwran.waketracker.data.UsageAccess
import com.hawwwran.waketracker.share.SharingSection

/**
 * Where the record stands and how it is counted. Everything here is about reading the
 * record; nothing on this page, or anywhere in the app, changes what was recorded.
 */
@Composable
fun StatusScreen(model: MainViewModel, onBack: () -> Unit, updates: @Composable () -> Unit) {
    val palette = LocalPalette.current
    // Always this phone's own record, also while the first page shows a paired phone's.
    val record by model.ownRecord.collectAsStateWithLifecycle()
    val preferences by model.preferences.values.collectAsStateWithLifecycle()
    val hasAccess by model.hasAccess.collectAsStateWithLifecycle()
    val runsUnrestricted by model.runsUnrestricted.collectAsStateWithLifecycle()
    val formats = rememberFormats()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium, color = palette.ink)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Section(stringResource(R.string.record_heading))
            Fact(stringResource(R.string.usage_access), stringResource(if (hasAccess) R.string.allowed else R.string.not_allowed))
            if (!hasAccess) {
                Button(onClick = { UsageAccess.openSettings(context) }, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.access_open_settings))
                }
                RestrictedSettingHelp(Modifier.padding(top = 8.dp, bottom = 12.dp))
            }
            Fact(stringResource(R.string.record_starts), record.coverage?.from?.let(formats::dateTime) ?: MISSING)
            Fact(stringResource(R.string.record_reaches), record.coverage?.until?.let(formats::dateTime) ?: MISSING)
            Fact(stringResource(R.string.last_read), record.lastReadAt?.let(formats::dateTime) ?: MISSING)
            Fact(stringResource(R.string.events_stored), "%,d".format(record.events))

            val gaps = record.coverage?.gaps.orEmpty()
            Fact(
                stringResource(R.string.gaps),
                if (gaps.isEmpty()) stringResource(R.string.gaps_none) else pluralStringResource(R.plurals.gaps_count, gaps.size, gaps.size),
            )
            for (gap in gaps) {
                Text(
                    "${formats.dateTime(gap.start)} – ${formats.dateTime(gap.end)}",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            record.failure?.let {
                Text(
                    stringResource(R.string.read_failed, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Explanation(stringResource(R.string.record_explained))

            Section(stringResource(R.string.background_heading))
            Fact(
                stringResource(R.string.background_last_run),
                if (record.lastBackgroundRun == 0L) stringResource(R.string.background_not_yet) else formats.dateTime(record.lastBackgroundRun),
            )
            Fact(
                stringResource(R.string.background_battery),
                stringResource(if (runsUnrestricted) R.string.background_unrestricted else R.string.background_restricted),
            )
            if (!runsUnrestricted) {
                Button(onClick = { Background.askToBeUnrestricted(context) }, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.background_allow))
                }
            }
            Explanation(stringResource(R.string.background_explained))

            Section(stringResource(R.string.counting_heading))
            Text(stringResource(R.string.day_starts_at), style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            HourChoice(preferences.dayStartHour, model.preferences::setDayStartHour)
            Explanation(stringResource(R.string.day_starts_at_explained))

            Row(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Switch) { model.preferences.setCountLockScreen(!preferences.countLockScreen) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.count_lock_screen),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = preferences.countLockScreen, onCheckedChange = null)
            }
            Explanation(stringResource(R.string.count_lock_screen_explained))

            SharingSection(model.sharing, formats)

            Section(stringResource(R.string.app_heading))
            Fact(stringResource(R.string.version), BuildConfig.VERSION_NAME)
            updates()
        }
    }
}

@Composable
fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = LocalPalette.current.ink,
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
    )
}

@Composable
private fun Fact(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge.tabular(), color = palette.ink)
    }
}

@Composable
fun Explanation(text: String, top: Int = 10) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalPalette.current.muted,
        modifier = Modifier.padding(top = top.dp),
    )
}

@Composable
private fun HourChoice(selected: Int, onSelect: (Int) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (hour in Preferences.DAY_START_HOURS) {
            val isSelected = hour == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) palette.ground else palette.track)
                    .clickable(role = Role.RadioButton) { onSelect(hour) }
                    .semantics { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "%d:00".format(hour),
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) palette.ink else palette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}
