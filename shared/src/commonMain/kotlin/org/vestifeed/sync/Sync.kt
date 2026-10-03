package org.vestifeed.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.vestifeed.backend.backend
import org.vestifeed.db.Database

class Sync(
    private val scope: CoroutineScope,
    private val db: Database,
) {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * The message of the most recent failed sync, or null after a successful
     * one. Screens show this so a blocked/failed sync is not silent (a
     * cross-origin browser fetch surfaces here as a CORS failure).
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * `true` while the global unread entries screen is the foreground screen.
     * Background sync consults this before surfacing an "unread entries"
     * notification, so the user is not notified about a list they can already
     * see.
     */
    var unreadScreenVisible: Boolean = false

    fun runInBackground() {
        scope.launch { run() }
    }

    suspend fun runInForeground() {
        run()
    }

    /** Clears the last error, e.g. once a retry is started. */
    fun clearError() {
        _lastError.value = null
    }

    private suspend fun run() {
        while (running.value) {
            delay(100)
        }

        _running.update { true }
        _lastError.value = null

        try {
            val conf = db.conf.select()
            backend(db).sync(initial = conf.minifluxIncrementalSyncTimestamp == null)
        } catch (t: Throwable) {
            _lastError.value = t.message ?: t.toString()
        } finally {
            _running.update { false }
        }
    }
}
