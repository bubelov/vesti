package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.time.Duration
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.vestifeed.db.table.ConfTable
import org.vestifeed.dbstats.DbStats
import org.vestifeed.dbstats.TableStats
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbols
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.backend
import org.vestifeed.ui.resources.bookmarked
import org.vestifeed.ui.resources.database
import org.vestifeed.ui.resources.duration
import org.vestifeed.ui.resources.embedded
import org.vestifeed.ui.resources.error
import org.vestifeed.ui.resources.failed
import org.vestifeed.ui.resources.file
import org.vestifeed.ui.resources.idle
import org.vestifeed.ui.resources.last_sync
import org.vestifeed.ui.resources.miniflux
import org.vestifeed.ui.resources.never
import org.vestifeed.ui.resources.not_configured
import org.vestifeed.ui.resources.pending_changes
import org.vestifeed.ui.resources.pending_sync
import org.vestifeed.ui.resources.rows
import org.vestifeed.ui.resources.schema_version
import org.vestifeed.ui.resources.server
import org.vestifeed.ui.resources.size
import org.vestifeed.ui.resources.state
import org.vestifeed.ui.resources.sync
import org.vestifeed.ui.resources.syncing
import org.vestifeed.ui.resources.tables
import org.vestifeed.ui.resources.unread
import org.vestifeed.ui.stats.StatsEntry
import org.vestifeed.ui.stats.StatsScreen
import org.vestifeed.ui.stats.StatsSection
import org.vestifeed.util.toInstantOrNull

/** Display order of the database table cards, rather than alphabetical. */
private val TABLE_ORDER = listOf("feed", "entry", "link", "tag", "feed_tag", "conf")

/**
 * The database/sync stats screen. It reads a snapshot of the database when it
 * opens and again whenever a sync starts or finishes, so the row counts and
 * pending changes always describe the state the user is looking at.
 *
 * The cards mirror the btcmap-android stats screen: a sync card with
 * expandable details, one card for the database file/schema, and one row per
 * table whose details break the counts down further.
 */
@Composable
fun DbStatsScreen(state: AppState) {
    val running by state.sync.running.collectAsState()
    val lastError by state.sync.lastError.collectAsState()
    val lastDuration by state.sync.lastDuration.collectAsState()

    var stats by remember { mutableStateOf<DbStats?>(null) }
    // Keyed on running so the snapshot is taken when a sync starts and once
    // more when it finishes, which is when the counts and the cursor change.
    LaunchedEffect(running) {
        stats = state.withDb { state.db.readStats() }
    }

    val loaded = stats
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    StatsScreen(sections = statsSections(state, loaded, running, lastError, lastDuration))
}

