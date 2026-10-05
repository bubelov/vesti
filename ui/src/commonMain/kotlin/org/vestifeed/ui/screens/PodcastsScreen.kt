package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vestifeed.db.table.LinkTable
import org.vestifeed.platform.proxiedUrl
import org.vestifeed.ui.AppState
import org.vestifeed.ui.Screen
import org.vestifeed.ui.entries.formatCalendarDate
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/** The reading width of the screen's content, centered on wide (desktop) windows. */
private val ContentWidth = 720.dp

/** How often the now-playing bar refreshes its position. */
private const val PositionPollMillis = 500L

/** Polls to wait for playback to start before giving up (~5s). */
private const val StartTimeoutAttempts = 10

/**
 * The Podcasts tab: every audio enclosure across the feeds, newest first. The
 * play button downloads the episode (when needed) and plays it in-app — or,
 * when the built-in player is disabled, hands it to the system's default media
 * player (or the browser where the host has none). The bar at the bottom scrubs
 * the episode that is currently playing. Tapping a row itself opens the entry.
 */
@Composable
fun PodcastsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LinkTable.AudioEnclosureRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playingLinkId by remember { mutableStateOf<Long?>(null) }
    var preparingLinkId by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        rows = state.withDb { state.db.link.selectAudioEnclosureRows() }
        loading = false
    }

    // Never keep playing after the user leaves the tab.
    DisposableEffect(Unit) {
        onDispose { state.platform.stopAudio() }
    }

    fun stop() {
        state.platform.stopAudio()
        playingLinkId = null
        positionMs = 0L
        durationMs = 0L
    }

    // Follow the player while something is playing. Position is null until the
    // player is ready, so wait for a first reading before treating null as
    // "finished"; give up if it never starts.
    LaunchedEffect(playingLinkId) {
        var started = false
        var attempts = 0
        while (playingLinkId != null) {
            val position = state.platform.audioPositionMs()
            val duration = state.platform.audioDurationMs()
            if (position != null) started = true
            val ended = (started && position == null) ||
                (started && duration != null && duration > 0 && position != null && position >= duration) ||
                (!started && attempts >= StartTimeoutAttempts)
            if (ended) {
                stop()
                break
            }
            positionMs = position ?: positionMs
            durationMs = duration ?: durationMs
            attempts++
            delay(PositionPollMillis)
        }
    }

    fun onPlay(row: LinkTable.AudioEnclosureRow) {
        if (playingLinkId == row.linkId) {
            stop()
            return
        }
        if (preparingLinkId != null) return

        if (!state.conf.useBuiltInAudioPlayer) {
            // Hand the enclosure to the host's external player. On desktop that
            // is the system's default media player, so download it first; on
            // Android and the browser it is the browser, opened synchronously so
            // the browser still counts it as a user gesture.
            if (state.platform.supportsExternalAudioPlayer) {
                scope.launch {
                    preparingLinkId = row.linkId
                    progress = row.extEnclosureDownloadProgress
                    state.platform.openAudioExternally(
                        url = proxiedUrl(row.href),
                        useBuiltInBrowser = state.conf.useBuiltInBrowser,
                    ) { progress = it }
                    preparingLinkId = null
                    progress = null
                }
            } else {
                state.platform.openUrl(proxiedUrl(row.href), state.conf.useBuiltInBrowser)
            }
            return
        }

        scope.launch {
            preparingLinkId = row.linkId
            progress = row.extEnclosureDownloadProgress
            val uri = state.platform.cacheAudio(proxiedUrl(row.href)) { progress = it }
            preparingLinkId = null
            progress = null
            if (uri != null) {
                state.withDb { state.db.link.updateEnclosureProgress(row.linkId, null, uri) }
                rows = rows.map { if (it.linkId == row.linkId) it.copy(extCacheUri = uri) else it }
                state.platform.playAudio(uri)
                playingLinkId = row.linkId
                positionMs = 0L
                durationMs = state.platform.audioDurationMs() ?: 0L
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentWidth).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No podcasts",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.linkId }) { row ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    state.navigate(Screen.EntryDetail(row.entryId))
                                },
                                leadingContent = {
                                    MaterialSymbol(
                                        glyph = MaterialSymbols.Podcasts,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                headlineContent = {
                                    Text(row.entryTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text("${row.feedTitle} · ${formatCalendarDate(row.entryPublished)}")
                                },
                                trailingContent = {
                                    when {
                                        preparingLinkId == row.linkId -> {
                                            val value = progress
                                            // Keep the spinner in the same 48.dp
                                            // footprint as the IconButton it
                                            // replaces so the row doesn't shift.
                                            Box(
                                                modifier = Modifier.size(48.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (value != null) {
                                                    CircularProgressIndicator(
                                                        progress = { value.toFloat() },
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                } else {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                            }
                                        }

                                        playingLinkId == row.linkId -> IconButton(
                                            onClick = { onPlay(row) },
                                        ) {
                                            MaterialSymbol(
                                                glyph = MaterialSymbols.Stop,
                                                contentDescription = "Stop",
                                            )
                                        }

                                        else -> IconButton(onClick = { onPlay(row) }) {
                                            MaterialSymbol(
                                                glyph = MaterialSymbols.PlayArrow,
                                                contentDescription = "Play",
                                            )
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }

            val playing = rows.firstOrNull { it.linkId == playingLinkId }
            if (playing != null && durationMs > 0L) {
                NowPlayingBar(
                    title = playing.entryTitle,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = {
                        state.platform.seekAudio(it)
                        positionMs = it
                    },
                    onStop = ::stop,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBar(
    title: String,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    // While dragging, follow the thumb; otherwise follow the player.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1L).toFloat()

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onStop) {
                    MaterialSymbol(MaterialSymbols.Stop, contentDescription = "Stop")
                }
                Text(
                    text = formatDuration(dragValue?.toLong() ?: positionMs),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = (dragValue ?: positionMs.toFloat()).coerceIn(0f, max),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { onSeek(it.toLong()) }
                        dragValue = null
                    },
                    valueRange = 0f..max,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** `m:ss` for a millisecond duration. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
