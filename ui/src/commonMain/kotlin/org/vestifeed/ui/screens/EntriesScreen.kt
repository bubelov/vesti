@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.platform.proxiedUrl
import org.vestifeed.ui.AppState
import org.vestifeed.ui.Screen
import org.vestifeed.ui.entries.EntryRow
import org.vestifeed.ui.entries.emptyMessage
import org.vestifeed.ui.entries.load
import org.vestifeed.ui.entries.toRow

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
        loading = false
    }

    // Reload once a background sync finishes so newly synced entries appear.
    LaunchedEffect(running) {
        if (wasRunning && !running) refreshKey++
        wasRunning = running
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                state.sync.clearError()
                state.sync.runInBackground()
            }) {
                Text(if (running) "Syncing…" else "Refresh")
            }
        }

        lastError?.let {
            Text(
                text = "Sync failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            loading && rows.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            rows.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Text(screen.list.emptyMessage(feedCount)) }

            else -> LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { row ->
                    EntryCard(
                        row = row,
                        isBookmarked = bookmarked[row.id] == true,
                        onClick = {
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
                        },
                        onToggleBookmark = {
                            scope.launch {
                                val next = bookmarked[row.id] != true
                                state.db.entry.updateBookmarkedAndBookmarkedSynced(row.id, next, false)
                                bookmarked = bookmarked + (row.id to next)
                                state.sync.runInBackground()
                            }
                        },
                    )
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

@Composable
private fun EntryCard(
    row: EntryRow,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column {
            if (row.showImage && row.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = proxiedUrl(row.imageUrl),
                    contentDescription = null,
                    contentScale = if (row.cropImage) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
            Column(Modifier.padding(16.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.summary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stripHtml(row.summary),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onToggleBookmark) {
                    Text(if (isBookmarked) "★ Saved" else "☆ Save")
                }
            }
        }
    }
}
}
