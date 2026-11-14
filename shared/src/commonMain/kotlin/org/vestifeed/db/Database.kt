package org.vestifeed.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.FeedTagTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.db.table.TagTable
import org.vestifeed.dbstats.DbStats
import org.vestifeed.dbstats.DbStatsReader

class Database(driver: SQLiteDriver, val path: String) {

    companion object {
        const val NAME = "vesti.db"

        /** Comfortably below SQLite's usual 999 bound-variable limit. */
        private const val DELETE_CHUNK_SIZE = 500
    }

    private val driver = driver

    private var connection: SQLiteConnection? = null

    private val conn: SQLiteConnection
        get() = connection ?: error("Database.connect() has not been called")

    lateinit var feed: FeedTable
        private set
    lateinit var entry: EntryTable
        private set
    lateinit var conf: ConfTable
        private set
    lateinit var link: LinkTable
        private set
    lateinit var tag: TagTable
        private set
    lateinit var feedTag: FeedTagTable
        private set

    /** Serializes opening the connection so two concurrent callers cannot race. */
    private val connectMutex = Mutex()

    /**
     * Serializes pushes of pending read/bookmark changes. A full sync pushes
     * them from `MinifluxSync`, while an entry action pushes them from its own
     * coroutine (`Sync.pushInBackground`); without this lock the two requests
     * can reach the server out of order and an older state can win. The lock
     * lives on the database so every backend instance built for this database
     * shares it.
     */
    internal val pendingPushMutex = Mutex()

    /**
     * Opens the connection and prepares the schema. It is a separate step from
     * construction because opening and migrating are suspending on the web
     * target.
     */
    suspend fun connect() {
        connectMutex.withLock {
            if (connection != null) return

            val conn = openDatabaseConnection(driver, path)
            conn.execSQL("PRAGMA foreign_keys = ON;")
            migrate(conn)

            feed = FeedTable(conn)
            entry = EntryTable(conn)
            conf = ConfTable(conn)
            link = LinkTable(conn)
            tag = TagTable(conn)
            feedTag = FeedTagTable(conn)
            // Published last: no caller may observe a non-null connection
            // before the table fields are ready. Every caller goes through the
            // mutex, whose release/acquire publishes them safely; a lock-free
            // `connection != null` check could not.
            connection = conn
        }
    }

    private suspend fun migrate(conn: SQLiteConnection) {
        var version = conn.prepare("SELECT user_version FROM pragma_user_version;").use { stmt ->
            if (stmt.step()) stmt.getInt(0) else 0
        }

        if (version == 0) {
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(EntryTable.SCHEMA)
            conn.execSQL(LinkTable.SCHEMA)
            conn.execSQL(ConfTable.SCHEMA)
            conn.execSQL(TagTable.SCHEMA)
            conn.execSQL(FeedTagTable.SCHEMA)
            conn.execSQL("PRAGMA user_version=14;")
            version = 14
        }

        if (version == 1) {
            conn.execSQL("ALTER TABLE conf ADD COLUMN entry_body_font_size INTEGER NOT NULL DEFAULT 16;")
            conn.execSQL("PRAGMA user_version=2;")
            version = 2
        }

        if (version == 2) {
            conn.execSQL("ALTER TABLE conf ADD COLUMN show_author_name INTEGER NOT NULL DEFAULT 0;")
            conn.execSQL("PRAGMA user_version=3;")
            version = 3
        }

        if (version == 3) {
            conn.execSQL("ALTER TABLE conf ADD COLUMN use_built_in_audio_player INTEGER NOT NULL DEFAULT 0;")
            conn.execSQL("PRAGMA user_version=4;")
            version = 4
        }

        if (version == 4) {
            conn.execSQL(TagTable.SCHEMA)
            conn.execSQL(FeedTagTable.SCHEMA)
            conn.execSQL("PRAGMA user_version=5;")
            version = 5
        }

        if (version == 5) {
            conn.execSQL("ALTER TABLE conf ADD COLUMN show_tags_tab INTEGER NOT NULL DEFAULT 0;")
            conn.execSQL("PRAGMA user_version=6;")
            version = 6
        }

        if (version == 6) {
            conn.execSQL("ALTER TABLE conf ADD COLUMN show_podcasts_tab INTEGER NOT NULL DEFAULT 0;")
            conn.execSQL("PRAGMA user_version=7;")
            version = 7
        }

        if (version == 7) {
            conn.execSQL("ALTER TABLE link ADD COLUMN ext_played INTEGER NOT NULL DEFAULT 0;")
            conn.execSQL("ALTER TABLE link ADD COLUMN ext_played_at TEXT;")
            conn.execSQL("PRAGMA user_version=8;")
            version = 8
        }

        if (version == 8) {
            conn.execSQL("ALTER TABLE entry ADD COLUMN ext_og_log TEXT NOT NULL DEFAULT '[]';")
            conn.execSQL("PRAGMA user_version=9;")
            version = 9
        }

        if (version == 9) {
            conn.execSQL("ALTER TABLE conf ADD COLUMN entries_view TEXT NOT NULL DEFAULT 'cards';")
            conn.execSQL("PRAGMA user_version=10;")
            version = 10
        }

        if (version == 10) {
            // Repair entries the OG fetcher saw before sync had inserted their
            // links: it found no alternate link, marked them checked, and never
            // retried, so the stored image URL stayed empty. Reset just those
            // rows (identified by their log, and not already resolved another
            // way) so the fetcher picks them up with the new link-aware query.
            conn.execSQL(
                """
                UPDATE entry SET ext_og_image_checked = 0
                WHERE ext_og_image_checked = 1
                  AND (ext_og_image_url IS NULL OR ext_og_image_url = '')
                  AND ext_og_log LIKE '%No HTML alternate link found%'
                  AND ext_og_log NOT LIKE '%No og:image meta tag found%'
                  AND ext_og_log NOT LIKE '%Could not read OG image dimensions%'
                  AND ext_og_log NOT LIKE '%OG image stored%';
                """.trimIndent()
            )
            conn.execSQL("PRAGMA user_version=11;")
            version = 11
        }

        if (version == 11) {
            // The OG fetcher no longer measures images; the display layer
            // (Coil) downloads and caches them. Drop the now-unused columns.
            conn.execSQL("ALTER TABLE entry DROP COLUMN ext_og_image_width;")
            conn.execSQL("ALTER TABLE entry DROP COLUMN ext_og_image_height;")
            conn.execSQL("PRAGMA user_version=12;")
            version = 12
        }

        if (version == 12) {
            // The UI language override; null means "follow the system locale".
            conn.execSQL("ALTER TABLE conf ADD COLUMN language TEXT;")
            conn.execSQL("PRAGMA user_version=13;")
            version = 13
        }

        if (version == 13) {
            // Wall-clock timestamps for the diagnostics screen and for
            // scheduling the deleted-entry reconcile. The existing
            // minifluxIncrementalSyncTimestamp is a server change cursor, not
            // a sync time, so it cannot serve either purpose.
            conn.execSQL("ALTER TABLE conf ADD COLUMN last_sync_at TEXT;")
            conn.execSQL("ALTER TABLE conf ADD COLUMN last_prune_at TEXT;")
            conn.execSQL("PRAGMA user_version=14;")
            version = 14
        }
    }

