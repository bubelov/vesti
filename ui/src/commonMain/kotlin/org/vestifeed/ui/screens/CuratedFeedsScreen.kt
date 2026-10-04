@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.vestifeed.curated.CuratedCatalog
import org.vestifeed.curated.CuratedCollection
import org.vestifeed.curated.CuratedFeed
import org.vestifeed.curated.CuratedGroup
import org.vestifeed.curated.feedHost
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.ui.AppState
import org.vestifeed.ui.Screen
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/** The reading width of the screen's content, centered on wide (desktop) windows. */
private val ContentWidth = 720.dp

/** How the embedded catalog loads for the browse screens. */
private sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val catalog: CuratedCatalog) : CatalogState
    data class Failed(val message: String) : CatalogState
}

/** Loads and remembers the embedded Awesome RSS Feeds catalog. */
@Composable
private fun rememberCatalog(state: AppState): CatalogState {
    var result by remember { mutableStateOf<CatalogState>(CatalogState.Loading) }
    LaunchedEffect(Unit) {
        result = runCatching { state.curatedFeeds() }.fold(
            onSuccess = { CatalogState.Ready(it) },
            onFailure = { CatalogState.Failed(it.message ?: it.toString()) },
        )
    }
    return result
}

/**
 * The curated-feeds browser: the embedded Awesome RSS Feeds catalog, grouped
 * into topic collections and country news sources. Picking a collection opens
 * [CuratedCollectionScreen].
 */
@Composable
fun CuratedCollectionsScreen(state: AppState) {
    when (val result = rememberCatalog(state)) {
        CatalogState.Loading -> CenteredProgress()
        is CatalogState.Failed -> CenteredMessage(
            title = "Couldn't load curated feeds",
            detail = result.message,
        )

        is CatalogState.Ready -> {
            var selectedGroup by remember { mutableStateOf(CuratedGroup.Recommended) }
            val collections = result.catalog.inGroup(selectedGroup)

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = ContentWidth).fillMaxSize()) {
                    SecondaryTabRow(selectedTabIndex = selectedGroup.ordinal) {
                        CuratedGroup.entries.forEach { group ->
                            Tab(
                                selected = group == selectedGroup,
                                onClick = { selectedGroup = group },
                                text = { Text(group.label()) },
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        item { CatalogIntro() }
                        items(collections, key = { it.id }) { collection ->
                            CollectionRow(collection) {
                                state.navigate(Screen.CuratedCollection(collection.id))
                            }
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                        item { SourceFooter(result.catalog, state) }
                    }
                }
            }
        }
    }
}

/** The tab label for a catalog group. */
private fun CuratedGroup.label(): String = when (this) {
    CuratedGroup.Recommended -> "Topics"
    CuratedGroup.Countries -> "Countries"
}

/**
 * The feeds inside one curated collection. Each feed is followed on its own;
 * already-followed feeds are marked and skipped.
 */
@Composable
fun CuratedCollectionScreen(state: AppState, collectionId: String) {
    val scope = rememberCoroutineScope()
    var subscribed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failures by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(collectionId) { subscribed = subscribedUrls(state) }

    when (val result = rememberCatalog(state)) {
        CatalogState.Loading -> CenteredProgress()
        is CatalogState.Failed -> CenteredMessage(
            title = "Couldn't load curated feeds",
            detail = result.message,
        )

        is CatalogState.Ready -> {
            val collection = result.catalog.collection(collectionId)
            if (collection == null) {
                CenteredMessage(title = "Collection not found", detail = null)
                return
            }

            fun isFollowing(feed: CuratedFeed): Boolean =
                feed.url in subscribed || feed.url in added

            fun follow(feed: CuratedFeed) {
                if (feed.url in pending) return
                scope.launch {
                    pending = pending + feed.url
                    failures = failures - feed.url
                    state.addFeedByUrl(feed.url)
                        .onSuccess { added = added + feed.url }
                        .onFailure { failures = failures + (feed.url to (it.message ?: it.toString())) }
                    pending = pending - feed.url
                }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = ContentWidth).fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(collection.feeds, key = { it.url }) { feed ->
                        CuratedFeedRow(
                            feed = feed,
                            following = isFollowing(feed),
                            pending = feed.url in pending,
                            error = failures[feed.url],
                            onAdd = { follow(feed) },
                        )
                        if (feed != collection.feeds.last()) {
                            HorizontalDivider(Modifier.padding(start = 16.dp))
                        }
                    }
                    item { SourceFooter(result.catalog, state) }
                }
            }
        }
    }
}

@Composable
private fun CatalogIntro() {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "Hand-picked feeds",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Start with a whole topic or a country's news. This catalog is " +
                "based on the Awesome RSS Feeds collection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CollectionRow(collection: CuratedCollection, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (val badge = curatedCollectionBadge(collection)) {
                        is CuratedCollectionBadge.Symbol -> MaterialSymbol(
                            glyph = badge.glyph,
                            contentDescription = null,
                        )

                        is CuratedCollectionBadge.Flag -> Text(
                            text = badge.emoji,
                            fontSize = 22.sp,
                        )

                        null -> MaterialSymbol(
                            glyph = MaterialSymbols.RssFeed,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        headlineContent = {
            Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = if (collection.feeds.size == 1) {
                    "1 feed"
                } else {
                    "${collection.feeds.size} feeds"
                },
            )
        },
        trailingContent = {
            MaterialSymbol(
                glyph = MaterialSymbols.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun CuratedFeedRow(
    feed: CuratedFeed,
    following: Boolean,
    pending: Boolean,
    error: String?,
    onAdd: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(feed.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    text = feed.description.ifBlank { feedHost(feed.url) },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                error?.let {
                    Text(
                        text = "Couldn't add: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            when {
                following -> Text(
                    text = "Following",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                pending -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )

                else -> TextButton(
                    onClick = onAdd,
                    modifier = Modifier.semantics {
                        contentDescription = if (error != null) {
                            "Retry adding ${feed.title}"
                        } else {
                            "Add ${feed.title}"
                        }
                    },
                ) {
                    Text(if (error != null) "Retry" else "Add")
                }
            }
        },
    )
}

@Composable
private fun SourceFooter(catalog: CuratedCatalog, state: AppState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val license = if (catalog.license.isNotBlank()) " (${catalog.license})" else ""
        Text(
            text = "Based on the Awesome RSS Feeds collection$license.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { state.platform.openUrl(catalog.source, false) }) {
            Text("View the collection source")
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The self-link URLs of every followed feed, for marking curated feeds. */
private suspend fun subscribedUrls(state: AppState): Set<String> {
    val feeds = state.db.feed.selectAll()
    if (feeds.isEmpty()) return emptySet()
    return state.db.link.selectAllByFeedId(feeds.map { it.id })
        .values
        .flatten()
        .filter { it.rel == AtomLinkRel.Self }
        .map { it.href }
        .toSet()
}
