@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

@Composable
fun EntryScreen(state: AppState, entryId: String) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf<EntryTable.Entry?>(null) }
    var links by remember { mutableStateOf<List<LinkTable.Link>>(emptyList()) }
    var feedTitle by remember { mutableStateOf("") }

    suspend fun reload() {
        val loaded = state.db.entry.selectById(entryId)
        entry = loaded
        links = state.db.link.selectByEntryId(entryId)
        feedTitle = loaded?.let { state.db.feed.selectById(it.feedId)?.title } ?: ""
    }

    LaunchedEffect(entryId) { reload() }

    val current = entry
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val href = links.firstOrNull {
        it.rel == AtomLinkRel.Alternate && it.type?.startsWith("text/html") == true
    }?.href ?: links.firstOrNull { it.rel == AtomLinkRel.Alternate }?.href

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(current.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))

        val meta = buildString {
            if (feedTitle.isNotBlank()) append(feedTitle)
            if (current.authorName.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(current.authorName)
            }
            if (isNotEmpty()) append(" · ")
            append(current.published.toString().substringBefore('T'))
        }
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        val body = current.contentText?.takeIf { it.isNotBlank() } ?: current.summary.orEmpty()
        if (body.isNotBlank()) {
            Text(stripHtml(body), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("(No content)", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        if (href != null) {
            OutlinedButton(
                onClick = {
                    state.platform.openUrl(href)
                    scope.launch {
                        state.db.entry.updateReadAndReadSynced(entryId, true, false)
                        state.sync.runInBackground()
                        reload()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                MaterialSymbol(MaterialSymbols.OpenInNew, contentDescription = null, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Open in browser")
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = { state.platform.shareText(current.title + "\n" + (href ?: "")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            MaterialSymbol(MaterialSymbols.Share, contentDescription = null, size = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    state.db.entry.updateReadAndReadSynced(entryId, !current.extRead, false)
                    state.sync.runInBackground()
                    reload()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            MaterialSymbol(
                glyph = if (current.extRead) MaterialSymbols.MarkEmailUnread else MaterialSymbols.MarkEmailRead,
                contentDescription = null,
                size = 18.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (current.extRead) "Mark as unread" else "Mark as read")
        }
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    state.db.entry.updateBookmarkedAndBookmarkedSynced(
                        entryId,
                        !current.extBookmarked,
                        false,
                    )
                    state.sync.runInBackground()
                    reload()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            MaterialSymbol(
                glyph = if (current.extBookmarked) MaterialSymbols.BookmarkAdded else MaterialSymbols.BookmarkAdd,
                contentDescription = null,
                size = 18.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (current.extBookmarked) "Remove bookmark" else "Bookmark")
        }
    }
}
