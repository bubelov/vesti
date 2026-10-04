package org.vestifeed.util

/**
 * Decodes the HTML character references in [this]: the common named entities
 * (`&rsquo;`, `&mdash;`, `&amp;`, …) plus the numeric `&#8217;` and `&#x2019;`
 * forms, including code points above the Basic Multilingual Plane (which become
 * a UTF-16 surrogate pair). References that are not recognised are left as
 * written, so a stray `&` never eats the text after it.
 */
fun String.decodeHtmlEntities(): String {
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
        if (semi == -1 || semi - i > MAX_ENTITY_LENGTH) {
            sb.append(c)
            i++
            continue
        }

        val entity = substring(i + 1, semi)
        val decoded = NAMED_ENTITIES[entity]
            ?: if (entity.startsWith("#")) decodeNumericEntity(entity) else null

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

/** Longest reference we consider: `#x10FFFF` plus a little slack. */
private const val MAX_ENTITY_LENGTH = 10

private fun decodeNumericEntity(entity: String): String? {
    val code = when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
        entity.startsWith("#") -> entity.substring(1).toIntOrNull(10)
        else -> null
    } ?: return null
    return codePointToString(code)
}

/**
 * The common-code replacement for `java.lang.Character.toChars(code)`: a code
 * point above the BMP becomes a UTF-16 surrogate pair, and an out-of-range
 * value yields null.
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
