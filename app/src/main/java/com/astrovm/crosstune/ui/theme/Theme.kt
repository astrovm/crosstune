package com.astrovm.crosstune.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
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

/** One color's tones for a theme: the accent itself, what's written on it, and its softer container. */
internal class Accent(val primary: Color, val onPrimary: Color, val container: Color, val onContainer: Color, val secondary: Color = primary)

/** The logo's violet, with its lime. */
private val violetDark = Accent(VioletLight, Color(0xFF1F0F66), Color(0xFF3A27A8), Color(0xFFE7E0FF), Lime)
private val violetLight = Accent(Violet, Color.White, Color(0xFFE6DEFF), Color(0xFF1A0A63), LimeDark)

/**
 * The app's color: the wallpaper's, which Android shares from 12 on, or one of a few of its own,
 * in the order they're offered. Each has its tones for dark and for light; the grounds stay neutral,
 * so the covers bring their own colors in whichever is picked.
 */
enum class Palette(internal val dark: Accent, internal val light: Accent) {
    // Violet until Android says otherwise, e.g. before Android 12, which shares no wallpaper colors.
    WALLPAPER(violetDark, violetLight),
    VIOLET(violetDark, violetLight),
    OCEAN(
        Accent(Color(0xFF9CCAFF), Color(0xFF003258), Color(0xFF00497D), Color(0xFFD0E4FF)),
        Accent(Color(0xFF0061A4), Color.White, Color(0xFFD1E4FF), Color(0xFF001D36))
    ),
    FOREST(
        Accent(Color(0xFF7FDB9B), Color(0xFF003919), Color(0xFF005227), Color(0xFF9BF8B5)),
        Accent(Color(0xFF006D35), Color.White, Color(0xFF9AF7B4), Color(0xFF00210C))
    ),
    SUNSET(
        Accent(Color(0xFFFFB68B), Color(0xFF522300), Color(0xFF743400), Color(0xFFFFDBC8)),
        Accent(Color(0xFF9A4600), Color.White, Color(0xFFFFDBC8), Color(0xFF321200))
    ),
    ROSE(
        Accent(Color(0xFFFFB0CB), Color(0xFF5E1135), Color(0xFF7B294C), Color(0xFFFFD9E3)),
        Accent(Color(0xFFA0365F), Color.White, Color(0xFFFFD9E3), Color(0xFF3E001D))
    )
}

/** Whether Android shares the wallpaper's colors, which it does from 12 on. */
val wallpaperColors: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** [scheme] in [accent]'s color, its grounds kept. */
internal fun ColorScheme.withAccent(accent: Accent): ColorScheme = copy(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.container,
    onPrimaryContainer = accent.onContainer,
    secondary = accent.secondary,
    tertiary = accent.secondary,
    secondaryContainer = lerp(surfaceContainerHigh, accent.container, 0.35f)
)

/** Dark grounds made true black, which OLED screens show by switching those pixels off. */
internal fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0E),
    surfaceContainer = Color(0xFF121216),
    surfaceContainerHigh = Color(0xFF1A1A1F),
    surfaceContainerHighest = Color(0xFF232329)
)

/** The app's look: dark or light, in [palette]'s color, and with [pureBlack] true black grounds in the dark. */
@Composable
fun CrosstuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: Palette = Palette.VIOLET,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = if (palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColorScheme.withAccent(palette.dark) else LightColorScheme.withAccent(palette.light)
    }
    MaterialTheme(
        colorScheme = if (darkTheme && pureBlack) scheme.pureBlack() else scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
