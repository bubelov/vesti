@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
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
import org.vestifeed.ui.entries.isAwaitingSync
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
    // Kept true from the moment a sync ends until the reload it triggers has
    // landed, so the frame in between cannot show an empty database as "no
    // feeds" before the pulled-in entries are read back.
    var reloadPending by remember { mutableStateOf(false) }

    val running by state.sync.running.collectAsState()
    val lastError by state.sync.lastError.collectAsState()

    LaunchedEffect(screen.list, state.conf, refreshKey) {
        loading = true
        try {
            val loaded = state.withDb { screen.list.load(state.db) }
            rows = loaded.map { it.toRow(state.conf) }
            bookmarked = loaded.associate { it.id to it.extBookmarked }
            feedCount = state.withDb { state.db.feed.selectAll().size }
            state.refreshUnreadCount()
        } finally {
            loading = false
            reloadPending = false
        }
    }

    // Reload once a background sync finishes so newly synced entries appear.
    LaunchedEffect(running) {
        if (wasRunning && !running) {
            reloadPending = true
            refreshKey++
        }
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
            val count = state.withDb { state.db.entry.countByOgImageFetchedAfter(since) }
            if (count != seen && !state.sync.running.value) {
                seen = count
                refreshKey++
            }
        }
    }

    val entriesView = state.conf.entriesView

    // While the first sync is still filling an empty database, the Unread list
    // is legitimately empty. Show that a sync is under way rather than the
    // "You have no feeds yet" empty state, which would misrepresent an account
    // whose feeds simply have not landed yet. The pending reload is included so
    // the placeholder survives until the pulled-in entries are actually read.
    val awaitingSync = screen.list.isAwaitingSync(
        syncPending = running || wasRunning || reloadPending,
        rowCount = rows.size,
        feedCount = feedCount,
    )

    fun onEntryClick(row: EntryRow) {
        scope.launch {
            state.withDb { state.db.entry.updateReadAndReadSynced(row.id, true, false) }
            state.sync.pushInBackground()
            val href = if (row.openInBrowser) state.withDb { alternateHref(state, row.id) } else null
            if (href != null) {
                state.platform.openUrl(href, row.useBuiltInBrowser)
            } else {
                state.navigate(Screen.EntryDetail(row.id))
            }
        }
    }

    fun onEntryBookmark(row: EntryRow) {
        scope.launch {
            val next = bookmarked[row.id] != true
            state.withDb { state.db.entry.updateBookmarkedAndBookmarkedSynced(row.id, next, false) }
            bookmarked = bookmarked + (row.id to next)
            // The Unread list drops newly bookmarked entries; Saved drops
            // un-bookmarked ones. Other lists keep the entry.
            val leavesList = when (screen.list) {
                EntriesList.Unread -> next
                EntriesList.Bookmarked -> !next
                else -> false
            }
            if (leavesList) rows = rows.filterNot { it.id == row.id }
            state.refreshUnreadCount()
            state.sync.pushInBackground()
        }
    }

    fun onEntryMarkRead(row: EntryRow) {
        scope.launch {
            state.withDb { state.db.entry.updateReadAndReadSynced(row.id, true, false) }
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
            state.sync.pushInBackground()
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

        RefreshableEntriesList(
            enabled = state.platform.supportsPullToRefresh,
            // The sync placeholder below carries its own progress indicator, so
            // suppress the pull-to-refresh one while it is up to avoid showing
            // two spinners at once.
            isRefreshing = running && !awaitingSync,
            onRefresh = {
                state.sync.clearError()
                state.sync.runInBackground()
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val swipesEnabled = maxWidth < SwipeMaxWidth

                when {
                    awaitingSync -> SyncingUnreadState()

                    loading && rows.isEmpty() -> CircularProgressIndicator()

                    rows.isEmpty() && screen.list is EntriesList.Unread && feedCount == 0 ->
                        EmptyUnreadState(
                            onAddFeed = {
                                state.navigateRoot(Screen.Feeds)
                                state.addFeedDialogVisible = true
                            },
                            onBrowseCurated = { state.navigateRoot(Screen.CuratedFeeds) },
                        )

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
                                markReadRemoves = screen.list is EntriesList.Unread,
                                bookmarkRemoves = screen.list is EntriesList.Unread ||
                                    screen.list is EntriesList.Bookmarked,
                                onMarkRead = { onEntryMarkRead(row) },
                                onToggleBookmark = { onEntryBookmark(row) },
                                modifier = Modifier.animateItem(),
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
                                markReadRemoves = screen.list is EntriesList.Unread,
                                bookmarkRemoves = screen.list is EntriesList.Unread ||
                                    screen.list is EntriesList.Bookmarked,
                                onMarkRead = { onEntryMarkRead(row) },
                                onToggleBookmark = { onEntryBookmark(row) },
                                modifier = Modifier.animateItem(),
                            ) {
                                EntryListCard(
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
                }
            }
        }
    }
}

/**
 * The entry-list container. On hosts with a touch pull gesture it wraps the
 * content in a [PullToRefreshBox]; on desktop, which has none, it is a plain
 * [Box] and refreshing happens from the app bar.
 */
@Composable
private fun RefreshableEntriesList(
    enabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (enabled) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            content = content,
        )
    } else {
        Box(modifier = modifier, content = content)
    }
}

/**
 * The actionable empty state on the Unread tab when there are no feeds at all:
 * sends the reader to the Feeds tab to add one by URL, or into the embedded
 * curated collection.
 */
