package org.vestifeed.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.vestifeed.backend.backend
import org.vestifeed.curated.CuratedCatalog
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.sync.Sync
import org.vestifeed.ui.curated.loadCuratedCatalog
import org.vestifeed.util.toUrl
import org.vestifeed.util.withHttpsScheme

/**
 * The single source of truth the shared Compose UI renders from. It owns the
 * database connection, the current configuration and a tiny back stack. Both
 * hosts (Android and the browser) construct it with their own [Database] and
 * [VestiPlatform].
 */
class AppState(
    val platform: VestiPlatform,
    val db: Database,
    val scope: CoroutineScope,
    val userAgent: String,
) {
    var connected by mutableStateOf(false)
        private set

    var conf by mutableStateOf(ConfTable.defaultConf())
        private set

    /**
     * The number of unread, unbookmarked entries — the size of the Unread list,
     * shown in its app-bar title. Kept fresh by [refreshUnreadCount].
     */
    var unreadCount by mutableStateOf(0)
        private set

    /**
     * Whether any feed has audio enclosures. The Podcasts tab is hidden even
     * when enabled if there are none, so a reader with no podcast feeds does
     * not see an empty tab. Kept fresh by [refreshHasPodcasts].
     */
    var hasPodcasts by mutableStateOf(false)
        private set

    /**
     * The embedded Awesome RSS Feeds catalog, parsed on first access and kept
     * for the rest of the session. Loaded lazily by [curatedFeeds].
     */
    var curatedCatalog by mutableStateOf<CuratedCatalog?>(null)
        private set

    /**
     * Whether the add-feed dialog is open. The Feeds app-bar action sets it;
     * [org.vestifeed.ui.screens.FeedsScreen] observes it and renders the dialog.
     */
    var addFeedDialogVisible by mutableStateOf(false)

    /**
     * Whether the OPML import dialog is open (Feeds app-bar overflow).
     * [org.vestifeed.ui.screens.FeedsScreen] observes it.
     */
    var opmlImportDialogVisible by mutableStateOf(false)

    /**
     * One-shot request from the Feeds app-bar overflow to export the feeds as
     * OPML. [org.vestifeed.ui.screens.FeedsScreen] consumes and clears it.
     */
    var opmlExportRequested by mutableStateOf(false)

    /**
     * Whether the entry shown by [Screen.EntryDetail] is bookmarked. The entry
     * screen syncs it on load; the app bar renders the toggle from it.
     */
    var entryBookmarked by mutableStateOf(false)

    /** Title of the entry shown by [Screen.EntryDetail], for the share action. */
    var entryTitle by mutableStateOf("")

    /** The entry's HTML alternate link, or null. Drives "Open in browser". */
    var entryHref by mutableStateOf<String?>(null)

    /** Whether the entry shown by [Screen.EntryDetail] is read. */
    var entryRead by mutableStateOf(false)

    /**
     * Whether the entry screen's in-entry find bar is open. The app-bar Search
     * action toggles it while on [Screen.EntryDetail]; the screen renders it.
     */
    var entrySearchVisible by mutableStateOf(false)

    /**
     * Toggles the bookmark on [entryId] for the entry-detail app-bar action,
     * refreshing [entryBookmarked] and the unread count.
     */
    fun toggleEntryBookmark(entryId: String) {
        scope.launch {
            val entry = db.entry.selectById(entryId) ?: return@launch
            val next = !entry.extBookmarked
            db.entry.updateBookmarkedAndBookmarkedSynced(entryId, next, false)
            entryBookmarked = next
            refreshUnreadCount()
            sync.runInBackground()
        }
    }

    /**
     * Opens [entryHref] in the platform browser and marks [entryId] read,
     * matching the old entry-screen button. No-op when there is no link.
     */
    fun openEntryInBrowser(entryId: String) {
        val href = entryHref ?: return
        platform.openUrl(href, conf.useBuiltInBrowser)
        scope.launch {
            db.entry.updateReadAndReadSynced(entryId, true, false)
            entryRead = true
            refreshUnreadCount()
            sync.runInBackground()
        }
    }

    /** Shares the open entry's title and link via the platform. */
    fun shareEntry() {
        platform.shareText(entryTitle + "\n" + (entryHref ?: ""))
    }

    /** Toggles the read flag on [entryId], refreshing [entryRead] and the count. */
    fun toggleEntryRead(entryId: String) {
        scope.launch {
            val entry = db.entry.selectById(entryId) ?: return@launch
            val next = !entry.extRead
            db.entry.updateReadAndReadSynced(entryId, next, false)
            entryRead = next
            refreshUnreadCount()
            sync.runInBackground()
        }
    }

    /**
     * Fetches the feed at [rawUrl] through the active backend and stores it
     * with its entries, the shared path for adding a feed by URL and for
     * following one from a curated collection. Returns the stored feed.
     */
    suspend fun addFeedByUrl(rawUrl: String): Result<FeedTable.Feed> = runCatching {
        val result = backend(db).addFeed(rawUrl.trim().withHttpsScheme().toUrl(), null)
        db.transaction {
            db.feed.insertOrReplace(result.feed)
            db.link.insertForFeed(result.feed.id, result.feedLinks)
            db.entry.insertOrReplace(result.entries.map { it.first })
            result.entries.forEach { (entry, entryLinks) ->
                db.link.insertForEntry(entry.id, entryLinks)
            }
        }
        refreshUnreadCount()
        result.feed
    }

    /** Loads [curatedCatalog] once, caching it for the session. */
    suspend fun curatedFeeds(): CuratedCatalog {
        curatedCatalog?.let { return it }
        val loaded = loadCuratedCatalog()
        curatedCatalog = loaded
        return loaded
    }

    val sync = Sync(scope, db)

    private val backStack = mutableStateListOf<Screen>()

    var screen by mutableStateOf<Screen>(Screen.Loading)
        private set

    suspend fun connect() {
        if (connected) return
        db.connect()
        conf = db.conf.select()
        screen = if (conf.backend == null) Screen.Auth else defaultTab()
        connected = true

        refreshUnreadCount()
        refreshHasPodcasts()
        // Keep both counts fresh once a background sync lands, wherever the
        // user happens to be.
        scope.launch {
            sync.running.collect { running ->
                if (!running) {
                    refreshUnreadCount()
                    refreshHasPodcasts()
                }
            }
        }
    }

    fun refreshConf() {
        scope.launch { conf = db.conf.select() }
    }

    /** Reloads [unreadCount] from the database (see its docs). */
    suspend fun refreshUnreadCount() {
        unreadCount = db.entry.selectUnreadCount()
    }

    /** Reloads [hasPodcasts] from the database (see its docs). */
    suspend fun refreshHasPodcasts() {
        hasPodcasts = db.link.hasAudioEnclosures()
    }

    fun updateConf(block: (ConfTable.Conf) -> ConfTable.Conf) {
        scope.launch {
            db.conf.update(block)
            conf = db.conf.select()
        }
    }

    /** Awaitable variant of [updateConf] for flows that must finish first. */
    suspend fun setConf(block: (ConfTable.Conf) -> ConfTable.Conf) {
        db.conf.update(block)
        conf = db.conf.select()
    }

    fun navigate(target: Screen) {
        if (target == screen) return
        backStack.add(screen)
        screen = target
    }

    /** Replaces the whole stack, e.g. selecting a bottom-navigation tab. */
    fun navigateRoot(target: Screen) {
        backStack.clear()
        screen = target
    }

    fun pop() {
        screen = backStack.removeLastOrNull() ?: defaultTab()
    }

    fun canGoBack(): Boolean = backStack.isNotEmpty()

    fun defaultTab(): Screen = Screen.Entries(EntriesList.Unread)

    fun logout() {
        scope.launch {
            db.conf.delete()
            db.transaction {
                db.link.deleteAll()
                db.entry.deleteAll()
                db.feed.deleteAll()
            }
            conf = ConfTable.defaultConf()
            unreadCount = 0
            hasPodcasts = false
            backStack.clear()
            screen = Screen.Auth
        }
    }
}
