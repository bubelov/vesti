@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vestifeed.db.table.ConfTable
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.platform.proxiedUrl
import org.vestifeed.ui.AppState
import org.vestifeed.ui.EntriesList
import org.vestifeed.ui.Screen
import org.vestifeed.ui.entries.EntryRow
import org.vestifeed.ui.entries.emptyMessage
import org.vestifeed.ui.entries.load
import org.vestifeed.ui.entries.toRow
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/** The reading width of the list, centered on wide (desktop) windows. */
private val ContentWidth = 760.dp

/** The card grid may be a little wider so two cards per row stay comfortable. */
private val GridMaxWidth = 900.dp

/** Below this card width the grid falls back to a single column. */
private val GridMinCellWidth = 320.dp

/**
 * Swipe-to-mark-read / swipe-to-bookmark is enabled on compact layouts (a
 * single column), where horizontal dragging is not competing with a grid.
 */
private val SwipeMaxWidth = 600.dp

/**
 * How often to check for OG preview images downloaded by the background
 * fetcher while this screen is open.
 */
private val OgImagePollInterval = 5.seconds

/**
 * A list of entries (unread, bookmarks or a feed's). Compact cards: a preview
 * image on the left, the title and metadata in the middle. Read entries are
 * dimmed; bookmarking is done with a swipe on compact layouts.
 */
