package org.vestifeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.material_symbols

// Both schemes are a Material Theme Builder export seeded from #1F2D61 (navy),
// with the full tonal palette swapped in for every role: primary, secondary,
// tertiary, the containers, and the neutral surfaces. This keeps component
// defaults (e.g. SegmentedButton's secondaryContainer) on-brand without any
// per-component overrides.

private val LightColors = lightColorScheme(
    primary = Color(0xFF4E5B92),
    surfaceTint = Color(0xFF4E5B92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF364479),
    inversePrimary = Color(0xFFB7C4FF),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE1F9),
    onSecondaryContainer = Color(0xFF424659),
    tertiary = Color(0xFF75546F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F4),
    onTertiaryContainer = Color(0xFF5C3D56),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF2F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    surfaceTint = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF1F2D61),
    primaryContainer = Color(0xFF364479),
    onPrimaryContainer = Color(0xFFDDE1FF),
    inversePrimary = Color(0xFF4E5B92),
    secondary = Color(0xFFC2C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFFE4BAD9),
    onTertiary = Color(0xFF43273F),
    tertiaryContainer = Color(0xFF5C3D56),
    onTertiaryContainer = Color(0xFFFFD7F4),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

/**
 * The Material Symbols typeface used to render the icon glyphs; provided by
 * [VestiTheme].
 */
val LocalIconFont = staticCompositionLocalOf<FontFamily?> { null }

/** Whether the app is in dark mode; provided by [VestiTheme]. */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val DarkNavIndicator = Color(0xFF353A4D)
private val DarkNavSelected = Color(0xFFAAAAB4)

/**
 * The bottom-navigation item colours. In dark mode the selected pill and its
 * icon use a cool slate instead of the secondary container.
 */
val vestiNavItemColors: NavigationBarItemColors
    @Composable get() = if (LocalDarkTheme.current) {
        NavigationBarItemDefaults.colors(
            selectedIconColor = DarkNavSelected,
            selectedTextColor = DarkNavSelected,
            indicatorColor = DarkNavIndicator,
        )
    } else {
        NavigationBarItemDefaults.colors()
    }

@Composable
fun VestiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val iconFont = FontFamily(Font(Res.font.material_symbols))

    CompositionLocalProvider(
        LocalIconFont provides iconFont,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
