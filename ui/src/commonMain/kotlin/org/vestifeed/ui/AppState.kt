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
