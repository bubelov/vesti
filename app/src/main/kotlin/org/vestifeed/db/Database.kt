package org.vestifeed.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
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

    private val conn = driver.open(path)

    val feed = FeedTable(conn)
    val entry = EntryTable(conn)
    val conf = ConfTable(conn)
    val link = LinkTable(conn)
    val tag = TagTable(conn)
    val feedTag = FeedTagTable(conn)

    init {
        conn.execSQL("PRAGMA foreign_keys = ON;")
        migrate()
    }

    private fun migrate() {
        val stmt = conn.prepare("SELECT user_version FROM pragma_user_version;")
        var version = if (stmt.step()) stmt.getInt(0) else 0

        if (version == 0) {
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(EntryTable.SCHEMA)
            conn.execSQL(LinkTable.SCHEMA)
            conn.execSQL(ConfTable.SCHEMA)
            conn.execSQL(TagTable.SCHEMA)
            conn.execSQL(FeedTagTable.SCHEMA)
            conn.execSQL("PRAGMA user_version=9;")
            version = 9
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
    }

    fun transaction(block: () -> Unit) {
        conn.execSQL("BEGIN TRANSACTION;")
        try {
            block()
            conn.execSQL("COMMIT;")
        } catch (e: Exception) {
            conn.execSQL("ROLLBACK;")
            throw e
        }
    }
}