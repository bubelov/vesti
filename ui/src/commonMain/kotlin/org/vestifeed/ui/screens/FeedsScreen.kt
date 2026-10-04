@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.vestifeed.backend.backend
import org.vestifeed.db.table.FeedTable
import org.vestifeed.opml.OpmlDocument
import org.vestifeed.opml.OpmlOutline
import org.vestifeed.opml.OpmlVersion
import org.vestifeed.opml.leafOutlines
import org.vestifeed.opml.toOpml
import org.vestifeed.opml.toXml
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.ui.AppState
import org.vestifeed.ui.EntriesList
import org.vestifeed.ui.Screen
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols
import org.vestifeed.util.toUrl

/** The reading width of the screen's content, centered on wide (desktop) windows. */
private val ContentWidth = 720.dp

/**
 * Feed management: follow a new feed, browse the subscriptions with their unread
 * counts, and import/export OPML. The primary content (the feed list) gets the
 * most space; the OPML tools are tucked behind a toggle so they don't dominate.
 */
@Composable
fun FeedsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<FeedTable.Feed>>(emptyList()) }
    var unread by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var newFeedUrl by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var opmlText by remember { mutableStateOf("") }
    var opmlError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    suspend fun reload() {
        val loaded = state.withDb { state.db.feed.selectAll() }
        feeds = loaded
        unread = state.withDb {
            loaded.associate { feed ->
                feed.id to state.db.entry.selectByFeedId(feed.id).count { !it.extRead }
            }
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    // The add-feed dialog is owned by the app bar; drop the request when this
    // screen leaves so it doesn't pop up on the next visit.
    DisposableEffect(Unit) {
        onDispose {
            state.addFeedDialogVisible = false
            state.opmlImportDialogVisible = false
            state.opmlExportRequested = false
        }
    }

    fun addFeed() {
        scope.launch {
            busy = true
            dialogError = null
            message = null
            state.addFeedByUrl(newFeedUrl)
                .onSuccess { feed ->
                    newFeedUrl = ""
                    message = "Added ${feed.title}"
                    messageIsError = false
                    refreshKey++
                    state.addFeedDialogVisible = false
                }
                .onFailure { dialogError = it.message ?: it.toString() }
            busy = false
        }
    }

    fun removeFeed(feedId: String, title: String) {
        scope.launch {
            busy = true
            message = null
            try {
                // Best-effort server-side delete; the local rows go either way.
                state.withDb {
                    runCatching { backend(state.db).deleteFeed(feedId) }
                    state.db.transaction {
                        state.db.link.deleteForFeed(feedId)
                        state.db.entry.deleteByFeedId(feedId)
                        state.db.feed.deleteById(feedId)
                    }
                }
                state.refreshUnreadCount()
                message = "Removed $title"
                messageIsError = false
                refreshKey++
            } catch (t: Throwable) {
                message = t.message ?: t.toString()
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun importOpml() {
        scope.launch {
            busy = true
            opmlError = null
            message = null
            try {
                val outlines = opmlText.toOpml().leafOutlines()
                val feedBackend = backend(state.db)
                var count = 0
                for (outline in outlines) {
                    val xmlUrl = outline.xmlUrl ?: continue
                    if (xmlUrl.isBlank()) continue
                    try {
                        val result = state.withDb { feedBackend.addFeed(xmlUrl.toUrl(), null) }
                        state.withDb {
                            state.db.transaction {
                                state.db.feed.insertOrReplace(result.feed)
                                state.db.link.insertForFeed(result.feed.id, result.feedLinks)
                                state.db.entry.insertOrReplace(result.entries.map { it.first })
                                result.entries.forEach { (entry, entryLinks) ->
                                    state.db.link.insertForEntry(entry.id, entryLinks)
                                }
                            }
                        }
                        count++
                    } catch (_: Throwable) {
                        // Skip feeds that fail to import; report the count.
                    }
                }
                opmlText = ""
                message = "Imported $count feed(s)"
                messageIsError = false
                refreshKey++
                state.opmlImportDialogVisible = false
            } catch (t: Throwable) {
                opmlError = t.message ?: t.toString()
            } finally {
                busy = false
            }
        }
    }

    fun exportOpml() {
        scope.launch {
            val (loaded, linksByFeed) = state.withDb {
                val loaded = state.db.feed.selectAll()
                loaded to state.db.link.selectAllByFeedId(loaded.map { it.id })
            }
            val document = OpmlDocument(
                version = OpmlVersion.V_2_0,
                outlines = loaded.map { feed ->
                    val links = linksByFeed[feed.id].orEmpty()
                    OpmlOutline(
                        text = feed.title,
                        outlines = emptyList(),
                        xmlUrl = links.firstOrNull { it.rel == AtomLinkRel.Self }?.href,
                        htmlUrl = links.firstOrNull { it.rel == AtomLinkRel.Alternate }?.href,
                        extOpenEntriesInBrowser = feed.extOpenEntriesInBrowser,
                        extShowPreviewImages = feed.extShowPreviewImages,
                        extBlockedWords = feed.extBlockedWords,
                    )
                },
            )
            state.platform.shareText(document.toXml())
        }
    }

    // Consume the app-bar "Export OPML" request.
    LaunchedEffect(state.opmlExportRequested) {
        if (state.opmlExportRequested) {
            state.opmlExportRequested = false
            exportOpml()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentWidth)
                .fillMaxSize(),
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            message?.let { MessageBanner(it, messageIsError) }

            if (feeds.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyFeeds(
                        onAddFeed = { state.addFeedDialogVisible = true },
                        onBrowseCurated = { state.navigate(Screen.CuratedFeeds) },
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(feeds, key = { _, feed -> feed.id }) { index, feed ->
                        FeedRow(
                            title = feed.title,
                            unread = unread[feed.id] ?: 0,
                            onOpen = {
                                state.navigate(Screen.Entries(EntriesList.BelongToFeed(feed.id)))
                            },
                            onSettings = { state.navigate(Screen.FeedSettings(feed.id)) },
                            onRemove = { removeFeed(feed.id, feed.title) },
                        )
                        if (index != feeds.lastIndex) {
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }

    if (state.addFeedDialogVisible) {
        AddFeedDialog(
            url = newFeedUrl,
            onUrlChange = {
                newFeedUrl = it
                dialogError = null
            },
            busy = busy,
            error = dialogError,
            onDismiss = { state.addFeedDialogVisible = false },
            onAdd = ::addFeed,
            onBrowseCurated = {
                state.addFeedDialogVisible = false
                state.navigate(Screen.CuratedFeeds)
            },
        )
    }

    if (state.opmlImportDialogVisible) {
        OpmlImportDialog(
            xml = opmlText,
            onXmlChange = {
                opmlText = it
                opmlError = null
            },
            busy = busy,
            error = opmlError,
            onDismiss = { state.opmlImportDialogVisible = false },
            onImport = ::importOpml,
        )
    }
}

@Composable
private fun AddFeedDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onBrowseCurated: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add feed") },
        text = {
            Column {
                Text(
                    text = "Paste an RSS or Atom URL to follow a new feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    enabled = !busy,
                    onClick = onBrowseCurated,
                    contentPadding = PaddingValues(start = 0.dp, end = 12.dp),
                ) {
                    Text("Browse curated feeds")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (!busy && url.isNotBlank()) onAdd() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && url.isNotBlank(), onClick = onAdd) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FeedRow(
    title: String,
    unread: Int,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { FeedBadge(title = title, unread = unread) },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(if (unread == 1) "1 unread entry" else "$unread unread entries")
        },
        trailingContent = {
            // Nudge right by 12dp so the icon lines up with the app bar's
            // overflow (the list item keeps 16dp end padding, the bar uses 4dp).
            Box(modifier = Modifier.offset(x = 12.dp)) {
                IconButton(onClick = { menuExpanded = true }) {
                    MaterialSymbol(MaterialSymbols.MoreVert, contentDescription = "Feed options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Feed settings") },
                        onClick = {
                            menuExpanded = false
                            onSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove feed") },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun FeedBadge(title: String, unread: Int) {
    val container = if (unread > 0) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (unread > 0) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = feedBadgeLabel(title),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun EmptyFeeds(
    onAddFeed: () -> Unit,
    onBrowseCurated: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No feeds yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add your first feed by URL, or pick a few from the " +
                "Awesome RSS Feeds collection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddFeed) { Text("Add feed by URL") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBrowseCurated) { Text("Browse curated feeds") }
    }
}

@Composable
private fun MessageBanner(message: String, isError: Boolean) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun OpmlImportDialog(
    xml: String,
    onXmlChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import OPML") },
        text = {
            Column {
                Text(
                    text = "Paste the contents of an OPML file to add its feeds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = xml,
                    onValueChange = onXmlChange,
                    label = { Text("OPML XML") },
                    minLines = 4,
                    maxLines = 8,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && xml.isNotBlank(), onClick = onImport) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Import")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
