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

/** Why a full sync was started, so the UI can decide whether to react to it. */
enum class SyncReason {
    /** The user tapped the sync button or pulled to refresh. */
    User,

    /** The first sync after launch/sign-in, when "Sync on startup" is on. */
    Startup,

    /** Any sync the user did not ask for. */
    Background,
}

/**
 * The outcome of one completed sync. [id] changes on every run, so two
 * consecutive runs with identical values still each trigger a collector.
 * [newEntries] is the net number of entry rows added before the prune.
 */
data class SyncRun(
    val id: Long,
    val reason: SyncReason,
    val newEntries: Long,
)

class Sync(
    private val scope: CoroutineScope,
    private val db: Database,
    private val userAgent: String = "",
    /**
     * Seam for tests: the app always resolves the backend from the current
     * configuration; a test can supply a fake catalog without a network.
     */
    private val backendFactory: suspend (Database, String) -> Backend = { database, agent ->
        backend(database, agent)
    },
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
     * The most recent successful full sync, or null before the first one. The
     * entries screen uses this to decide whether the user should be taken to
     * the top of the list (a user sync always, a startup sync only when it
     * brought new entries).
     */
    private val _lastRun = MutableStateFlow<SyncRun?>(null)
    val lastRun: StateFlow<SyncRun?> = _lastRun.asStateFlow()

    /**
     * `true` while the global unread entries screen is the foreground screen.
     * Background sync consults this before surfacing an "unread entries"
     * notification, so the user is not notified about a list they can already
     * see.
     */
    var unreadScreenVisible: Boolean = false

    fun runInBackground(reason: SyncReason = SyncReason.Background) {
        scope.launch { run(reason) }
    }

    suspend fun runInForeground(reason: SyncReason = SyncReason.Background) {
        run(reason)
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
                withContext(ioDispatcher) { backendFactory(db, userAgent).pushPendingChanges() }
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

    private suspend fun run(reason: SyncReason) {
        while (running.value) {
            delay(100)
        }

        _running.update { true }
        _lastError.value = null

        val started = TimeSource.Monotonic.markNow()
        var newEntries = 0L
        var completed = false

        try {
            // The whole sync (network plus blocking SQLite writes) runs off the
            // caller's dispatcher: launched from the UI it used to freeze the
            // window for as long as the transactions took.
            withContext(ioDispatcher) {
                val conf = db.conf.select()
                val currentBackend = backendFactory(db, userAgent)

                val beforeCount = db.entry.selectCount()
                currentBackend.sync(initial = conf.minifluxIncrementalSyncTimestamp == null)
                val afterCount = db.entry.selectCount()
                newEntries = (afterCount - beforeCount).coerceAtLeast(0)

                // A wall-clock timestamp, unlike the incremental cursor, which
                // only moves when an entry actually changed on the server.
                val now = Clock.System.now()
                db.conf.update { it.copy(lastSyncAt = now.toString()) }
                _lastDuration.value = started.elapsedNow()

                // The prune is deliberately outside the "did the sync work"
                // path: a failed reconcile must not turn a successful feed sync
                // into an error.
                pruneIfDue(currentBackend, conf, now)
                completed = true
            }
        } catch (t: Throwable) {
            _lastError.value = t.message ?: t.toString()
        } finally {
            if (completed) {
                _lastRun.update { previous ->
                    SyncRun(
                        id = (previous?.id ?: 0L) + 1L,
                        reason = reason,
                        newEntries = newEntries,
                    )
                }
            }
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