@Composable
fun EntriesScreen(state: AppState, screen: Screen.Entries) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<EntryRow>>(emptyList()) }
    var bookmarked by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var feedCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var wasRunning by remember { mutableStateOf(false) }

    val running by state.sync.running.collectAsState()
    val lastError by state.sync.lastError.collectAsState()

    LaunchedEffect(screen.list, state.conf, refreshKey) {
        loading = true
        val loaded = screen.list.load(state.db)
        rows = loaded.map { it.toRow(state.conf) }
        bookmarked = loaded.associate { row ->
            row.id to (state.db.entry.selectById(row.id)?.extBookmarked ?: false)
        }
        feedCount = state.db.feed.selectAll().size
        state.refreshUnreadCount()
        loading = false
    }

    // Reload once a background sync finishes so newly synced entries appear.
    LaunchedEffect(running) {
        if (wasRunning && !running) refreshKey++
        wasRunning = running
    }

    // The OG fetch runs in the background with no callback, so poll the count
    // of entries whose preview image landed since this screen opened and
    // reload when it grows. Without this, images downloaded after the initial
    // load stay invisible until the next sync or a screen re-entry.
    LaunchedEffect(screen.list) {
        val since = Clock.System.now()
        var seen = 0L
        while (true) {
            delay(OgImagePollInterval)
            val count = state.db.entry.countByOgImageFetchedAfter(since)
            if (count != seen && !state.sync.running.value) {
                seen = count
                refreshKey++
            }
        }
    }

    val entriesView = state.conf.entriesView

    fun onEntryClick(row: EntryRow) {
        scope.launch {
            state.db.entry.updateReadAndReadSynced(row.id, true, false)
            state.sync.runInBackground()
            val href = if (row.openInBrowser) alternateHref(state, row.id) else null
            if (href != null) {
                state.platform.openUrl(href)
            } else {
                state.navigate(Screen.EntryDetail(row.id))
            }
        }
    }

    fun onEntryBookmark(row: EntryRow) {
        scope.launch {
            val next = bookmarked[row.id] != true
            state.db.entry.updateBookmarkedAndBookmarkedSynced(row.id, next, false)
            bookmarked = bookmarked + (row.id to next)
            state.refreshUnreadCount()
            state.sync.runInBackground()
        }
    }

    fun onEntryMarkRead(row: EntryRow) {
        scope.launch {
            state.db.entry.updateReadAndReadSynced(row.id, true, false)
            // Update the list right away: the unread list drops the entry,
            // the others just dim it. A fast sync can finish between frames, so
            // we cannot rely on the running-state reload to do this.
            rows = if (screen.list is EntriesList.Unread) {
                rows.filterNot { it.id == row.id }
            } else {
                rows.map { if (it.id == row.id) it.copy(read = true) else it }
            }
            refreshKey++
            state.refreshUnreadCount()
            state.sync.runInBackground()
        }
    }

    Column(Modifier.fillMaxSize()) {
        lastError?.let {
            Text(
                text = "Sync failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = ContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val swipesEnabled = maxWidth < SwipeMaxWidth

            when {
                loading && rows.isEmpty() -> CircularProgressIndicator()

                rows.isEmpty() -> Text(
                    text = screen.list.emptyMessage(feedCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                entriesView == ConfTable.EntriesView.Cards -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GridMinCellWidth),
                    modifier = Modifier.widthIn(max = GridMaxWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(rows, key = { it.id }) { row ->
                        SwipeableEntry(
                            enabled = swipesEnabled,
                            onMarkRead = { onEntryMarkRead(row) },
                            onToggleBookmark = { onEntryBookmark(row) },
                        ) {
                            EntryGridCard(
                                row = row,
                                isBookmarked = bookmarked[row.id] == true,
                                onClick = { onEntryClick(row) },
                                onToggleBookmark = { onEntryBookmark(row) },
                                onMarkRead = { onEntryMarkRead(row) },
                                showActions = !swipesEnabled,
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = ContentWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        SwipeableEntry(
                            enabled = swipesEnabled,
                            onMarkRead = { onEntryMarkRead(row) },
                            onToggleBookmark = { onEntryBookmark(row) },
                        ) {
                            EntryListCard(
                                row = row,
                                onClick = { onEntryClick(row) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun alternateHref(state: AppState, entryId: String): String? {
    val links = state.db.link.selectByEntryId(entryId)
    return links.firstOrNull {
        it.rel == AtomLinkRel.Alternate && it.type?.startsWith("text/html") == true
    }?.href ?: links.firstOrNull { it.rel == AtomLinkRel.Alternate }?.href
}

/**
 * Wraps an entry card with the compact-layout swipe gestures: drag right to
 * bookmark, drag left to mark as read. The card always snaps back — the list
 * reacts to the underlying change — so [confirmValueChange] performs the action
 * and returns false.
 */
@Composable
private fun SwipeableEntry(
    enabled: Boolean,
    onMarkRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleBookmark()
                SwipeToDismissBoxValue.EndToStart -> onMarkRead()
                else -> Unit
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val markingRead = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (markingRead) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (markingRead) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    contentAlignment = if (markingRead) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                ) {
                    MaterialSymbol(
                        glyph = if (markingRead) MaterialSymbols.Close else MaterialSymbols.BookmarkAdd,
                        contentDescription = null,
                    )
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun EntryListCard(
    row: EntryRow,
    onClick: () -> Unit,
) {
    val hasImage = row.showImage && row.imageUrl.isNotBlank()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(
                        start = if (hasImage) 108.dp else 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.read) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stripHtml(row.summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (hasImage) {
                // Overlaid rather than placed in a Row: the text decides the
                // card height, and the image then stretches to match it, flush
                // with the card's top, left and bottom edges (the card's shape
                // clips the corners).
                Box(Modifier.matchParentSize()) {
                    AsyncImage(
                        model = proxiedUrl(row.imageUrl),
                        contentDescription = null,
                        contentScale = if (row.cropImage) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(96.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryGridCard(
    row: EntryRow,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onMarkRead: () -> Unit,
    showActions: Boolean,
) {
    val hasImage = row.showImage && row.imageUrl.isNotBlank()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box {
            Column {
                if (hasImage) {
                    // Media on top, flush with the card's edges.
                    AsyncImage(
                        model = proxiedUrl(row.imageUrl),
                        contentDescription = null,
                        contentScale = if (row.cropImage) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = if (showActions && !hasImage) 116.dp else 16.dp,
                        bottom = 16.dp,
                    ),
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (row.read) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = row.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stripHtml(row.summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // In the single-column layout the dismiss/bookmark swipes cover
            // these, so hide them to keep the card clean.
            if (showActions) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardActionButton(
                        glyph = MaterialSymbols.Close,
                        contentDescription = "Mark as read",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onScrim = hasImage,
                        onClick = onMarkRead,
                    )
                    CardActionButton(
                        glyph = if (isBookmarked) {
                            MaterialSymbols.BookmarkAdded
                        } else {
                            MaterialSymbols.BookmarkAdd
                        },
                        contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark",
                        tint = if (isBookmarked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onScrim = hasImage,
                        onClick = onToggleBookmark,
                    )
                }
            }
        }
    }
}

/**
 * An icon button for the entry cards. In the card grid it may sit over the
 * preview image, so [onScrim] adds a translucent circular backdrop to keep it
 * legible on any image.
 */
@Composable
private fun CardActionButton(
    glyph: String,
    contentDescription: String,
    tint: Color,
    onScrim: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (onScrim) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
            contentColor = Color.White,
            modifier = modifier,
        ) {
            IconButton(onClick = onClick) {
                MaterialSymbol(glyph, contentDescription, tint = Color.White)
            }
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier) {
            MaterialSymbol(glyph, contentDescription, tint = tint)
        }
    }
}
