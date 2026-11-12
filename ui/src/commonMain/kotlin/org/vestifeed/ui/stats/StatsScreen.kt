package org.vestifeed.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/** Test tag for the stats card list, so a test can scroll to a section. */
const val STATS_LIST_TAG = "stats-list"

/** Test tag for one label/value row of a stats card. */
fun statsEntryTag(sectionKey: String, label: String): String = "stats-$sectionKey-$label"

/**
 * Test tag for one detail row revealed beneath a stats entry, namespaced by the
 * parent entry's label since several entries may share a detail label (for
 * example every table reports its own row count).
 */
fun statsDetailTag(sectionKey: String, entryLabel: String, detailLabel: String): String =
    "stats-detail-$sectionKey-$entryLabel-$detailLabel"

/**
 * Renders label/value cards in an adaptive staggered grid: a phone shows one
 * column, while a wide window shows as many [minColumnWidth]-wide columns as
 * fit. Each column packs its cards independently, so a card taller than its
 * neighbours does not leave a hole beneath the shorter ones.
 *
 * The theme and the icon font come from the surrounding
 * [org.vestifeed.ui.theme.VestiTheme]; section and row icons are omitted when
 * no icon font is provided.
 */
@Composable
fun StatsScreen(
    sections: List<StatsSection>,
    modifier: Modifier = Modifier,
    minColumnWidth: Dp = 340.dp,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = minColumnWidth),
        modifier = modifier.fillMaxSize().testTag(STATS_LIST_TAG),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(sections, key = { it.key }) { section ->
            StatsCard(section)
        }
    }
}

@Composable
private fun StatsCard(section: StatsSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                section.icon?.let { icon ->
                    MaterialSymbol(
                        glyph = icon,
                        contentDescription = null,
                        size = 24.sp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            section.entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
                key(entry.label) {
                    StatsEntryRow(section = section, entry = entry)
                }
            }
        }
    }
}

/**
 * One label/value row. A row whose [StatsEntry.details] are non-empty is
 * tappable: it shows an expand affordance and reveals the details beneath
 * itself, drawn in the same shape as an unexpanded row.
 */
@Composable
private fun StatsEntryRow(section: StatsSection, entry: StatsEntry) {
    val expandable = entry.details.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                .testTag(statsEntryTag(section.key, entry.label))
                // Label and value are one node, so a screen reader announces
                // them together instead of as two stops.
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entry.icon?.let { icon ->
                MaterialSymbol(
                    glyph = icon,
                    contentDescription = null,
                    size = 20.sp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expandable) {
                Spacer(Modifier.width(8.dp))
                // The bundled icon subset has no expand glyph, so the same
                // chevron is turned to point down while the details are open.
                MaterialSymbol(
                    glyph = MaterialSymbols.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                    size = 20.sp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (expanded) {
            entry.details.forEach { detail ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(statsDetailTag(section.key, entry.label, detail.label))
                        // Label and value are one node in the details too.
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The details share an unexpanded row's type scale, colours
                    // and padding, so a revealed step reads as one of them.
                    Text(
                        text = detail.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                    )
                    Text(
                        text = detail.value,
                        style = MaterialTheme.typography.bodyMedium,
                        // A detail can be a sync error message, which is longer
                        // than the numbers that usually sit here.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
