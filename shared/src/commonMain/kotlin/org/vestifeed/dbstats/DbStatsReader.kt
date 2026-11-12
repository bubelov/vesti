package org.vestifeed.dbstats

import androidx.sqlite.SQLiteConnection
import kotlin.use

/**
 * Reads the database's schema version, logical size and per-table row counts.
 *
 * The queries are read-only and tolerate tables this build does not know: the
 * stats columns are detected from the table's own definition, the same way the
 * apps that came before this screen did, so a future table (or a test fixture
 * with a subset of the schema) is reported truthfully instead of crashing.
 */
internal class DbStatsReader(private val conn: SQLiteConnection) {

    suspend fun read(): DbStats {
        return DbStats(
            version = readUserVersion(),
            sizeBytes = readSizeBytes(),
            tables = readTables(),
        )
    }

    /** The schema version stored in the database's `user_version` pragma. */
    suspend fun readUserVersion(): Int {
        conn.prepare("SELECT user_version FROM pragma_user_version;").use {
            return if (it.step()) it.getInt(0) else 0
        }
    }

    /**
     * The database's logical size. `page_count * page_size` is what SQLite
     * itself considers the file to occupy, and unlike a filesystem stat it also
     * works in the browser, where the database lives in OPFS or memory.
     */
    suspend fun readSizeBytes(): Long {
        conn.prepare("SELECT page_count * page_size FROM pragma_page_count, pragma_page_size;").use {
            return if (it.step()) it.getLong(0) else 0L
        }
    }

    /** Lists the user tables and their row stats, ordered by name. */
    suspend fun readTables(): List<TableStats> {
        val tableNames = mutableListOf<String>()
        conn.prepare(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                AND name != 'android_metadata'
            ORDER BY name;
            """
        ).use {
            while (it.step()) {
                tableNames.add(it.getText(0))
            }
        }

        return tableNames.map { readTable(it) }
    }

    private suspend fun readTable(name: String): TableStats {
        val table = quote(name)
        val columns = readColumns(name)

        val rowCount = count("SELECT count(*) FROM $table;")

        val unreadCount = if (READ in columns) {
            count("SELECT count(*) FROM $table WHERE $READ = 0;")
        } else {
            null
        }

        val bookmarkedCount = if (BOOKMARKED in columns) {
            count("SELECT count(*) FROM $table WHERE $BOOKMARKED = 1;")
        } else {
            null
        }

        val pendingSyncCount = if (READ_SYNCED in columns || BOOKMARKED_SYNCED in columns) {
            val pending = buildList {
                if (READ_SYNCED in columns) add("$READ_SYNCED = 0")
                if (BOOKMARKED_SYNCED in columns) add("$BOOKMARKED_SYNCED = 0")
            }
            count("SELECT count(*) FROM $table WHERE ${pending.joinToString(" OR ")};")
        } else {
            null
        }

        return TableStats(
            name = name,
            rowCount = rowCount,
            unreadCount = unreadCount,
            bookmarkedCount = bookmarkedCount,
            pendingSyncCount = pendingSyncCount,
        )
    }

    /**
     * Reads the column names of [name] through a bound parameter rather than
     * interpolating the name, so a table name from `sqlite_master` that needs
     * escaping cannot break out of the statement.
     */
    private suspend fun readColumns(name: String): Set<String> {
        val columns = mutableSetOf<String>()
        conn.prepare("SELECT name FROM pragma_table_info(?);").use { stmt ->
            stmt.bindText(1, name)
            while (stmt.step()) {
                columns.add(stmt.getText(0))
            }
        }
        return columns
    }

    /** Quotes a SQL identifier, doubling any embedded double quote. */
    private fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private suspend fun count(sql: String): Long {
        conn.prepare(sql).use {
            return if (it.step()) it.getLong(0) else 0L
        }
    }

    private companion object {
        const val READ = "ext_read"
        const val BOOKMARKED = "ext_bookmarked"
        const val READ_SYNCED = "ext_read_synced"
        const val BOOKMARKED_SYNCED = "ext_bookmarked_synced"
    }
}
