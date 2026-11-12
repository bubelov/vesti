package org.vestifeed.ui.stats

/**
 * One card on a stats screen: a titled group of label/value rows.
 *
 * [key] is the card's identity for the list diff and must be unique within a
 * screen. It is deliberately separate from [title], which is only what is
 * displayed, so two cards can share a title without the diff conflating them.
 *
 * [icon] is a Material Symbols glyph (see [org.vestifeed.ui.icons.MaterialSymbols]),
 * or null for a card without a leading icon.
 */
data class StatsSection(
    val key: String,
    val title: String,
    val entries: List<StatsEntry>,
    val icon: String? = null,
)

/**
 * A label and its value inside a [StatsSection] card.
 *
 * [icon] is an optional Material Symbols glyph drawn before the label, for a
 * row that benefits from its own glyph (for example a per-table row).
 *
 * [details] are extra label/value rows hidden behind the row: when non-empty
 * the row becomes tappable and reveals them beneath itself, drawn like any
 * other row, for a compact summary that still has the full breakdown one tap
 * away.
 */
data class StatsEntry(
    val label: String,
    val value: String,
    val icon: String? = null,
    val details: List<StatsEntry> = emptyList(),
)
