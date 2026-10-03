package org.vestifeed.ui.theme

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.Font
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.material_symbols

// Vesti's brand is a warm orange. The scheme below is a full Material 3 tonal
// palette built around it, so every role (onPrimary, containers, outlines,
// surfaces) stays coherent and keeps AA contrast. Overriding only `primary` /
// `secondary` leaves the remaining roles on Material 3's baseline purple, which
// is how the sign-in button ended up with purple text on an orange fill.

private val LightColors = lightColorScheme(
    primary = Color(0xFF8F4513),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF321200),
    inversePrimary = Color(0xFFFFB68E),
    secondary = Color(0xFF74584A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2A160C),
    tertiary = Color(0xFF665D2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEE1A8),
    onTertiaryContainer = Color(0xFF201C00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF4DED3),
    onSurfaceVariant = Color(0xFF52443C),
    inverseSurface = Color(0xFF382E29),
    inverseOnSurface = Color(0xFFFFEDE5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF85736A),
    outlineVariant = Color(0xFFD8C2B7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceDim = Color(0xFFE7D7CE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFCEAE1),
    surfaceContainerHigh = Color(0xFFF6E4DB),
    surfaceContainerHighest = Color(0xFFF0DED5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68E),
    onPrimary = Color(0xFF552100),
    primaryContainer = Color(0xFF7A3410),
    onPrimaryContainer = Color(0xFFFFDCC8),
    inversePrimary = Color(0xFF8F4513),
    secondary = Color(0xFFE5BEAC),
    onSecondary = Color(0xFF422A1E),
    secondaryContainer = Color(0xFF5B4133),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFD2C58D),
    onTertiary = Color(0xFF373004),
    tertiaryContainer = Color(0xFF4F461A),
    onTertiaryContainer = Color(0xFFEEE1A8),
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF0D0E12),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD8C2B7),
    inverseSurface = Color(0xFFF0DFD7),
    inverseOnSurface = Color(0xFF382E29),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9F8C83),
    outlineVariant = Color(0xFF52443C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF413632),
    surfaceDim = Color(0xFF19120D),
    surfaceContainerLowest = Color(0xFF130C08),
    surfaceContainerLow = Color(0xFF221A15),
    surfaceContainer = Color(0xFF18191E),
    surfaceContainerHigh = Color(0xFF312823),
    surfaceContainerHighest = Color(0xFF3D332E),
)

/**
 * The Material Symbols typeface used to render the icon glyphs; provided by
 * [VestiTheme].
 */
val LocalIconFont = staticCompositionLocalOf<FontFamily?> { null }

/** Whether the app is in dark mode; provided by [VestiTheme]. */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val DarkCardBackground = Color(0xFF0D0E12)
private val DarkCardBorder = Color.White.copy(alpha = 0.3f)

/**
 * The container colour for app cards. In dark mode a card shares the page
 * colour and is separated from it by [vestiCardBorder] instead.
 */
val vestiCardContainer: Color
    @Composable get() = if (LocalDarkTheme.current) {
        DarkCardBackground
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

/** The hairline border around app cards, white in dark mode. */
val vestiCardBorder: BorderStroke?
    @Composable get() = if (LocalDarkTheme.current) {
        BorderStroke(1.dp, DarkCardBorder)
    } else {
        null
    }

private val DarkNavIndicator = Color(0xFF353A4D)
private val DarkNavSelected = Color(0xFFAAAAB4)

/**
 * The bottom-navigation item colours. In dark mode the selected pill and its
 * icon use a cool slate instead of the warm secondary container.
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
