package org.vestifeed.ui.screens

/**
 * Case-insensitive, non-overlapping occurrences of [query] in [text], returned
 * as inclusive `first..last` ranges in order of appearance. A blank [query]
 * (after trimming) yields no matches.
 */
internal fun findMatches(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<IntRange>()
    var index = text.indexOf(needle, startIndex = 0, ignoreCase = true)
    while (index >= 0) {
        matches += index..(index + needle.length - 1)
        index = text.indexOf(needle, startIndex = index + needle.length, ignoreCase = true)
    }
    return matches
}
