package org.vestifeed.entries

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vestifeed.db.Database
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.sync.Sync
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Owns all data loading, mutation and intent routing for the entries screen.
 * The fragment is a thin renderer that collects [state] and forwards user
 * intents to the public methods.
 *
 * IO is always dispatched to [Dispatchers.IO]; the only thing the VM emits
 * to the main thread are immutable state objects and one-shot actions.
 */
class EntriesViewModel(
    val filter: EntriesFilter,
    private val db: Database,
    private val sync: Sync,
    private val resources: Resources,
) : ViewModel() {

    private val _state = MutableStateFlow(EntriesScreenState())
    val state: StateFlow<EntriesScreenState> = _state.asStateFlow()

    private val _actions = MutableSharedFlow<EntriesItemAction>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actions: SharedFlow<EntriesItemAction> = _actions.asSharedFlow()

    /**
     * High-water mark for the OG-image poll. Set right after the initial load
     * so we only count entries whose OG image was fetched after this screen
     * was opened. Updated after each successful poll-driven reload.
     */
    private var ogImageFetchedSince: OffsetDateTime? = null

    init {
        // Reload whenever a sync finishes.
        viewModelScope.launch {
            sync.running.collect { running ->
                _state.update { it.copy(running = running) }
                if (running) {
                    // If a sync kicks off before we have any rows to show,
                    // surface the "initial sync" placeholder instead of the
                    // empty-state message. The Miniflux auth flow starts a
                    // first sync right before navigating here, so without
                    // this we'd briefly flash "you have no feeds" before
                    // any entries landed in the database.
                    if (_state.value.items !is ItemsState.Showing) {
                        _state.update { it.copy(items = ItemsState.InitialSync) }
                    }
                } else {
                    reload()
                }
            }
        }

        // Initial load plus the OG-image poll. The watermark is captured
        // before the first query so we don't miss anything fetched during
        // the initial load itself.
        viewModelScope.launch {
            ogImageFetchedSince = OffsetDateTime.now()
            reloadInternal()

            while (isActive) {
                delay(OG_IMAGE_POLL_INTERVAL)
                val watermark = ogImageFetchedSince ?: continue
                val count = withContext(Dispatchers.IO) {
                    db.entry.countByOgImageFetchedAfter(watermark)
                }
                if (count > 0L) {
                    ogImageFetchedSince = OffsetDateTime.now()
                    if (!sync.running.value) {
                        reload()
                    }
                }
            }
        }
    }

    /**
     * Re-read everything from the database and re-emit state. Safe to call
     * repeatedly; the previous reload job is replaced.
     */
    fun reload() {
        viewModelScope.launch { reloadInternal() }
    }

    private suspend fun reloadInternal() {
        val now = OffsetDateTime.now()
        val rows = withContext(Dispatchers.IO) { filter.loadEntries(db) }
        val feedCount = withContext(Dispatchers.IO) { db.feed.selectAll().size }
        val conf = withContext(Dispatchers.IO) { db.conf.select() }

        val items = rows.map { EntryRowMapper.toItem(it, conf, now, resources) }
        val title = filter.resolveTitle(db)
        val itemsState = when {
            items.isNotEmpty() -> ItemsState.Showing(items)
            // Don't surface the empty-state message while a sync is in
            // flight: rows are about to land in the database and the
            // next reload will pick them up. Showing "you have no feeds"
            // now would contradict the progress indicator and confuse
            // users coming from the Miniflux auth flow.
            sync.running.value -> ItemsState.InitialSync
            else -> ItemsState.Empty(filter.emptyMessageRes(feedCount))
        }

        _state.update { it.copy(title = title.toTitleState(), items = itemsState) }
    }

    /** Pull-to-refresh / swipe-refresh handler. */
    fun refresh() {
        _state.update { it.copy(pullToRefreshInProgress = true) }
        viewModelScope.launch {
            try {
                sync.runInForeground()
            } finally {
                _state.update { it.copy(pullToRefreshInProgress = false) }
            }
        }
    }

    /**
     * User tapped an entry. Marks it read on the IO dispatcher and then asks
     * the fragment (via [actions]) to either open the in-app entry fragment
     * or open the alternate external link in the appropriate browser.
     */
    fun onItemClicked(item: EntriesAdapter.Item) {
        viewModelScope.launch {
            runCatching {
                val unreadIdsSnapshot = if (filter is EntriesFilter.Unread) {
                    withContext(Dispatchers.IO) { db.entry.selectUnreadIds() }
                } else {
                    null
                }

                withContext(Dispatchers.IO) {
                    db.entry.updateReadAndReadSynced(
                        id = item.id,
                        extRead = true,
                        extReadSynced = false,
                    )
                }
                reload()

                if (item.openInBrowser) {
                    val links = withContext(Dispatchers.IO) { db.link.selectByEntryId(item.id) }
                    val alternates = links.filter { it.rel is AtomLinkRel.Alternate }
                    val target = when {
                        alternates.isEmpty() -> null
                        else -> alternates.firstOrNull { it.type == "text/html" } ?: alternates.first()
                    }
                    if (target == null) {
                        _actions.tryEmit(EntriesItemAction.NoExternalLinks)
                    } else {
                        _actions.tryEmit(EntriesItemAction.OpenExternal(target.href, item.useBuiltInBrowser))
                    }
                } else {
                    val action = if (filter is EntriesFilter.Unread && unreadIdsSnapshot != null) {
                        EntriesItemAction.OpenUnreadPager(item.id, unreadIdsSnapshot)
                    } else {
                        EntriesItemAction.OpenEntry(item.id)
                    }
                    _actions.tryEmit(action)
                }
            }
        }
    }

    /**
     * User long-pressed the preview image of an entry. Asks the fragment to
     * open the full image URL in the appropriate browser, respecting the
     * built-in/external browser preference stored on the item.
     */
    fun onImageLongClicked(item: EntriesAdapter.Item) {
        if (item.imageUrl.isEmpty()) return
        _actions.tryEmit(EntriesItemAction.OpenImageExternal(item.imageUrl, item.useBuiltInBrowser))
    }

    /** Persist the read flag and queue a sync so the server sees it. */
    fun setRead(entryId: String, read: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.entry.updateReadAndReadSynced(
                    id = entryId,
                    extRead = read,
                    extReadSynced = false,
                )
            }
            reloadInternal()
            if (!read) {
                _state.update { it.copy(scrollToEntryId = entryId) }
            }
            sync.runInBackground()
        }
    }

    /**
     * Called by the fragment once it has scrolled to the entry requested by
     * [EntriesScreenState.scrollToEntryId], so the field doesn't keep firing
     * the same scroll on every subsequent state emission.
     */
    fun consumeScrollToEntry() {
        _state.update { it.copy(scrollToEntryId = null) }
    }

    /** Persist the bookmark flag and queue a sync so the server sees it. */
    fun setBookmarked(entryId: String, bookmarked: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.entry.updateBookmarkedAndBookmarkedSynced(
                    id = entryId,
                    extBookmarked = bookmarked,
                    extBookmarkedSynced = false,
                )
            }
            reload()
            sync.runInBackground()
        }
    }

    /**
     * Mark every unread, non-bookmarked entry as read in a single bulk
     * UPDATE, refresh the visible list, and queue a sync so the server
     * eventually sees the new read flags. Unsafe to call from the main
     * thread; the DB hit is dispatched to IO internally.
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.entry.markAllUnreadAsRead()
            }
            reload()
            sync.runInBackground()
        }
    }

    private companion object {
        /** How often the entries list asks the DB if any new OG images arrived. */
        val OG_IMAGE_POLL_INTERVAL = 5.seconds
    }
}

private fun EntriesFilter.TitleFormat.toTitleState(): TitleState = when (this) {
    is EntriesFilter.TitleFormat.Custom -> TitleState.Custom(title)
    is EntriesFilter.TitleFormat.Res -> TitleState.Res(resId, args)
}

/**
 * Constructs an [EntriesViewModel] with its dependencies pulled from the
 * application context. Use from the fragment via `by viewModels { ... }`.
 */
class EntriesViewModelFactory(
    private val filter: EntriesFilter,
    private val db: Database,
    private val sync: Sync,
    private val resources: Resources,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == EntriesViewModel::class.java) {
            "Unknown ViewModel class ${modelClass.name}"
        }
        return EntriesViewModel(filter, db, sync, resources) as T
    }
}