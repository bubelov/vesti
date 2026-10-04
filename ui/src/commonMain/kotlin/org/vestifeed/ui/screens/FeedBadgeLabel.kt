package org.vestifeed.ui.screens

/**
 * The two-character monogram shown as a feed's avatar: the first two letters of
 * a single-word title, otherwise the initials of the first two words. Always
 * upper-case, falling back to "?" for an empty title.
 */
internal fun feedBadgeLabel(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> words[0].take(1).uppercase() + words[1].take(1).uppercase()
    }
}
