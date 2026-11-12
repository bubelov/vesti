package org.vestifeed.sync

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vestifeed.backend.Backend
import org.vestifeed.backend.backend
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.platform.ioDispatcher
import org.vestifeed.util.toInstantOrNull

class Sync(
    private val scope: CoroutineScope,
    private val db: Database,
    private val userAgent: String = "",
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
     * How long the most recent successful sync took, or null before the first
     * one. Kept in memory: it describes this session's last run, like the sync
     * stats screen's other live values, rather than a persisted figure.
     */
    private val _lastDuration = MutableStateFlow<Duration?>(null)
    val lastDuration: StateFlow<Duration?> = _lastDuration.asStateFlow()

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

    /**
     * Flushes pending local read/bookmark changes without a full sync. Entry
     * actions use this: pushing one entry's state is a single request per
     * kind, where [runInBackground] re-fetches every feed, category and changed
     * entry first. It deliberately does not touch [running], so it stays
     * invisible and never triggers the full-list reload a sync does.
     */
    fun pushInBackground() {
        scope.launch {
            try {
                withContext(ioDispatcher) { backend(db, userAgent).pushPendingChanges() }
                _lastError.value = null
            } catch (t: Throwable) {
                _lastError.value = t.message ?: t.toString()
            }
        }
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

        val started = TimeSource.Monotonic.markNow()

        try {
            // The whole sync (network plus blocking SQLite writes) runs off the
            // caller's dispatcher: launched from the UI it used to freeze the
            // window for as long as the transactions took.
            withContext(ioDispatcher) {
                val conf = db.conf.select()
                val currentBackend = backend(db, userAgent)

                currentBackend.sync(initial = conf.minifluxIncrementalSyncTimestamp == null)

                // A wall-clock timestamp, unlike the incremental cursor, which
                // only moves when an entry actually changed on the server.
                val now = Clock.System.now()
                db.conf.update { it.copy(lastSyncAt = now.toString()) }
                _lastDuration.value = started.elapsedNow()

                // The prune is deliberately outside the "did the sync work"
                // path: a failed reconcile must not turn a successful feed sync
                // into an error.
                pruneIfDue(currentBackend, conf, now)
            }
        } catch (t: Throwable) {
            _lastError.value = t.message ?: t.toString()
        } finally {
            _running.update { false }
        }
    }

    /**
     * Reconciles local entries with the server's ID list at most once a day.
     * A failure is swallowed: the next sync retries because [ConfTable.Conf.lastPruneAt]
     * is only written after the reconcile returns.
     */
    private suspend fun pruneIfDue(backend: Backend, conf: ConfTable.Conf, now: Instant) {
        if (conf.backend != ConfTable.Backend.Miniflux) return
        if (!isPruneDue(conf.lastPruneAt, now)) return

        try {
            if (backend.pruneDeletedEntries()) {
                db.conf.update { it.copy(lastPruneAt = now.toString()) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Keep lastPruneAt unchanged so the next sync tries again.
        }
    }
}

/**
 * Whether a deleted-entry reconcile is due. A missing or unparseable timestamp
 * counts as due, so the first sync after an upgrade performs the prune.
 */
internal fun isPruneDue(
    lastPruneAt: String?,
    now: Instant,
    interval: Duration = 24.hours,
): Boolean {
    if (lastPruneAt == null) return true
    val last = lastPruneAt.toInstantOrNull() ?: return true
    return now - last >= interval
}
