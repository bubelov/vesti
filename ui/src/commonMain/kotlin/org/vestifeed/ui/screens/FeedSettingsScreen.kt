@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import org.vestifeed.backend.backend
import org.vestifeed.db.table.FeedTable
import org.vestifeed.feedsettings.exportFeedSettings
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

@Composable
fun FeedSettingsScreen(state: AppState, feedId: String) {
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<FeedTable.Feed?>(null) }
    var title by remember { mutableStateOf("") }
    var blockedWords by remember { mutableStateOf("") }
    var showImages by remember { mutableStateOf<Boolean?>(null) }
    var openInBrowser by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(feedId) {
        val loadedFeed = state.withDb { state.db.feed.selectById(feedId) }
        feed = loadedFeed
        title = loadedFeed?.title ?: ""
        blockedWords = loadedFeed?.extBlockedWords ?: ""
        showImages = loadedFeed?.extShowPreviewImages
        openInBrowser = loadedFeed?.extOpenEntriesInBrowser
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Text("Preview images", style = MaterialTheme.typography.titleMedium)
        PreviewOption("Follow global", showImages == null) { showImages = null }
        PreviewOption("Show", showImages == true) { showImages = true }
        PreviewOption("Hide", showImages == false) { showImages = false }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = blockedWords,
            onValueChange = { blockedWords = it },
            label = { Text("Blocked words (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Open entries in browser", modifier = Modifier.weight(1f))
            Switch(checked = openInBrowser == true, onCheckedChange = { openInBrowser = it })
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    val current = feed ?: return@launch
                    val updated = current.copy(
                        title = title,
                        extBlockedWords = blockedWords,
                        extShowPreviewImages = showImages,
                        extOpenEntriesInBrowser = openInBrowser,
                    )
                    state.withDb {
                        state.db.feed.insertOrReplace(updated)
                        runCatching { backend(state.db, state.userAgent).updateFeedTitle(feedId, title) }
                    }
                    feed = updated
                    message = "Saved"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            MaterialSymbol(MaterialSymbols.Save, contentDescription = null, size = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("Save")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    val current = feed ?: return@launch
                    val json = exportFeedSettings(listOf(current), state.conf.backend)
                    state.platform.shareText(json)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            MaterialSymbol(MaterialSymbols.Download, contentDescription = null, size = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("Export settings")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    state.withDb {
                        runCatching { backend(state.db, state.userAgent).deleteFeed(feedId) }
                        state.db.transaction {
                            state.db.link.deleteForFeed(feedId)
                            state.db.entry.deleteByFeedId(feedId)
                            state.db.feed.deleteById(feedId)
                        }
                    }
                    state.pop()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            MaterialSymbol(MaterialSymbols.Delete, contentDescription = null, size = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("Delete feed")
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it)
        }
    }
}

@Composable
private fun PreviewOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
