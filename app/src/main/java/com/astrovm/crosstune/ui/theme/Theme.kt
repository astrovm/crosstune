package com.astrovm.crosstune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val DarkColorScheme = darkColorScheme(
    primary = VioletLight,
    onPrimary = Color(0xFF1F0F66),
    primaryContainer = Color(0xFF3A27A8),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Lime,
    onSecondary = Color(0xFF1B2600),
    secondaryContainer = Color(0xFF2A2840),
    onSecondaryContainer = Color(0xFFE2DDF8),
    tertiary = Lime,
    onTertiary = Color(0xFF1B2600),
    background = Night,
    onBackground = OnNight,
    surface = Night,
    onSurface = OnNight,
    surfaceVariant = NightHigh,
    onSurfaceVariant = OnNightVariant,
    surfaceContainerLowest = NightLowest,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightHigh,
    surfaceContainerHighest = NightHighest,
    outline = Color(0xFF4A4758),
    outlineVariant = Color(0xFF2D2B38),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF4C1A1E),
    onErrorContainer = Color(0xFFFFDAD6)
)

internal val LightColorScheme = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1A0A63),
    secondary = LimeDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E4FA),
    onSecondaryContainer = Color(0xFF1F1B33),
    tertiary = LimeDark,
    onTertiary = Color.White,
    background = Day,
    onBackground = OnDay,
    surface = Day,
    onSurface = OnDay,
    surfaceVariant = DayHigh,
    onSurfaceVariant = OnDayVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DayLow,
    surfaceContainer = DayContainer,
    surfaceContainerHigh = DayHigh,
    surfaceContainerHighest = DayHighest,
    outline = Color(0xFF7A7689),
    outlineVariant = Color(0xFFD9D5E3),
    errorContainer = Color(0xFFFFE3DF),
    onErrorContainer = Color(0xFF410006)
)

/** Soft, generous rounding: small for chips and covers in lists, large for groups and sheets. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/**
 * Crosstune's own colors, the logo's violet with a lime accent, rather than the wallpaper's: the
 * covers bring their colors in, and a neutral, slightly violet ground lets them show.
 */
@Composable
fun CrosstuneTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