@Composable
private fun EmptyUnreadState(
    onAddFeed: () -> Unit,
    onBrowseCurated: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 420.dp).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MaterialSymbol(
            glyph = MaterialSymbols.RssFeed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            size = 48.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "You have no feeds yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Jump to the Feeds tab to add a feed by URL, or start with a " +
                "few picks from the Awesome RSS Feeds collection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddFeed) { Text("Add a feed by URL") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBrowseCurated) { Text("Browse curated feeds") }
    }
}

/**
 * The placeholder on the Unread tab while the first sync is still filling an
 * empty database. It replaces the empty state so a just-signed-in reader sees
 * that their feeds are on the way instead of "You have no feeds yet".
 */
@Composable
private fun SyncingUnreadState() {
    Column(
        modifier = Modifier.widthIn(max = 420.dp).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Syncing your feeds…",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your feeds and entries are being downloaded. This may take a " +
                "moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
 * bookmark, drag left to mark as read.
 *
 * The card follows the finger and nothing is decided until the gesture ends:
 * released past half a card width, the card animates the rest of the way out
 * and the action runs (the row is then removed, or the card springs back when
 * the action keeps the entry); released short of that, it just springs back.
 */
@Composable
private fun SwipeableEntry(
    enabled: Boolean,
    markReadRemoves: Boolean,
    bookmarkRemoves: Boolean,
    onMarkRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }

    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableStateOf(0f) }

    // The offset is written synchronously by the drag so a quick lift can never
    // leave a pending coroutine to race the release animation (the Animatable
    // mutex would let one cancel the other and freeze the card mid-drag).
    var offset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { widthPx = it.size.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val limit = widthPx
                    offset = (offset + delta).coerceIn(-limit, limit)
                },
                onDragStarted = {
                    settleJob?.cancel()
                    settleJob = null
                },
                onDragStopped = {
                    val limit = widthPx
                    val current = offset
                    settleJob?.cancel()
                    settleJob = scope.launch {
                        if (limit > 0f && abs(current) > limit / 2f) {
                            // Past halfway: finish the swipe, then act.
                            val swipingBookmark = current > 0f
                            animate(
                                initialValue = current,
                                targetValue = if (swipingBookmark) limit else -limit,
                                animationSpec = tween(durationMillis = 180),
                            ) { value, _ -> offset = value }
                            if (swipingBookmark) onToggleBookmark() else onMarkRead()
                            val keeps = if (swipingBookmark) !bookmarkRemoves else !markReadRemoves
                            if (keeps) {
                                animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
                            }
                        } else {
                            animate(current, 0f, animationSpec = spring()) { value, _ -> offset = value }
                        }
                    }
                },
            ),
    ) {
        if (offset != 0f) {
            SwipeBackground(
                markingRead = offset < 0f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) {
            content()
        }
    }
}

@Composable
private fun SwipeBackground(markingRead: Boolean, modifier: Modifier = Modifier) {
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
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = if (markingRead) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            MaterialSymbol(
                glyph = if (markingRead) MaterialSymbols.Visibility else MaterialSymbols.BookmarkAdd,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun EntryListCard(
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
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(
                        start = if (hasImage) 108.dp else 16.dp,
                        top = 16.dp,
                        end = if (showActions) 124.dp else 16.dp,
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
                if (row.showPreviewText && row.summary.isNotBlank()) {
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

            // Wide layouts have no swipe gesture, so the list card offers the
            // same eye/bookmark buttons as the grid.
            if (showActions) {
                CardActionButtons(
                    isBookmarked = isBookmarked,
                    onMarkRead = onMarkRead,
                    onToggleBookmark = onToggleBookmark,
                    onScrim = false,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp),
                )
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
        Box(Modifier.fillMaxWidth()) {
            Column {
                if (hasImage) {
                    // Media on top, flush with the card's edges. Cropping forces
                    // the fixed 16:9 band; otherwise the band takes the image's
                    // own aspect ratio, so a full width shows the whole picture.
                    var aspectRatio by remember(row.imageUrl) { mutableStateOf<Float?>(null) }
                    AsyncImage(
                        model = proxiedUrl(row.imageUrl),
                        contentDescription = null,
                        contentScale = if (row.cropImage) ContentScale.Crop else ContentScale.Fit,
                        onSuccess = { success ->
                            val image = success.result.image
                            if (image.width > 0 && image.height > 0) {
                                aspectRatio = image.width.toFloat() / image.height.toFloat()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                if (row.cropImage) 16f / 9f else aspectRatio ?: (16f / 9f),
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = if (showActions && !hasImage) 124.dp else 16.dp,
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
                    if (row.showPreviewText && row.summary.isNotBlank()) {
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
            // these, so hide them to keep the card clean. Without a preview
            // image the card is just text, so the buttons sit against the right
            // edge, vertically centred; over an image they stay top-right.
            if (showActions) {
                CardActionButtons(
                    isBookmarked = isBookmarked,
                    onMarkRead = onMarkRead,
                    onToggleBookmark = onToggleBookmark,
                    onScrim = hasImage,
                    modifier = Modifier
                        .align(if (hasImage) Alignment.TopEnd else Alignment.CenterEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

/**
 * The eye/bookmark action pair for an entry. Shared by the grid and list
 * layouts so both offer the same actions wherever the swipe gesture is not
 * available.
 */
@Composable
private fun CardActionButtons(
    isBookmarked: Boolean,
    onMarkRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onScrim: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardActionButton(
            glyph = MaterialSymbols.Visibility,
            contentDescription = "Mark as read",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onScrim = onScrim,
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
            onScrim = onScrim,
            onClick = onToggleBookmark,
        )
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
