@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.vestifeed.db.table.EntryTable
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.platform.proxiedUrl
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/**
 * The entry reader. It renders the body as a sequence of prose paragraphs and
 * inline pictures; the entry's actions (bookmark, open, share, mark read) live
 * in the app bar, which reads the state published here by [reload]. The app-bar
 * Search action opens an in-entry find bar that highlights matches in the prose.
 */
@Composable
fun EntryScreen(state: AppState, entryId: String) {
    var entry by remember { mutableStateOf<EntryTable.Entry?>(null) }
    var feedTitle by remember { mutableStateOf("") }

    // In-entry find state. Local because only this screen uses it.
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(0) }

    val scrollState = rememberScrollState()
    var containerTop by remember { mutableStateOf(0f) }
    val textLayouts = remember { mutableMapOf<Int, TextLayoutResult>() }
    val textTops = remember { mutableMapOf<Int, Float>() }

    suspend fun reload() {
        val loaded = state.db.entry.selectById(entryId)
        entry = loaded
        // Publish what the app bar needs to render and perform this entry's
        // actions.
        state.entryTitle = loaded?.title.orEmpty()
        state.entryBookmarked = loaded?.extBookmarked ?: false
        state.entryRead = loaded?.extRead ?: false
        val links = state.db.link.selectByEntryId(entryId)
        state.entryHref = links.firstOrNull {
            it.rel == AtomLinkRel.Alternate && it.type?.startsWith("text/html") == true
        }?.href ?: links.firstOrNull { it.rel == AtomLinkRel.Alternate }?.href
        feedTitle = loaded?.let { state.db.feed.selectById(it.feedId)?.title } ?: ""
    }

    LaunchedEffect(entryId) { reload() }

    // Clear the find state when switching entries or closing the bar.
    LaunchedEffect(entryId, state.entrySearchVisible) {
        if (!state.entrySearchVisible) searchQuery = ""
        currentMatch = 0
    }

    // The app bar owns the find bar's visibility; hide it when this screen leaves.
    DisposableEffect(Unit) {
        onDispose { state.entrySearchVisible = false }
    }

    val current = entry
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val body = current.contentText?.takeIf { it.isNotBlank() } ?: current.summary.orEmpty()
    val blocks = remember(current.id, body) { parseEntryBlocks(body) }

    // Find matches live inside paragraphs; images are skipped.
    val matchesByBlock = remember(blocks, searchQuery) {
        blocks.map { block ->
            if (block is EntryBlock.Paragraph) findMatches(block.text, searchQuery) else emptyList()
        }
    }
    val totalMatches = matchesByBlock.sumOf { it.size }
    // Global match order -> (block index, local index, range), matching the
    // highlight order in the rendered paragraphs.
    val globalMatches = remember(matchesByBlock) {
        buildList {
            matchesByBlock.forEachIndexed { blockIndex, ranges ->
                ranges.forEachIndexed { localIndex, range ->
                    add(Triple(blockIndex, localIndex, range))
                }
            }
        }
    }

    LaunchedEffect(searchQuery) { currentMatch = 0 }
    LaunchedEffect(totalMatches) { if (currentMatch >= totalMatches) currentMatch = 0 }

    val matchStyle = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    val currentStyle = SpanStyle(
        background = MaterialTheme.colorScheme.primary,
        color = MaterialTheme.colorScheme.onPrimary,
    )

    // Scroll the current match into view. Each paragraph's layout and its
    // root-relative top let us land on the right position in the scroll content.
    LaunchedEffect(globalMatches, currentMatch) {
        val (blockIndex, _, range) = globalMatches.getOrNull(currentMatch) ?: return@LaunchedEffect
        val layout = textLayouts[blockIndex] ?: return@LaunchedEffect
        val top = textTops[blockIndex] ?: return@LaunchedEffect
        val target = (top - containerTop) + scrollState.value +
            layout.getBoundingBox(range.first).top - WithMargin
        scrollState.animateScrollTo(target.toInt().coerceIn(0, scrollState.maxValue))
    }

    Column(Modifier.fillMaxSize()) {
        if (state.entrySearchVisible) {
            EntrySearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = totalMatches,
                currentMatch = if (totalMatches == 0) 0 else currentMatch + 1,
                onPrev = {
                    if (totalMatches > 0) {
                        currentMatch = (currentMatch - 1 + totalMatches) % totalMatches
                    }
                },
                onNext = {
                    if (totalMatches > 0) currentMatch = (currentMatch + 1) % totalMatches
                },
                onClose = { state.entrySearchVisible = false },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { containerTop = it.positionInRoot().y }
                .verticalScroll(scrollState)
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
            if (blocks.isEmpty()) {
                Text("(No content)", style = MaterialTheme.typography.bodyMedium)
            } else {
                blocks.forEachIndexed { blockIndex, block ->
                    if (blockIndex > 0) Spacer(Modifier.height(BlockSpacing))
                    when (block) {
                        is EntryBlock.Paragraph -> {
                            val localMatches = matchesByBlock[blockIndex]
                            val currentLocal = globalMatches.getOrNull(currentMatch)
                                ?.takeIf { it.first == blockIndex }
                                ?.second
                                ?: -1
                            val annotated = if (localMatches.isEmpty()) {
                                AnnotatedString(block.text)
                            } else {
                                highlightMatches(
                                    block.text,
                                    localMatches,
                                    currentLocal,
                                    matchStyle,
                                    currentStyle,
                                )
                            }
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodyLarge,
                                onTextLayout = { textLayouts[blockIndex] = it },
                                modifier = Modifier.onGloballyPositioned {
                                    textTops[blockIndex] = it.positionInRoot().y
                                },
                            )
                        }

                        is EntryBlock.Image -> EntryImage(block)
                    }
                }
            }
        }
    }
}

/** Keep a little space above the scrolled-to match. */
private const val WithMargin = 24f

private val BlockSpacing = 16.dp

@Composable
private fun EntryImage(block: EntryBlock.Image) {
    // Aspect ratio is unknown until the picture loads; assume 16:9 in the
    // meantime, then snap to the real ratio so nothing is cropped or letterboxed.
    var aspectRatio by remember(block.url) { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = proxiedUrl(block.url),
            contentDescription = block.caption,
            contentScale = ContentScale.FillWidth,
            onSuccess = { success ->
                val image = success.result.image
                if (image.width > 0 && image.height > 0) {
                    aspectRatio = image.width.toFloat() / image.height.toFloat()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio ?: (16f / 9f))
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        block.caption?.let { caption ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EntrySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Find in entry") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (matchCount == 0) "0/0" else "$currentMatch/$matchCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            MaterialSymbol(MaterialSymbols.ArrowBack, contentDescription = "Previous match")
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            MaterialSymbol(
                glyph = MaterialSymbols.ArrowBack,
                contentDescription = "Next match",
                modifier = Modifier.rotate(180f),
            )
        }
        IconButton(onClick = onClose) {
            MaterialSymbol(MaterialSymbols.Close, contentDescription = "Close search")
        }
    }
}
