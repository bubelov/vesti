package org.vestifeed.ui.screens

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

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

/**
 * [text] with every range in [matches] highlighted using [matchStyle]; the
 * [current] match gets [currentStyle] so it stands out from the rest.
 */
internal fun highlightMatches(
    text: String,
    matches: List<IntRange>,
    current: Int,
    matchStyle: SpanStyle,
    currentStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    append(text)
    matches.forEachIndexed { index, range ->
        addStyle(
            style = if (index == current) currentStyle else matchStyle,
            start = range.first,
            end = range.last + 1,
        )
    }
}
