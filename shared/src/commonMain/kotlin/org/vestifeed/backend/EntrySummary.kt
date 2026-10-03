package org.vestifeed.backend

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

private fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this

    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '&') {
            sb.append(c)
            i++
            continue
        }

        val semi = indexOf(';', i + 1)
        if (semi == -1 || semi - i > 10) {
            sb.append(c)
            i++
            continue
        }

        val entity = substring(i + 1, semi)
        val decoded = NAMED_ENTITIES[entity]
            ?: if (entity.startsWith("#")) decodeNumericEntity(entity)
            else null

        if (decoded != null) {
            sb.append(decoded)
            i = semi + 1
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun decodeNumericEntity(entity: String): String? {
    val code = when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
        entity.startsWith("#") -> entity.substring(1).toIntOrNull(10)
        else -> null
    } ?: return null
    return codePointToString(code)
}

/**
 * The common-code replacement for `java.lang.Character.toChars(code)`: a
 * code point above the BMP becomes a UTF-16 surrogate pair, and an out-of-range
 * value yields null (matching the `IllegalArgumentException` the JVM helper
 * threw).
 */
private fun codePointToString(code: Int): String? {
    if (code < 0 || code > 0x10FFFF) return null
    if (code <= 0xFFFF) return code.toChar().toString()

    val offset = code - 0x10000
    val high = 0xD800 + (offset shr 10)
    val low = 0xDC00 + (offset and 0x3FF)
    return charArrayOf(high.toChar(), low.toChar()).concatToString()
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to "\u00A0",
    "ndash" to "–",
    "mdash" to "—",
    "lsquo" to "‘",
    "rsquo" to "’",
    "ldquo" to "“",
    "rdquo" to "”",
    "hellip" to "…",
    "laquo" to "«",
    "raquo" to "»",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "euro" to "€",
    "pound" to "£",
    "cent" to "¢",
    "yen" to "¥",
)
