package com.hawwwran.waketracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colours. A night ground with the lit screen as the one colour that carries data:
 * [lit] where the phone was in use, [litDim] where the screen only woke on the lock screen.
 * The two are steps of one hue, checked against [track] for contrast in both themes, so the
 * difference survives colour blindness (the chart also draws the dim ones lower).
 */
@Immutable
data class Palette(
    val ground: Color,
    val track: Color,
    val hairline: Color,
    val ink: Color,
    val muted: Color,
    val lit: Color,
    val litDim: Color,
    val dark: Boolean,
)

private val Night = Palette(
    ground = Color(0xFF10162A),
    track = Color(0xFF1B2440),
    hairline = Color(0xFF2A3556),
    ink = Color(0xFFE9EDF7),
    muted = Color(0xFF98A3BF),
    lit = Color(0xFF7FDBF2),
    litDim = Color(0xFF3E8FA8),
    dark = true,
)

private val Day = Palette(
    ground = Color(0xFFF6F7FB),
    track = Color(0xFFE3E8F3),
    hairline = Color(0xFFD2D9E8),
    ink = Color(0xFF151B2E),
    muted = Color(0xFF5D6785),
    lit = Color(0xFF075E78),
    litDim = Color(0xFF4A9DB6),
    dark = false,
)

/** The update bar: an attention colour that stays the same in both themes. */
val Amber = Color(0xFFF5B83D)
val OnAmber = Color(0xFF1D1500)

val LocalPalette = staticCompositionLocalOf { Day }

@Composable
fun WakeTrackerTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) Night else Day
    val scheme = if (palette.dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = scheme.copy(
            primary = palette.lit,
            onPrimary = palette.ground,
            background = palette.ground,
            onBackground = palette.ink,
            surface = palette.ground,
            onSurface = palette.ink,
            surfaceVariant = palette.track,
            onSurfaceVariant = palette.muted,
            surfaceContainerHigh = palette.track,
            surfaceContainerHighest = palette.track,
            outline = palette.muted,
            outlineVariant = palette.hairline,
        ),
    ) {
        // Icons and plain text take the ink unless told otherwise; without a Material
        // Surface around the app nothing else sets it.
        CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.ink, content = content)
    }
}