@Composable
private fun statsSections(
    state: AppState,
    stats: DbStats,
    running: Boolean,
    lastError: String?,
    lastDuration: Duration?,
): List<StatsSection> {
    val conf = state.conf
    val entryTable = stats.tables.firstOrNull { it.name == "entry" }
    val pendingChanges = entryTable?.pendingSyncCount

    val stateLabel = when {
        running -> stringResource(Res.string.syncing)
        lastError != null -> stringResource(Res.string.failed)
        else -> stringResource(Res.string.idle)
    }
    val syncState = StatsEntry(
        label = stringResource(Res.string.state),
        value = stateLabel,
        // The error itself can span several lines, so it goes behind the row
        // instead of stretching the card's value column.
        details = lastError?.let {
            listOf(StatsEntry(label = stringResource(Res.string.error), value = it))
        } ?: emptyList(),
    )

    return buildList {
        add(
            StatsSection(
                key = "sync",
                title = stringResource(Res.string.sync),
                icon = MaterialSymbols.Sync,
                entries = buildList {
                    add(
                        StatsEntry(
                            label = stringResource(Res.string.backend),
                            value = when (conf.backend) {
                                ConfTable.Backend.Miniflux -> stringResource(Res.string.miniflux)
                                ConfTable.Backend.Embedded -> stringResource(Res.string.embedded)
                                null -> stringResource(Res.string.not_configured)
                            },
                        ),
                    )
                    conf.minifluxUrl?.let {
                        add(StatsEntry(label = stringResource(Res.string.server), value = it))
                    }
                    add(syncState)
                    // The incremental cursor and pending counters only exist
                    // for a Miniflux account; the embedded backend keeps its
                    // state locally, where "never" would be misleading.
                    if (conf.backend == ConfTable.Backend.Miniflux) {
                        add(
                            StatsEntry(
                                label = stringResource(Res.string.last_sync),
                                value = conf.minifluxIncrementalSyncTimestamp
                                    ?.let { formatLastSync(it) ?: it }
                                    ?: stringResource(Res.string.never),
                            ),
                        )
                    }
                    // Recorded for whichever backend ran the sync, unlike the
                    // Miniflux-only cursor above.
                    lastDuration?.let {
                        add(
                            StatsEntry(
                                label = stringResource(Res.string.duration),
                                value = formatSyncDuration(it),
                            ),
                        )
                    }
                    if (conf.backend == ConfTable.Backend.Miniflux) {
                        pendingChanges?.let {
                            add(
                                StatsEntry(
                                    label = stringResource(Res.string.pending_changes),
                                    value = formatCount(it),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        add(
            StatsSection(
                key = "database",
                title = stringResource(Res.string.database),
                icon = MaterialSymbols.Dns,
                entries = listOf(
                    StatsEntry(
                        label = stringResource(Res.string.file),
                        value = databaseFileName(state.db.path),
                    ),
                    StatsEntry(
                        label = stringResource(Res.string.schema_version),
                        value = stats.version.toString(),
                    ),
                    StatsEntry(
                        label = stringResource(Res.string.size),
                        value = formatBytes(stats.sizeBytes),
                    ),
                ),
            ),
        )

        add(
            StatsSection(
                key = "tables",
                title = stringResource(Res.string.tables),
                icon = MaterialSymbols.ListAlt,
                entries = stats.tables
                    .sortedWith(
                        compareBy { table ->
                            TABLE_ORDER.indexOf(table.name).let { i ->
                                if (i == -1) TABLE_ORDER.size else i
                            }
                        },
                    )
                    .map { tableEntry(it) },
            ),
        )
    }
}

/**
 * The compact row for [table]: its row count, with the read/bookmark and
 * pending-sync breakdown one tap away when the table tracks them.
 */
@Composable
private fun tableEntry(table: TableStats): StatsEntry {
    return StatsEntry(
        label = table.name,
        value = formatCount(table.rowCount),
        icon = tableIcon(table.name),
        details = buildList {
            add(StatsEntry(stringResource(Res.string.rows), formatCount(table.rowCount)))
            table.unreadCount?.let {
                add(StatsEntry(stringResource(Res.string.unread), formatCount(it)))
            }
            table.bookmarkedCount?.let {
                add(StatsEntry(stringResource(Res.string.bookmarked), formatCount(it)))
            }
            table.pendingSyncCount?.let {
                add(StatsEntry(stringResource(Res.string.pending_sync), formatCount(it)))
            }
        }.takeIf { it.size > 1 } ?: emptyList(),
    )
}

/**
 * The Material Symbols glyph for a table row: one that names what the table
 * holds, falling back to a generic list for a table this build does not know
 * (a future one, or a test fixture).
 */
private fun tableIcon(name: String): String = when (name) {
    "feed" -> MaterialSymbols.RssFeed
    "entry" -> MaterialSymbols.Newspaper
    "link" -> MaterialSymbols.Link
    "tag" -> MaterialSymbols.Label
    "feed_tag" -> MaterialSymbols.GridView
    "conf" -> MaterialSymbols.Settings
    else -> MaterialSymbols.ListAlt
}

/** The database's file name, or the raw path when it has no directory part. */
internal fun databaseFileName(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\')

/**
 * Formats a count with thousands separators, so a 20 000-row table is readable
 * at a glance.
 */
internal fun formatCount(value: Long): String {
    val digits = value.toString()
    val negative = digits.startsWith("-")
    val body = digits.removePrefix("-")
    val grouped = body.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}

/** A human-readable byte size, e.g. `48.2 MB`. */
internal fun formatBytes(bytes: Long): String {    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    if (unit == 0) return "$bytes ${units[unit]}"
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return "$rounded ${units[unit]}"
}

/**
 * Renders a sync run's wall time compactly: milliseconds under a second,
 * tenths of a second under a minute, and whole minutes and seconds above that.
 */
internal fun formatSyncDuration(duration: Duration): String {
    val millis = duration.inWholeMilliseconds
    if (millis < 1_000) return "$millis ms"
    val seconds = millis / 1_000
    if (seconds < 60) return "$seconds.${(millis % 1_000) / 100} s"
    return "${seconds / 60}m ${seconds % 60}s"
}

/** Renders the sync cursor as a local date and time, e.g. `Oct 31, 2026 09:14`. */
internal fun formatLastSync(
    raw: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String? {
    val instant = raw.toInstantOrNull() ?: return null
    val local = instant.toLocalDateTime(timeZone)
    val month = MONTH_NAMES[local.month.ordinal]
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$month ${local.day}, ${local.year} $hour:$minute"
}

private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
