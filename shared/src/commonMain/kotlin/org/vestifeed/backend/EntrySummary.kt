package org.vestifeed.backend

import org.vestifeed.util.decodeHtmlEntities

/**
 * Build the one-line summary shown in the entries list from a raw HTML
 * description.
 *
 * RSS posts often start with a "Table of Contents" block (a `<div
 * class="table-of-contents">` or `<nav id="TableOfContents">` listing every
 * section header), which produces a useless preview. Pick the first `<p>` that
 * survives [minParagraphLength] characters of plain text instead, and only fall
 * back to the stripped, length-capped body when no paragraph qualifies.
 *
 * Numeric and a handful of common named HTML entities (`&rsquo;`, `&ldquo;`,
 * `&amp;`, …) are decoded so the preview matches what the detail view renders.
 */
internal fun String.toEntrySummary(
    maxLength: Int = 400,
    minParagraphLength: Int = 80,
): String {
    val firstParagraph = firstNonTocParagraph(minParagraphLength)
    if (firstParagraph.isNotEmpty()) {
        return firstParagraph.take(maxLength)
    }

    return stripHtml().take(maxLength)
}

private fun String.firstNonTocParagraph(minLength: Int): String {
    if (isEmpty()) return ""

    val withoutToc = TABLE_OF_CONTENTS_REGEX.replace(this, " ")

    for (match in PARAGRAPH_REGEX.findAll(withoutToc)) {
        val text = match.groupValues[1].stripHtml()
        if (text.length >= minLength) {
            return text
        }
    }

    return ""
}

private val TABLE_OF_CONTENTS_REGEX = Regex(
    """<(?:div|nav)\b[^>]*?(?:class\s*=\s*"[^"]*table-of-contents[^"]*"|id\s*=\s*"TableOfContents")[^>]*>.*?</(?:div|nav)>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val PARAGRAPH_REGEX = Regex(
    """<p\b[^>]*>(.*?)</p>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private fun String.stripHtml(): String {
    return this
        .replace(TAG_REGEX, " ")
        .decodeHtmlEntities()
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val TAG_REGEX = Regex("<[^>]*>")
