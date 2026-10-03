@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.vestifeed.ui.theme.vestiCardBorder
import org.vestifeed.ui.theme.vestiCardContainer
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
    var opmlText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    suspend fun reload() {
        val loaded = state.db.feed.selectAll()
        feeds = loaded
        unread = loaded.associate { feed ->
            feed.id to state.db.entry.selectByFeedId(feed.id).count { !it.extRead }
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    fun addFeed() {
        scope.launch {
            busy = true
            message = null
            try {
                val result = backend(state.db).addFeed(newFeedUrl.trim().toUrl(), null)
                state.db.transaction {
                    state.db.feed.insertOrReplace(result.feed)
                    state.db.link.insertForFeed(result.feed.id, result.feedLinks)
                    state.db.entry.insertOrReplace(result.entries.map { it.first })
                    result.entries.forEach { (entry, entryLinks) ->
                        state.db.link.insertForEntry(entry.id, entryLinks)
                    }
                }
                newFeedUrl = ""
                message = "Added ${result.feed.title}"
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
            message = null
            try {
                val outlines = opmlText.toOpml().leafOutlines()
                val feedBackend = backend(state.db)
                var count = 0
                for (outline in outlines) {
                    val xmlUrl = outline.xmlUrl ?: continue
                    if (xmlUrl.isBlank()) continue
                    try {
                        val result = feedBackend.addFeed(xmlUrl.toUrl(), null)
                        state.db.transaction {
                            state.db.feed.insertOrReplace(result.feed)
                            state.db.link.insertForFeed(result.feed.id, result.feedLinks)
                            state.db.entry.insertOrReplace(result.entries.map { it.first })
                            result.entries.forEach { (entry, entryLinks) ->
                                state.db.link.insertForEntry(entry.id, entryLinks)
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
            } catch (t: Throwable) {
                message = t.message ?: t.toString()
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun exportOpml() {
        scope.launch {
            val loaded = state.db.feed.selectAll()
            val linksByFeed = state.db.link.selectAllByFeedId(loaded.map { it.id })
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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
            }

            AddFeedSection(
                url = newFeedUrl,
                onUrlChange = { newFeedUrl = it },
                busy = busy,
                onAdd = ::addFeed,
            )

            message?.let { MessageBanner(it, messageIsError) }

            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Feeds", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${feeds.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (feeds.isEmpty()) {
                EmptyFeeds()
            } else {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = vestiCardContainer,
                    border = vestiCardBorder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        feeds.forEachIndexed { index, feed ->
                            FeedRow(
                                title = feed.title,
                                unread = unread[feed.id] ?: 0,
                                onOpen = {
                                    state.navigate(Screen.Entries(EntriesList.BelongToFeed(feed.id)))
                                },
                                onSettings = { state.navigate(Screen.FeedSettings(feed.id)) },
                            )
                            if (index != feeds.lastIndex) {
                                HorizontalDivider(Modifier.padding(start = 72.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            OpmlSection(
                opmlText = opmlText,
                onOpmlChange = { opmlText = it },
                busy = busy,
                onImport = ::importOpml,
                onExport = ::exportOpml,
            )
        }
    }
}

@Composable
private fun AddFeedSection(
    url: String,
    onUrlChange: (String) -> Unit,
    busy: Boolean,
    onAdd: () -> Unit,
) {
    Text("Add feed", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Paste an RSS or Atom URL to follow a new feed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Feed URL") },
            placeholder = { Text("https://example.com/feed.xml") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Button(
            enabled = !busy && url.isNotBlank(),
            onClick = onAdd,
            modifier = Modifier.height(56.dp),
        ) {
            MaterialSymbol(MaterialSymbols.Add, contentDescription = null, size = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("Add")
        }
    }
}

@Composable
private fun FeedRow(
    title: String,
    unread: Int,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { UnreadBadge(unread) },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(if (unread == 1) "1 unread entry" else "$unread unread entries")
        },
        trailingContent = {
            IconButton(onClick = onSettings) {
                MaterialSymbol(MaterialSymbols.Tune, contentDescription = "Feed settings")
            }
        },
    )
}

@Composable
private fun UnreadBadge(count: Int) {
    val container = if (count > 0) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (count > 0) {
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
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun EmptyFeeds() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = vestiCardContainer,
        border = vestiCardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No feeds yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Add your first feed above to start reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun OpmlSection(
    opmlText: String,
    onOpmlChange: (String) -> Unit,
    busy: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("OPML", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Move your subscriptions between readers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide" else "Import / Export")
        }
    }

    if (expanded) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = opmlText,
            onValueChange = onOpmlChange,
            label = { Text("OPML XML") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && opmlText.isNotBlank(),
                onClick = onImport,
            ) {
                MaterialSymbol(MaterialSymbols.Upload, contentDescription = null, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Import")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = onExport,
            ) {
                MaterialSymbol(MaterialSymbols.Download, contentDescription = null, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Export")
            }
        }
    }
}
