package org.vestifeed.ui.screens

import org.vestifeed.util.decodeHtmlEntities

/**
 * Turns an HTML fragment into plain text. Non-prose content (script/style
 * bodies and comments) is dropped, tags are replaced with a space so adjacent
 * blocks do not run together, HTML character references are decoded, and runs
 * of whitespace are collapsed. This is deliberately a very small helper: the
 * Compose screens only need readable prose, not a full HTML parser or renderer.
 *
 * When [preserveParagraphs] is set, block-level boundaries (`</p>`, `</div>`,
 * headings, list items, `<br>`, …) become newlines instead of spaces, so the
 * article's paragraphing survives for the entry detail view. The default
 * (summaries, search results) still flattens to a single line.
 */
internal fun stripHtml(html: String, preserveParagraphs: Boolean = false): String {
    // Drop script/style bodies and comments before tags so their contents never
    // reach the prose.
    val prose = html
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(COMMENT, " ")

    var text = if (preserveParagraphs) {
        // Collapse the source's own formatting whitespace first, then turn
        // block boundaries into newlines so only real paragraph breaks remain.
        prose
            .replace(WHITESPACE, " ")
            .replace(BR, "\n")
            .replace(LINE_END, "\n")
            .replace(PARAGRAPH_END, "\n\n")
            .replace(TAG, " ")
    } else {
        prose.replace(TAG, " ")
    }

    // Decode references, then fold non-breaking spaces into ordinary ones so
    // whitespace collapsing and matching see a single kind of space.
    text = text.decodeHtmlEntities().replace('\u00A0', ' ')

    return if (preserveParagraphs) {
        text
            .replace(HORIZONTAL_WHITESPACE, " ")
            .replace(SPACES_AROUND_NEWLINE, "\n")
            .replace(BLANK_LINES, "\n\n")
            .trim()
    } else {
        text.replace(WHITESPACE, " ").trim()
    }
}

private val TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
private val HORIZONTAL_WHITESPACE = Regex("[\\t\\x0B\\f\\r ]+")
private val SPACES_AROUND_NEWLINE = Regex(" *\\n *")
private val BLANK_LINES = Regex("\\n{3,}")

/** A `<script>` or `<style>` block, including its body. */
private val SCRIPT_OR_STYLE = Regex(
    "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** An HTML comment. */
private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

/** A hard line break. */
private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

/**
 * Ends of line-oriented blocks: list items and table rows read as separate
 * lines, not separate paragraphs.
 */
private val LINE_END = Regex("</(?:li|tr|dt|dd)\\s*>", RegexOption.IGNORE_CASE)

/** Ends of paragraph-level blocks; these get a blank line before the next one. */
private val PARAGRAPH_END = Regex(
    "</(?:p|div|section|article|header|footer|aside|nav|main|blockquote|pre|figure|figcaption|h[1-6]|ul|ol|table|dl)\\s*>",
    RegexOption.IGNORE_CASE,
)
