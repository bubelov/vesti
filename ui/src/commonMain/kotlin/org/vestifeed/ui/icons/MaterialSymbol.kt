package org.vestifeed.ui.icons

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.vestifeed.ui.theme.LocalIconFont

/**
 * A Material Symbols icon, drawn with the typeface [LocalIconFont] (provided by
 * [org.vestifeed.ui.theme.VestiTheme]) at its default text size.
 *
 * [contentDescription] is null for decorative icons, e.g. one already described
 * by its button's label.
 */
@Composable
fun MaterialSymbol(
    glyph: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: TextUnit = 24.sp,
) {
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics {}
    }

    Text(
        text = glyph,
        fontFamily = LocalIconFont.current,
        fontSize = size,
        color = tint,
        modifier = modifier.then(semantics),
    )
}
