package org.vestifeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFB3541E)
private val OrangeDark = Color(0xFFFFB68E)
private val Brown = Color(0xFF6D4C41)

private val LightColors = lightColorScheme(
    primary = Orange,
    secondary = Brown,
)

private val DarkColors = darkColorScheme(
    primary = OrangeDark,
    secondary = Color(0xFFD7CCC8),
)

@Composable
fun VestiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
