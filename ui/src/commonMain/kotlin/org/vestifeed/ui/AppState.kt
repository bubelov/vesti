package org.vestifeed.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.sync.Sync

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
        platform.openUrl(href)
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
    }

    fun refreshConf() {
        scope.launch { conf = db.conf.select() }
    }

    /** Reloads [unreadCount] from the database (see its docs). */
    suspend fun refreshUnreadCount() {
        unreadCount = db.entry.selectUnreadCount()
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
            backStack.clear()
            screen = Screen.Auth
        }
    }
}
