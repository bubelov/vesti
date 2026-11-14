package org.vestifeed.ui.icons

import java.awt.Font
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The icon font is a subset containing exactly the glyphs the app draws, which
 * is why it is a few hundred kilobytes instead of eleven. A constant added
 * without the matching glyph renders as a blank box at runtime, and nothing else
 * in the build would notice - `bookmark_check` was one such case.
 */
class MaterialSymbolsFontTest {

    @Test
    fun everyDeclaredGlyphIsInTheBundledFont() {
        val file = File("src/commonMain/composeResources/font/material_symbols.ttf")
        assertTrue(file.isFile, "icon font not found at ${file.absolutePath}")
        val font = Font.createFont(Font.TRUETYPE_FONT, file)

        val glyphs = MaterialSymbols::class.java.declaredFields
            .filter { it.type == String::class.java && Modifier.isStatic(it.modifiers) }
            .associate { it.name to (it.get(null) as String) }

        assertTrue(glyphs.size > 50, "expected the icon constants, found ${glyphs.size}")

        val missing = glyphs
            .filterValues { value -> value.any { char -> !font.canDisplay(char.code) } }
            .keys
            .sorted()

        assertTrue(missing.isEmpty(), "glyphs missing from the bundled font: $missing")
    }
}
