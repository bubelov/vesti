package org.vestifeed.db.table

import androidx.sqlite.SQLiteConnection

class FeedTagTable(private val conn: SQLiteConnection) {
    companion object {
        const val SCHEMA = """
            CREATE TABLE feed_tag (
                feed_id TEXT NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
                tag_id TEXT NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
                PRIMARY KEY (feed_id, tag_id)
            ) STRICT;
        """
    }

    suspend fun insert(feedId: String, tagId: String) {
        conn.prepare(
            """
            INSERT OR IGNORE INTO feed_tag (feed_id, tag_id)
            VALUES (?, ?);
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            stmt.bindText(2, tagId)
            stmt.step()
        }
    }

    suspend fun delete(feedId: String, tagId: String) {
        conn.prepare(
            """
            DELETE FROM feed_tag
            WHERE feed_id = ? AND tag_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            stmt.bindText(2, tagId)
            stmt.step()
        }
    }

    suspend fun deleteByFeedId(feedId: String) {
        conn.prepare(
            """
            DELETE FROM feed_tag
            WHERE feed_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            stmt.step()
        }
    }

    suspend fun deleteByTagId(tagId: String) {
        conn.prepare(
            """
            DELETE FROM feed_tag
            WHERE tag_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, tagId)
            stmt.step()
        }
    }

    suspend fun selectTagIdsByFeedId(feedId: String): List<String> {
        conn.prepare(
            """
            SELECT tag_id
            FROM feed_tag
            WHERE feed_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            return buildList {
                while (stmt.step()) {
                    add(stmt.getText(0))
                }
            }
        }
    }

    suspend fun selectFeedIdsByTagId(tagId: String): List<String> {
        conn.prepare(
            """
            SELECT feed_id
            FROM feed_tag
            WHERE tag_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, tagId)
            return buildList {
                while (stmt.step()) {
                    add(stmt.getText(0))
                }
            }
        }
    }
}