    /**
     * Runs [block] in a database transaction, rolling back if it throws.
     *
     * The connection's serialization lock is held for the whole block (see
     * [LockingSQLiteConnection.transaction] on JVM/Android), so no other
     * statement can interleave with the transaction.
     */
    suspend fun transaction(block: suspend () -> Unit) {
        withTransaction(conn, block)
    }

    /** Closes the underlying connection. */
    fun close() {
        connection?.close()
    }

    /**
     * Reads the database stats shown by the settings screen. It is a plain
     * read, but every statement goes through the connection lock, so calling
     * it from a background dispatcher cannot interleave with a running sync.
     */
    suspend fun readStats(): DbStats = DbStatsReader(conn).read()

    /**
     * Deletes local entries that no longer exist on the server, along with
     * their links. Only numeric entry IDs are considered: Miniflux uses numeric
     * IDs, while embedded entries use URLs and must never be pruned by a
     * Miniflux reconcile.
     *
     * The server ID set is the union of two scans in the caller, so a page
     * shift during a scan can only make the prune more conservative (an entry
     * that disappeared from one page but is still live appears in the other).
     * Returns the number of deleted entries.
     */
    suspend fun pruneEntriesNotIn(serverIds: Set<Long>): Int {
        // An empty set means the scan failed or the account has no entries;
        // never interpret that as "delete everything".
        if (serverIds.isEmpty()) return 0

        return withTransaction(conn) {
            val stale = mutableListOf<String>()
            conn.prepare("SELECT id FROM entry;").use { stmt ->
                while (stmt.step()) {
                    val id = stmt.getText(0)
                    val numericId = id.toLongOrNull() ?: continue
                    if (numericId !in serverIds) stale.add(id)
                }
            }

            if (stale.isEmpty()) return@withTransaction 0

            stale.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }

                // Links reference entries, so they must go first.
                conn.prepare("DELETE FROM link WHERE entry_id IN ($placeholders);").use { stmt ->
                    chunk.forEachIndexed { index, id -> stmt.bindText(index + 1, id) }
                    stmt.step()
                }
                conn.prepare("DELETE FROM entry WHERE id IN ($placeholders);").use { stmt ->
                    chunk.forEachIndexed { index, id -> stmt.bindText(index + 1, id) }
                    stmt.step()
                }
            }

            stale.size
        }
    }
}
