@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import org.vestifeed.util.toUrl

@Composable
fun FeedsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<FeedTable.Feed>>(emptyList()) }
    var unread by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var newFeedUrl by remember { mutableStateOf("") }
    var opmlText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Add feed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFeedUrl,
                onValueChange = { newFeedUrl = it },
                label = { Text("Feed URL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy && newFeedUrl.isNotBlank(),
                onClick = {
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
                            refreshKey++
                        } catch (t: Throwable) {
                            message = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Add") }
        }

        Spacer(Modifier.height(20.dp))
        Text("Import OPML", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = opmlText,
            onValueChange = { opmlText = it },
            label = { Text("OPML XML") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(
                enabled = !busy && opmlText.isNotBlank(),
                onClick = {
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
                                        state.db.link.insertForFeed(
                                            result.feed.id,
                                            result.feedLinks,
                                        )
                                        state.db.entry.insertOrReplace(
                                            result.entries.map { it.first },
                                        )
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
                            refreshKey++
                        } catch (t: Throwable) {
                            message = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Import") }

            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
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
                                    xmlUrl = links
                                        .firstOrNull { it.rel == AtomLinkRel.Self }?.href,
                                    htmlUrl = links
                                        .firstOrNull { it.rel == AtomLinkRel.Alternate }?.href,
                                    extOpenEntriesInBrowser = feed.extOpenEntriesInBrowser,
                                    extShowPreviewImages = feed.extShowPreviewImages,
                                    extBlockedWords = feed.extBlockedWords,
                                )
                            },
                        )
                        state.platform.shareText(document.toXml())
                    }
                },
            ) { Text("Export OPML") }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
        }

        Text("Feeds (${feeds.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        feeds.forEach { feed ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(feed.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${unread[feed.id] ?: 0} unread",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { state.navigate(Screen.FeedSettings(feed.id)) }) {
                        Text("Settings")
                    }
                    TextButton(
                        onClick = {
                            state.navigate(Screen.Entries(EntriesList.BelongToFeed(feed.id)))
                        },
                    ) { Text("Entries") }
                }
            }
        }
    }
}
