package org.vestifeed.dbstats

/**
 * A read-only snapshot of the database, used by the database stats screen.
 *
 * [sizeBytes] is the logical size SQLite reports (`page_count * page_size`),
 * so it is available on every platform, including the browser where there is
 * no file to stat.
 */
data class DbStats(
    val version: Int,
    val sizeBytes: Long,
    val tables: List<TableStats>,
)

/**
 * Row counts for one table.
 *
 * [unreadCount] and [bookmarkedCount] are only reported for tables that carry
 * the entry flags (`entry` today); they are null for the others.
 * [pendingSyncCount] counts rows whose read/bookmark state has not reached the
 * server yet, and is null for tables that have no sync flags.
 */
data class TableStats(
    val name: String,
    val rowCount: Long,
    val unreadCount: Long? = null,
    val bookmarkedCount: Long? = null,
    val pendingSyncCount: Long? = null,
)
