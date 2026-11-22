package com.hawwwran.waketracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.hawwwran.waketracker.model.DayStats
import com.hawwwran.waketracker.model.Session
import com.hawwwran.waketracker.model.Span
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A full hour of a day: where it falls across the day's width, and which hour it is. */
data class HourTick(val fraction: Float, val hour: Int)

/** The hours of the clock divisible by [every] within [bounds], the day's own ends included. */
fun hourTicks(bounds: Span, zone: ZoneId, every: Int): List<HourTick> {
    val ticks = ArrayList<HourTick>()
    var hour = Instant.ofEpochMilli(bounds.start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
    while (true) {
        val ts = hour.toInstant().toEpochMilli()
        if (ts > bounds.end) break
        if (ts >= bounds.start && hour.hour % every == 0) {
            ticks += HourTick(((ts - bounds.start).toDouble() / bounds.duration).toFloat(), hour.hour)
        }
        hour = hour.plusHours(1)
    }
    return ticks
}

/**
 * One day as a strip of time, left to right. The track is solid only where the record
 * covers the day and faint elsewhere, so a hole in it, and the rest of today, show as such.
 * Use is drawn at full height, a wake that stayed on the lock screen lower and dimmer: two
 * differences, so it does not hang on colour.
 */
@Composable
fun DayStrip(day: DayStats, ticks: List<HourTick>, modifier: Modifier = Modifier, selected: Session? = null) {
    val palette = LocalPalette.current
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        val width = size.width
        val height = size.height
        val perMilli = width / day.bounds.duration
        fun x(ts: Long) = (ts - day.bounds.start) * perMilli

        drawRect(palette.track.copy(alpha = 0.4f))
        for (known in day.known) {
            drawRect(palette.track, Offset(x(known.start), 0f), Size(x(known.end) - x(known.start), height))
        }
        for (tick in ticks) {
            if (tick.fraction <= 0f || tick.fraction >= 1f) continue
            val at = tick.fraction * width
            drawLine(palette.hairline, Offset(at, 0f), Offset(at, height), strokeWidth = 1f)
        }

        // A few seconds are far less than a pixel of a day; a hairline keeps them visible.
        val thinnest = 1.dp.toPx()
        for (session in day.sessions) {
            val left = x(session.start)
            val markWidth = maxOf(x(session.end) - left, thinnest)
            val top = if (session.unlocked) 0f else height * 0.5f
            val colour = if (session.unlocked) palette.lit else palette.litDim
            drawRect(colour, Offset(left, top), Size(markWidth, height - top))
        }

        if (selected != null) {
            val left = x(selected.start)
            val markWidth = maxOf(x(selected.end) - left, thinnest)
            drawRect(palette.ink, Offset(left, height - 3.dp.toPx()), Size(markWidth, 3.dp.toPx()))
        }
    }
}

/** The hours under (or over) a strip. Its width must be the strip's. */
@Composable
fun HourAxis(ticks: List<HourTick>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = palette.muted, fontFeatureSettings = "tnum")
    val height = with(LocalDensity.current) { style.lineHeight.toDp() }
    Canvas(modifier.fillMaxWidth().height(height)) {
        var freeFrom = 0f
        for (tick in ticks) {
            val label = measurer.measure("%02d".format(tick.hour), style)
            val labelWidth = label.size.width.toFloat()
            val left = (tick.fraction * size.width - labelWidth / 2).coerceIn(0f, maxOf(0f, size.width - labelWidth))
            // On a narrow strip the last label would run into the one before it.
            if (left < freeFrom) continue
            drawText(label, topLeft = Offset(left, 0f))
            freeFrom = left + labelWidth + 4.dp.toPx()
        }
    }
}
