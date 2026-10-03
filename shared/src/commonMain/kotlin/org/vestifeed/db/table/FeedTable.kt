package org.vestifeed.db.table

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.vestifeed.db.bindBooleanOrNull
import org.vestifeed.db.getBoolOrNull
import kotlin.collections.forEach

class FeedTable(private val conn: SQLiteConnection) {
    companion object {
        const val SCHEMA = """
            CREATE TABLE feed (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                ext_open_entries_in_browser INTEGER,
                ext_blocked_words TEXT,
                ext_show_preview_images INTEGER
            ) STRICT;
          """
    }

    data class Feed(
        val id: String,
        val title: String,
        val extOpenEntriesInBrowser: Boolean?,
        val extBlockedWords: String,
        val extShowPreviewImages: Boolean?,
    )

    suspend fun insertOrReplace(feed: Feed) {
        insertOrReplace(listOf(feed))
    }

    suspend fun insertOrReplace(feeds: List<Feed>) {
        if (feeds.isEmpty()) {
            return
        }
        conn.prepare(
            """
            INSERT OR REPLACE
            INTO feed (id, title, ext_open_entries_in_browser, ext_blocked_words, ext_show_preview_images)
            VALUES (?, ?, ?, ?, ?);
            """
        ).use { stmt ->
            feeds.forEach { feed ->
                stmt.bindText(1, feed.id)
                stmt.bindText(2, feed.title)
                stmt.bindBooleanOrNull(3, feed.extOpenEntriesInBrowser)
                stmt.bindText(4, feed.extBlockedWords)
                stmt.bindBooleanOrNull(5, feed.extShowPreviewImages)
                stmt.step()
                stmt.reset()
            }
        }
    }

    suspend fun selectAll(): List<Feed> {
        conn.prepare(
            """
            SELECT id, title, ext_open_entries_in_browser, ext_blocked_words, ext_show_preview_images
            FROM feed
            ORDER BY title COLLATE NOCASE;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(
                        Feed(
                            id = stmt.getText(0),
                            title = stmt.getText(1),
                            extOpenEntriesInBrowser = stmt.getBoolOrNull(2),
                            extBlockedWords = stmt.getText(3),
                            extShowPreviewImages = stmt.getBoolOrNull(4),
                        )
                    )
                }
            }
        }
    }

    suspend fun selectById(id: String): Feed? {
        conn.prepare(
            """
            SELECT id, title, ext_open_entries_in_browser, ext_blocked_words, ext_show_preview_images
            FROM feed
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, id)
            return if (stmt.step()) Feed(
                id = stmt.getText(0),
                title = stmt.getText(1),
                extOpenEntriesInBrowser = stmt.getBoolOrNull(2),
                extBlockedWords = stmt.getText(3),
                extShowPreviewImages = stmt.getBoolOrNull(4),
            ) else null
        }
    }

    suspend fun deleteById(id: String) {
        conn.prepare(
            """
            DELETE FROM feed
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.step()
        }
    }

    suspend fun deleteAll() {
        conn.execSQL("DELETE FROM feed;")
    }
}
