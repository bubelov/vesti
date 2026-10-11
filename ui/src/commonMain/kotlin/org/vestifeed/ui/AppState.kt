package org.vestifeed.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vestifeed.backend.backend
import org.vestifeed.curated.CuratedCatalog
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.platform.ioDispatcher
import org.vestifeed.sync.Sync
import org.vestifeed.ui.curated.loadCuratedCatalog
import org.vestifeed.ui.updates.UpdateInstallResult
import org.vestifeed.ui.updates.UpdateService
import org.vestifeed.update.UpdateManifest
import org.vestifeed.update.UpdateStatus
import org.vestifeed.update.evaluateUpdate
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
    /**
     * Runs blocking database work on [ioDispatcher] and returns to the caller's
     * dispatcher. The bundled SQLite driver executes synchronously, so touching
     * the database from the Compose/main thread freezes the UI for the length
     * of the query (the Unread list, for one, reads thousands of rows). The
     * shared connection is serialized with a lock, so a single background
     * dispatcher is enough to keep it correct.
     */
    suspend fun <T> withDb(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

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
            val entry = withDb { db.entry.selectById(entryId) } ?: return@launch
            val next = !entry.extBookmarked
            withDb { db.entry.updateBookmarkedAndBookmarkedSynced(entryId, next, false) }
            entryBookmarked = next
            refreshUnreadCount()
            sync.pushInBackground()
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
            withDb { db.entry.updateReadAndReadSynced(entryId, true, false) }
            entryRead = true
            refreshUnreadCount()
            sync.pushInBackground()
        }
    }

    /** Shares the open entry's title and link via the platform. */
    fun shareEntry() {
        platform.shareText(entryTitle + "\n" + (entryHref ?: ""))
    }

    /** Toggles the read flag on [entryId], refreshing [entryRead] and the count. */
    fun toggleEntryRead(entryId: String) {
        scope.launch {
            val entry = withDb { db.entry.selectById(entryId) } ?: return@launch
            val next = !entry.extRead
            withDb { db.entry.updateReadAndReadSynced(entryId, next, false) }
            entryRead = next
            refreshUnreadCount()
            sync.pushInBackground()
        }
    }

    /**
     * Fetches the feed at [rawUrl] through the active backend and stores it
     * with its entries, the shared path for adding a feed by URL and for
     * following one from a curated collection. Returns the stored feed.
     */
    suspend fun addFeedByUrl(rawUrl: String): Result<FeedTable.Feed> = withDb {
        runCatching {
            val result = backend(db, userAgent).addFeed(rawUrl.trim().withHttpsScheme().toUrl(), null)
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
    }

    /** Loads [curatedCatalog] once, caching it for the session. */
    suspend fun curatedFeeds(): CuratedCatalog {
        curatedCatalog?.let { return it }
        // Reading and parsing the catalog is IO, not UI work.
        val loaded = withDb { loadCuratedCatalog() }
        curatedCatalog = loaded
        return loaded
    }

    val sync = Sync(scope, db, userAgent)

    /**
     * The host's self-update channel, or null where the app cannot install
     * packages (desktop, browser). Everything below is inert without one.
     */
    private val updates: UpdateService? = platform.updates

    /** Whether this host can install its own updates (Android nightlies). */
    val supportsUpdates: Boolean get() = updates != null

    /** The running build's label for the settings row, or null without a channel. */
    val currentVersionLabel: String? get() = updates?.currentVersionLabel

    /**
     * A newer build the check found, shown as the update prompt. Null when
     * there is nothing to offer or the user dismissed it.
     */
    var availableUpdate by mutableStateOf<UpdateManifest?>(null)
        private set

    /** True while an update check or download is running. */
    var updateBusy by mutableStateOf(false)
        private set

    /** Download progress in `0f..1f`, or null while it is unknown or idle. */
    var updateProgress by mutableStateOf<Double?>(null)
        private set

    /** The latest manual check/install outcome, shown by the settings row. */
    var updateMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Looks for a newer build on the update channel. An available build opens
     * the prompt unless [manual] is set (which always surfaces it) or the user
     * already dismissed it. The automatic check runs once on [connect]; the
     * settings row runs it manually.
     */
    fun checkForUpdates(manual: Boolean = false) {
        val service = updates ?: return
        if (updateBusy) return
        scope.launch {
            updateBusy = true
            if (manual) updateMessage = null
            val status = runCatching {
                evaluateUpdate(service.currentVersionCode, service.currentBuildTime, service.fetchLatest())
            }.getOrDefault(UpdateStatus.Unknown)
            updateBusy = false
            when (status) {
                is UpdateStatus.Available ->
                    if (manual || !service.isDismissed(status.manifest.buildTime)) {
                        availableUpdate = status.manifest
                    }
                UpdateStatus.UpToDate -> if (manual) updateMessage = "You're up to date"
                UpdateStatus.Unknown -> if (manual) updateMessage = "Could not check for updates"
            }
        }
    }

    /** Closes the update prompt and remembers the build as dismissed. */
    fun dismissUpdate() {
        availableUpdate?.let { updates?.dismiss(it.buildTime) }
        availableUpdate = null
        updateMessage = null
    }

    /** Downloads the offered update and hands it to the system installer. */
    fun installUpdate() {
        val service = updates ?: return
        val manifest = availableUpdate ?: return
        if (updateBusy) return
        scope.launch {
            updateBusy = true
            updateProgress = null
            updateMessage = null
            val result = runCatching {
                service.install(manifest) { updateProgress = it }
            }.getOrDefault(UpdateInstallResult.Failed("Could not install the update"))
            updateBusy = false
            updateProgress = null
            when (result) {
                UpdateInstallResult.Started -> availableUpdate = null
                UpdateInstallResult.NeedsPermission ->
                    updateMessage = "Allow Vesti to install updates, then tap Update again"
                is UpdateInstallResult.Failed -> updateMessage = result.message
            }
        }
    }

    private val backStack = mutableStateListOf<Screen>()

    var screen by mutableStateOf<Screen>(Screen.Loading)
        private set

    suspend fun connect() {
        if (connected) return
        conf = withDb {
            db.connect()
            db.conf.select()
        }
        screen = if (conf.backend == null) Screen.Auth else defaultTab()
        connected = true

        withDb {
            refreshUnreadCount()
            refreshHasPodcasts()
        }
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
        // Honor "Sync on startup": pull from the backend as soon as the app is
        // up so the lists do not open on a stale database. Nothing to sync
        // against until a backend is configured (the auth screen is showing).
        if (conf.backend != null && conf.syncOnStartup) {
            sync.runInBackground()
        }
        // Ask the update channel whether a newer nightly has been rolled out.
        // It is a no-op on hosts with no channel.
        checkForUpdates()
    }

    fun refreshConf() {
        scope.launch { conf = withDb { db.conf.select() } }
    }

    /** Reloads [unreadCount] from the database (see its docs). */
    suspend fun refreshUnreadCount() {
        unreadCount = withDb { db.entry.selectUnreadCount() }
    }

    /** Reloads [hasPodcasts] from the database (see its docs). */
    suspend fun refreshHasPodcasts() {
        hasPodcasts = withDb { db.link.hasAudioEnclosures() }
    }

    fun updateConf(block: (ConfTable.Conf) -> ConfTable.Conf) {
        scope.launch {
            conf = withDb {
                db.conf.update(block)
                db.conf.select()
            }
        }
    }

    /** Awaitable variant of [updateConf] for flows that must finish first. */
    suspend fun setConf(block: (ConfTable.Conf) -> ConfTable.Conf) {
        conf = withDb {
            db.conf.update(block)
            db.conf.select()
        }
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
            withDb {
                db.conf.delete()
                db.transaction {
                    db.link.deleteAll()
                    db.entry.deleteAll()
                    db.feed.deleteAll()
                }
            }
            conf = ConfTable.defaultConf()
            unreadCount = 0
            hasPodcasts = false
            backStack.clear()
            screen = Screen.Auth
        }
    }
}
