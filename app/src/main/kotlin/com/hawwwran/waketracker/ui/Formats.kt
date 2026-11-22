package com.hawwwran.waketracker.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates and times the way the phone's language and clock setting write them. */
class Formats(val zone: ZoneId, private val locale: Locale, is24Hour: Boolean) {
    private val clock = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

    private fun pattern(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    /** The year is written only when it is not the present one. */
    private fun withYear(day: LocalDate, skeleton: String) = pattern(if (day.year == LocalDate.now(zone).year) skeleton else skeleton + "y")

    fun clock(ts: Long): String = clock.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** A time of day given as milliseconds after a day's start, for averages. */
    fun clockAfter(dayStartHour: Int, millis: Long): String =
        clock.format(LocalTime.of(dayStartHour, 0).plusSeconds(millis / 1000))

    /** "Sunday 22 November" */
    fun dayTitle(day: LocalDate): String = withYear(day, "EEEEdMMMM").format(day)

    /** "Sun 22" */
    fun dayShort(day: LocalDate): String = pattern("EEEd").format(day)

    /** "16 – 22 November", "30 Nov – 6 Dec" */
    fun range(first: LocalDate, last: LocalDate): String {
        val end = withYear(last, if (first.month == last.month) "dMMMM" else "dMMM").format(last)
        val start = pattern(if (first.month == last.month) "d" else "dMMM").format(first)
        return "$start – $end"
    }

    /** "22 Nov 16:40" */
    fun dateTime(ts: Long): String {
        val moment = Instant.ofEpochMilli(ts).atZone(zone)
        return withYear(moment.toLocalDate(), "dMMM").format(moment) + " " + clock.format(moment)
    }
}

@Composable
fun rememberFormats(): Formats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    // Read on every pass: the phone can change its time zone while the app is open.
    val zone = ZoneId.systemDefault()
    return remember(zone, locale, is24Hour) { Formats(zone, locale, is24Hour) }
}

object Durations {
    private const val NBSP = '\u00A0'

    /** "3 h 42 min", "42 min", "38 s". Whole minutes from a minute up: nobody reads a day's total to the second. */
    fun text(millis: Long): String = parts(millis).joinToString(" ") { (number, unit) -> "$number$NBSP$unit" }

    /** The same with the units set in [unitStyle], so a large figure reads as its digits. */
    fun styled(millis: Long, unitStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
        parts(millis).forEachIndexed { index, (number, unit) ->
            if (index > 0) append(' ')
            append(number)
            withStyle(unitStyle) { append("$NBSP$unit") }
        }
    }

    private fun parts(millis: Long): List<Pair<String, String>> {
        val seconds = millis.coerceAtLeast(0) / 1000
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return when {
            hours > 0 && minutes > 0 -> listOf("$hours" to "h", "$minutes" to "min")
            hours > 0 -> listOf("$hours" to "h")
            minutes > 0 || seconds == 0L -> listOf("$minutes" to "min")
            else -> listOf("$seconds" to "s")
        }
    }
}
