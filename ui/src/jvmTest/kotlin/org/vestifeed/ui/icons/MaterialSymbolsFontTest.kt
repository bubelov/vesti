package org.vestifeed.ui.icons

import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The icon font is a subset containing exactly the glyphs the app draws, which
 * is why it is a few hundred kilobytes instead of eleven. A constant added
 * without the matching glyph renders as a blank box at runtime, and nothing else
 * in the build would notice - `bookmark_check` was one such case.
 *
 * The solid variants live in a second font (Compose Multiplatform does not
 * expose font variation settings), so that one is checked for the glyphs it is
 * expected to draw and for actually being solid.
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

    @Test
    fun theFilledFontCarriesASolidBookmark() {
        val outlineFont = File("src/commonMain/composeResources/font/material_symbols.ttf")
        val filledFont = File("src/commonMain/composeResources/font/material_symbols_filled.ttf")
        assertTrue(filledFont.isFile, "filled icon font not found at ${filledFont.absolutePath}")

        val glyph = MaterialSymbols.Bookmark
        val filled = Font.createFont(Font.TRUETYPE_FONT, filledFont)
        assertTrue(
            glyph.all { filled.canDisplay(it.code) },
            "the filled font cannot draw the bookmark",
        )

        val outlineInk = ink(outlineFont, glyph)
        val filledInk = ink(filledFont, glyph)
        assertTrue(
            filledInk > outlineInk * 1.3,
            "the bookmark should be solid in the filled font " +
                "(outline=$outlineInk px, filled=$filledInk px)",
        )
    }

    /** Dark pixels drawn by [glyph] in [fontFile], a crude "how solid is it". */
    private fun ink(fontFile: File, glyph: String): Int {
        val size = 96
        val font = Font.createFont(Font.TRUETYPE_FONT, fontFile).deriveFont(64f)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, size, size)
        graphics.color = Color.BLACK
        graphics.font = font
        graphics.drawString(glyph, 4, size - 16)
        graphics.dispose()

        var dark = 0
        for (x in 0 until size) {
            for (y in 0 until size) {
                if ((image.getRGB(x, y) and 0xFFFFFF) < 0x808080) dark++
            }
        }
        return dark
    }
}
