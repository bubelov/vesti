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

class Database(driver: SQLiteDriver, val path: String) {

    companion object {
        const val NAME = "vesti.db"
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
     * Opens the connection and prepares the schema. It is a separate step from
     * construction because opening and migrating are suspending on the web
     * target.
     */
    suspend fun connect() {
        if (connection != null) return

        connectMutex.withLock {
            if (connection != null) return

            val conn = openDatabaseConnection(driver, path)
            connection = conn

            conn.execSQL("PRAGMA foreign_keys = ON;")
            migrate(conn)

            feed = FeedTable(conn)
            entry = EntryTable(conn)
            conf = ConfTable(conn)
            link = LinkTable(conn)
            tag = TagTable(conn)
            feedTag = FeedTagTable(conn)
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
            conn.execSQL("PRAGMA user_version=10;")
            version = 10
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
}
