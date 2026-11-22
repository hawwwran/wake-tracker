package com.hawwwran.waketracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The one large figure of a page: how long the screen was on. */
@Composable
fun Hero(millis: Long, caption: String) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            Durations.styled(millis, SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = palette.muted)),
            style = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            color = palette.ink,
            maxLines = 1,
            softWrap = false,
        )
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.muted)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = palette.ink, maxLines = 1)
    }
}

@Composable
fun StatRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), content = content)
}

/** A page's message when there is nothing to show for it. */
@Composable
fun Notice(title: String, text: String) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = palette.ink)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * The three columns every line of a multi-day page shares: day name, chart, figure. One
 * frame for the hour axis and for each day, so the hours sit exactly over the charts.
 */
@Composable
fun DayRowFrame(label: @Composable () -> Unit, figure: @Composable () -> Unit, chart: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(60.dp)) { label() }
        Box(Modifier.weight(1f)) { chart() }
        Box(Modifier.width(92.dp), contentAlignment = Alignment.CenterEnd) { figure() }
    }
}

const val MISSING = "–"
