package org.vestifeed.db.table

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import org.vestifeed.db.bindTextOrNull
import org.vestifeed.db.getBoolOrNull
import java.time.OffsetDateTime
import kotlin.use

class EntryTable(private val conn: SQLiteConnection) {
    companion object {
        const val SCHEMA = """
            CREATE TABLE entry (
                content_type TEXT,
                content_src TEXT,
                content_text TEXT,
                summary TEXT,
                id TEXT PRIMARY KEY NOT NULL,
                feed_id TEXT NOT NULL,
                title TEXT NOT NULL,
                published TEXT NOT NULL,
                updated TEXT NOT NULL,
                author_name TEXT NOT NULL,
                ext_read INTEGER NOT NULL,
                ext_read_synced INTEGER NOT NULL,
                ext_bookmarked INTEGER NOT NULL,
                ext_bookmarked_synced INTEGER NOT NULL,
                ext_comments_url TEXT NOT NULL,
                ext_og_image_checked INTEGER NOT NULL,
                ext_og_image_url TEXT NOT NULL,
                ext_og_image_width INTEGER NOT NULL,
                ext_og_image_height INTEGER NOT NULL,
                ext_og_image_fetched_at TEXT NOT NULL DEFAULT '',
                ext_og_log TEXT NOT NULL DEFAULT '[]'
            ) STRICT;
        """
    }

    data class Entry(
        val contentType: String?,
        val contentSrc: String?,
        val contentText: String?,
        val summary: String?,
        val id: String,
        val feedId: String,
        val title: String,
        val published: OffsetDateTime,
        val updated: OffsetDateTime,
        val authorName: String,
        val extRead: Boolean,
        val extReadSynced: Boolean,
        val extBookmarked: Boolean,
        val extBookmarkedSynced: Boolean,
        val extCommentsUrl: String,
        val extOpenGraphImageChecked: Boolean,
        val extOpenGraphImageUrl: String,
        val extOpenGraphImageWidth: Int,
        val extOpenGraphImageHeight: Int,
        val extOpenGraphImageFetchedAt: OffsetDateTime?,
        val extOpenGraphImageLog: String,
    )

    private fun SQLiteStatement.toEntry(): Entry {
        return Entry(
            contentType = this.getTextOrNull(0),
            contentSrc = this.getTextOrNull(1),
            contentText = this.getTextOrNull(2),
            summary = this.getTextOrNull(3),
            id = this.getText(4),
            feedId = this.getText(5),
            title = this.getText(6),
            published = OffsetDateTime.parse(this.getText(7)),
            updated = OffsetDateTime.parse(this.getText(8)),
            authorName = this.getText(9),
            extRead = this.getInt(10) == 1,
            extReadSynced = this.getInt(11) == 1,
            extBookmarked = this.getInt(12) == 1,
            extBookmarkedSynced = this.getInt(13) == 1,
            extCommentsUrl = this.getText(14),
            extOpenGraphImageChecked = this.getInt(15) == 1,
            extOpenGraphImageUrl = this.getText(16),
            extOpenGraphImageWidth = this.getInt(17),
            extOpenGraphImageHeight = this.getInt(18),
            extOpenGraphImageFetchedAt = this.getTextOrNull(19)?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
            },
            extOpenGraphImageLog = this.getText(20),
        )
    }

    fun Entry.withoutContent(): EntryWithoutContent {
        return EntryWithoutContent(
            summary = summary,
            id = id,
            feedId = feedId,
            title = title,
            published = published,
            updated = updated,
            authorName = authorName,
            extRead = extRead,
            extReadSynced = extReadSynced,
            extBookmarked = extBookmarked,
            extBookmarkedSynced = extBookmarkedSynced,
            extCommentsUrl = extCommentsUrl,
            extOpenGraphImageChecked = extOpenGraphImageChecked,
            extOpenGraphImageUrl = extOpenGraphImageUrl,
            extOpenGraphImageWidth = extOpenGraphImageWidth,
            extOpenGraphImageHeight = extOpenGraphImageHeight,
            extOpenGraphImageFetchedAt = extOpenGraphImageFetchedAt,
            extOpenGraphImageLog = extOpenGraphImageLog,
        )
    }

    fun insertOrReplace(entries: List<Entry>) {
        conn.prepare(
            """
            INSERT INTO
            entry (content_type, content_src, content_text, summary, id, feed_id, title, published, updated, author_name, ext_read, ext_read_synced, ext_bookmarked, ext_bookmarked_synced, ext_comments_url, ext_og_image_checked, ext_og_image_url, ext_og_image_width, ext_og_image_height, ext_og_image_fetched_at, ext_og_log)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                content_type = excluded.content_type,
                content_src = excluded.content_src,
                content_text = excluded.content_text,
                summary = excluded.summary,
                feed_id = excluded.feed_id,
                title = excluded.title,
                published = excluded.published,
                updated = excluded.updated,
                author_name = excluded.author_name,
                ext_comments_url = excluded.ext_comments_url,
                ext_read = CASE WHEN ext_read_synced = 1 THEN excluded.ext_read ELSE ext_read END,
                ext_read_synced = CASE WHEN ext_read_synced = 1 THEN excluded.ext_read_synced ELSE ext_read_synced END,
                ext_bookmarked = CASE WHEN ext_bookmarked_synced = 1 THEN excluded.ext_bookmarked ELSE ext_bookmarked END,
                ext_bookmarked_synced = CASE WHEN ext_bookmarked_synced = 1 THEN excluded.ext_bookmarked_synced ELSE ext_bookmarked_synced END;
            """
        ).use { stmt ->
            entries.forEach { entry ->
                stmt.bindTextOrNull(1, entry.contentType)
                stmt.bindTextOrNull(2, entry.contentSrc)
                stmt.bindTextOrNull(3, entry.contentText)
                stmt.bindTextOrNull(4, entry.summary)
                stmt.bindText(5, entry.id)
                stmt.bindText(6, entry.feedId)
                stmt.bindText(7, entry.title)
                stmt.bindText(8, entry.published.toString())
                stmt.bindText(9, entry.updated.toString())
                stmt.bindText(10, entry.authorName)
                stmt.bindInt(11, if (entry.extRead) 1 else 0)
                stmt.bindInt(12, if (entry.extReadSynced) 1 else 0)
                stmt.bindInt(13, if (entry.extBookmarked) 1 else 0)
                stmt.bindInt(14, if (entry.extBookmarkedSynced) 1 else 0)
                stmt.bindText(15, entry.extCommentsUrl)
                stmt.bindInt(16, if (entry.extOpenGraphImageChecked) 1 else 0)
                stmt.bindText(17, entry.extOpenGraphImageUrl)
                stmt.bindInt(18, entry.extOpenGraphImageWidth)
                stmt.bindInt(19, entry.extOpenGraphImageHeight)
                stmt.bindText(20, entry.extOpenGraphImageFetchedAt?.toString() ?: "")
                stmt.bindText(21, entry.extOpenGraphImageLog)
                stmt.step()
                stmt.reset()
            }
        }
    }

    data class ShortEntry(
        val published: OffsetDateTime,
        val title: String,
    )

    fun selectAllPublishedAndTitle(): List<ShortEntry> {
        conn.prepare(
            """
            SELECT published, title 
            FROM entry 
            ORDER BY published DESC;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(
                        ShortEntry(
                            published = OffsetDateTime.parse(stmt.getText(0)),
                            title = stmt.getText(1),
                        )
                    )
                }
            }

        }
    }

    fun selectById(entryId: String): Entry? {
        conn.prepare(
            """
            SELECT content_type, content_src, content_text, summary, id, feed_id, title, published, updated, author_name, ext_read, ext_read_synced, ext_bookmarked, ext_bookmarked_synced, ext_comments_url, ext_og_image_checked, ext_og_image_url, ext_og_image_width, ext_og_image_height, ext_og_image_fetched_at, ext_og_log
            FROM entry
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, entryId)
            return if (stmt.step()) stmt.toEntry() else null
        }
    }

    data class EntriesAdapterRow(
        override val id: String,
        val feedId: String,
        val extBookmarked: Boolean,
        override val extShowPreviewImages: Boolean?,
        override val extOpenGraphImageUrl: String,
        override val extOpenGraphImageWidth: Int,
        override val extOpenGraphImageHeight: Int,
        override val title: String,
        override val feedTitle: String,
        override val published: OffsetDateTime,
        override val authorName: String,
        override val summary: String,
        override val extRead: Boolean,
        override val extOpenEntriesInBrowser: Boolean,
    ) : org.vestifeed.entries.EntryRowMappable

    fun selectByFeedId(feedId: String): List<EntriesAdapterRow> {
        conn.prepare(
            """
            SELECT e.id, e.feed_id, e.ext_bookmarked, e.ext_og_image_url,
                   e.ext_og_image_width, e.ext_og_image_height, e.title,
                   f.title as feed_title, f.ext_show_preview_images,
                   e.published, e.summary, e.ext_read, f.ext_open_entries_in_browser,
                   e.author_name
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.feed_id = ?
            ORDER BY e.published DESC;
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            return buildList {
                while (stmt.step()) {
                    add(statementToEntriesAdapterRow(stmt))
                }
            }
        }
    }

    /**
     * Returns unread, non-bookmarked entries belonging to any feed whose id
     * appears in [feedIds]. Used by the tag entries screen to surface
     * articles from every feed that has been tagged with the active tag.
     * Returns an empty list when [feedIds] is empty so we don't issue a
     * query that would match every entry.
     */
    fun selectUnreadByFeedIds(feedIds: List<String>): List<EntriesAdapterRow> {
        if (feedIds.isEmpty()) return emptyList()
        val placeholders = feedIds.joinToString(",") { "?" }
        return conn.prepare(
            """
            SELECT e.id, e.feed_id, e.ext_bookmarked, e.ext_og_image_url,
                   e.ext_og_image_width, e.ext_og_image_height, e.title,
                   f.title as feed_title, f.ext_show_preview_images,
                   e.published, e.summary, e.ext_read, f.ext_open_entries_in_browser,
                   e.author_name
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.feed_id IN ($placeholders)
              AND e.ext_read = 0 AND e.ext_bookmarked = 0
            ORDER BY e.published DESC;
            """
        ).use { stmt ->
            feedIds.forEachIndexed { index, feedId ->
                stmt.bindText(index + 1, feedId)
            }
            buildList {
                while (stmt.step()) {
                    add(statementToEntriesAdapterRow(stmt))
                }
            }
        }
    }

    fun selectUnread(): List<EntriesAdapterRow> {
        conn.prepare(
            """
            SELECT e.id, e.feed_id, e.ext_bookmarked, e.ext_og_image_url,
                   e.ext_og_image_width, e.ext_og_image_height, e.title,
                   f.title as feed_title, f.ext_show_preview_images,
                   e.published, e.summary, e.ext_read, f.ext_open_entries_in_browser,
                   e.author_name
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.ext_read = 0 AND e.ext_bookmarked = 0
            ORDER BY e.published DESC;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(statementToEntriesAdapterRow(stmt))
                }
            }
        }
    }

    fun selectUnreadCount(): Int {
        conn.prepare(
            """
            SELECT COUNT(*)
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.ext_read = 0 AND e.ext_bookmarked = 0;
            """
        ).use { stmt ->
            return if (stmt.step()) stmt.getInt(0) else 0
        }
    }

    /**
     * Returns the number of unread entries belonging to any feed that has
     * been tagged with [tagId]. Matches the semantics of the feeds tab's
     * per-feed unread badge: an entry counts as "unread" when
     * `ext_read = 0`, regardless of whether it has been bookmarked.
     * Returns 0 when no feed is tagged with [tagId] so the query never
     * joins against an empty feed set.
     */
    fun selectUnreadCountByTagId(tagId: String): Long {
        conn.prepare(
            """
            SELECT COUNT(*)
            FROM entry e
            JOIN feed_tag ft ON ft.feed_id = e.feed_id
            WHERE ft.tag_id = ? AND e.ext_read = 0;
            """
        ).use { stmt ->
            stmt.bindText(1, tagId)
            return if (stmt.step()) stmt.getLong(0) else 0L
        }
    }

    fun selectUnreadIds(): List<String> {
        conn.prepare(
            """
            SELECT id
            FROM entry
            WHERE ext_read = 0 AND ext_bookmarked = 0
            ORDER BY published DESC;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(stmt.getText(0))
                }
            }
        }
    }

    fun selectBookmarked(): List<EntriesAdapterRow> {
        conn.prepare(
            """
            SELECT e.id, e.feed_id, e.ext_bookmarked, e.ext_og_image_url,
                   e.ext_og_image_width, e.ext_og_image_height, e.title,
                   f.title as feed_title, f.ext_show_preview_images,
                   e.published, e.summary, e.ext_read, f.ext_open_entries_in_browser,
                   e.author_name
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.ext_bookmarked = 1
            ORDER BY e.published DESC;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(statementToEntriesAdapterRow(stmt))
                }
            }
        }
    }

    fun selectBookmarkedCount(): Int {
        conn.prepare(
            """
            SELECT COUNT(*)
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.ext_bookmarked = 1;
            """
        ).use { stmt ->
            return if (stmt.step()) stmt.getInt(0) else 0
        }
    }

    fun selectCount(): Long {
        conn.prepare("SELECT COUNT(*) FROM entry;").use { stmt ->
            return if (stmt.step()) stmt.getLong(0) else 0L
        }
    }

    fun selectMaxId(): String? {
        conn.prepare("SELECT MAX(CAST(id AS INTEGER)) FROM entry;").use { stmt ->
            stmt.step()
            return stmt.getTextOrNull(0)
        }
    }

    fun selectMaxUpdated(): String? {
        conn.prepare("SELECT MAX(updated) FROM entry;").use { stmt ->
            stmt.step()
            return stmt.getTextOrNull(0)
        }
    }

    fun updateReadAndReadSynced(id: String, extRead: Boolean, extReadSynced: Boolean) {
        conn.prepare("UPDATE entry SET ext_read = ?, ext_read_synced = ? WHERE id = ?;")
            .use { stmt ->
                stmt.bindInt(1, if (extRead) 1 else 0)
                stmt.bindInt(2, if (extReadSynced) 1 else 0)
                stmt.bindText(3, id)
                stmt.step()
            }
    }

    /**
     * Bulk-mark every unread, non-bookmarked entry as read and clear the
     * `ext_read_synced` flag so the next sync pushes the change to the
     * server. The predicate mirrors `selectUnread` so the rows that
     * disappear from the unread list are exactly the ones mutated here.
     *
     * Uses a single parameter-free statement so SQLite can plan the update
     * without per-row binding.
     */
    fun markAllUnreadAsRead() {
        conn.execSQL(
            "UPDATE entry SET ext_read = 1, ext_read_synced = 0 " +
                "WHERE ext_read = 0 AND ext_bookmarked = 0"
        )
    }

    fun updateBookmarkedAndBookmarkedSynced(
        id: String,
        extBookmarked: Boolean,
        extBookmarkedSynced: Boolean
    ) {
        conn.prepare("UPDATE entry SET ext_bookmarked = ?, ext_bookmarked_synced = ? WHERE id = ?;")
            .use { stmt ->
                stmt.bindInt(1, if (extBookmarked) 1 else 0)
                stmt.bindInt(2, if (extBookmarkedSynced) 1 else 0)
                stmt.bindText(3, id)
                stmt.step()
            }
    }

    data class EntryWithoutContent(
        val summary: String?,
        val id: String,
        val feedId: String,
        val title: String,
        val published: OffsetDateTime,
        val updated: OffsetDateTime,
        val authorName: String,
        val extRead: Boolean,
        val extReadSynced: Boolean,
        val extBookmarked: Boolean,
        val extBookmarkedSynced: Boolean,
        val extCommentsUrl: String,
        val extOpenGraphImageChecked: Boolean,
        val extOpenGraphImageUrl: String,
        val extOpenGraphImageWidth: Int,
        val extOpenGraphImageHeight: Int,
        val extOpenGraphImageFetchedAt: OffsetDateTime?,
        val extOpenGraphImageLog: String,
    )

    fun selectByReadSynced(extReadSynced: Boolean): List<EntryWithoutContent> {
        conn.prepare(
            """
            SELECT
                summary,
                id,
                feed_id,
                title,
                published,
                updated,
                author_name,
                ext_read,
                ext_read_synced,
                ext_bookmarked,
                ext_bookmarked_synced,
                ext_comments_url,
                ext_og_image_checked,
                ext_og_image_url,
                ext_og_image_width,
                ext_og_image_height,
                ext_og_image_fetched_at,
                ext_og_log
            FROM entry
            WHERE ext_read_synced = ?
            ORDER BY published DESC;
            """
        ).use { stmt ->
            stmt.bindInt(1, if (extReadSynced) 1 else 0)
            return buildList {
                while (stmt.step()) {
                    add(statementToEntryWithoutContent(stmt))
                }
            }
        }
    }

    fun selectByBookmarkedSynced(extBookmarkedSynced: Boolean): List<EntryWithoutContent> {
        conn.prepare(
            """
            SELECT
                summary,
                id,
                feed_id,
                title,
                published,
                updated,
                author_name,
                ext_read,
                ext_read_synced,
                ext_bookmarked,
                ext_bookmarked_synced,
                ext_comments_url,
                ext_og_image_checked,
                ext_og_image_url,
                ext_og_image_width,
                ext_og_image_height,
                ext_og_image_fetched_at,
                ext_og_log
            FROM entry
            WHERE ext_bookmarked_synced = ?
            ORDER BY published DESC;
            """
        ).use { stmt ->
            stmt.bindInt(1, if (extBookmarkedSynced) 1 else 0)
            return buildList {
                while (stmt.step()) {
                    add(statementToEntryWithoutContent(stmt))
                }
            }
        }
    }

    fun updateOgImageChecked(extOgImageChecked: Boolean, id: String) {
        conn.prepare("UPDATE entry SET ext_og_image_checked = ? WHERE id = ?;").use { stmt ->
            stmt.bindInt(1, if (extOgImageChecked) 1 else 0)
            stmt.bindText(2, id)
            stmt.step()
        }
    }

    fun updateOgLog(extOgLog: String, id: String) {
        conn.prepare("UPDATE entry SET ext_og_log = ? WHERE id = ?;").use { stmt ->
            stmt.bindText(1, extOgLog)
            stmt.bindText(2, id)
            stmt.step()
        }
    }

    fun updateOgImage(
        extOgImageUrl: String,
        extOgImageWidth: Long,
        extOgImageHeight: Long,
        extOgImageFetchedAt: OffsetDateTime,
        id: String
    ) {
        conn.prepare("UPDATE entry SET ext_og_image_url = ?, ext_og_image_width = ?, ext_og_image_height = ?, ext_og_image_fetched_at = ?, ext_og_image_checked = 1 WHERE id = ?;")
            .use { stmt ->
                stmt.bindText(1, extOgImageUrl)
                stmt.bindLong(2, extOgImageWidth)
                stmt.bindLong(3, extOgImageHeight)
                stmt.bindText(4, extOgImageFetchedAt.toString())
                stmt.bindText(5, id)
                stmt.step()
            }
    }

    /**
     * Returns the number of entries whose `ext_og_image_fetched_at` is greater
     * than [since] (and not the empty default). Used by the entries view to
     * poll for newly-downloaded OG images without subscribing to a callback.
     */
    fun countByOgImageFetchedAfter(since: OffsetDateTime): Long {
        conn.prepare(
            """
            SELECT COUNT(*) FROM entry
            WHERE ext_og_image_fetched_at > ? AND ext_og_image_fetched_at != ''
            """,
        ).use { stmt ->
            stmt.bindText(1, since.toString())
            return if (stmt.step()) stmt.getLong(0) else 0L
        }
    }

    fun updateReadSynced(extReadSynced: Boolean, id: String) {
        conn.prepare("UPDATE entry SET ext_read_synced = ? WHERE id = ?;").use { stmt ->
            stmt.bindInt(1, if (extReadSynced) 1 else 0)
            stmt.bindText(2, id)
            stmt.step()
        }
    }

    fun updateBookmarkedSynced(extBookmarkedSynced: Boolean, id: String) {
        conn.prepare("UPDATE entry SET ext_bookmarked_synced = ? WHERE id = ?;").use { stmt ->
            stmt.bindInt(1, if (extBookmarkedSynced) 1 else 0)
            stmt.bindText(2, id)
            stmt.step()
        }
    }

    fun deleteByFeedId(feedId: String) {
        conn.prepare("DELETE FROM entry WHERE feed_id = ?").use { stmt ->
            stmt.bindText(1, feedId)
            stmt.step()
        }
    }

    fun deleteAll() {
        conn.execSQL("DELETE FROM entry")
    }

    /**
     * One pending OG-image job: an unchecked entry. Per-feed previews-
     * disabled rows are filtered out at the SQL level, so the fetcher
     * never needs to re-check this candidate against the per-feed
     * setting — it can hand every row straight to the network path.
     */
    data class OgImageCandidate(
        val id: String,
        val title: String,
        val extOpenGraphImageLog: String,
    )

    /**
     * Returns up to [limit] entries that have not yet been checked for an
     * OG image, skipping rows whose feed has `ext_show_preview_images = 0`
     * (an explicit per-feed "hide" — only the `null` "follow global" and
     * the explicit `1` cases reach the fetcher). The filter is at the SQL
     * level so the loop doesn't repeatedly load, log, and re-query the
     * same per-feed-hidden rows every iteration. Rows are left
     * `ext_og_image_checked = 0` here, so flipping the per-feed toggle on
     * later will surface them on the next iteration.
     *
     * Inner-joined on `feed`, so an entry whose feed has been deleted
     * (orphan) is silently dropped — which is the right behaviour, since
     * the OG fetcher can't resolve a `link` row for it either.
     */
    fun selectPendingOgImageEntries(limit: Long): List<OgImageCandidate> {
        conn.prepare(
            """
            SELECT e.id, e.title, e.ext_og_log
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.ext_og_image_checked = 0
              AND (f.ext_show_preview_images IS NULL OR f.ext_show_preview_images != 0)
            ORDER BY e.published DESC
            LIMIT ?
            """
        ).use { stmt ->
            stmt.bindLong(1, limit)
            return buildList {
                while (stmt.step()) {
                    add(
                        OgImageCandidate(
                            id = stmt.getText(0),
                            title = stmt.getText(1),
                            extOpenGraphImageLog = stmt.getText(2),
                        )
                    )
                }
            }
        }
    }

    private fun getColumnIndex(stmt: SQLiteStatement, name: String): Int {
        return stmt.getColumnNames().indexOf(name)
    }

    private fun SQLiteStatement.getTextOrNull(index: Int): String? {
        return if (isNull(index)) null else getText(index)
    }

    private fun statementToEntriesAdapterRow(stmt: SQLiteStatement): EntriesAdapterRow {
        return EntriesAdapterRow(
            id = stmt.getTextOrNull(getColumnIndex(stmt, "id")) ?: "",
            feedId = stmt.getTextOrNull(getColumnIndex(stmt, "feed_id")) ?: "",
            extBookmarked = stmt.getInt(getColumnIndex(stmt, "ext_bookmarked")) == 1,
            extShowPreviewImages = stmt.getBoolOrNull(
                getColumnIndex(
                    stmt,
                    "ext_show_preview_images"
                )
            ),
            extOpenGraphImageUrl = stmt.getTextOrNull(getColumnIndex(stmt, "ext_og_image_url"))
                ?: "",
            extOpenGraphImageWidth = stmt.getInt(getColumnIndex(stmt, "ext_og_image_width")),
            extOpenGraphImageHeight = stmt.getInt(getColumnIndex(stmt, "ext_og_image_height")),
            title = stmt.getTextOrNull(getColumnIndex(stmt, "title")) ?: "",
            feedTitle = stmt.getTextOrNull(getColumnIndex(stmt, "feed_title")) ?: "",
            published = runCatching {
                OffsetDateTime.parse(
                    stmt.getTextOrNull(
                        getColumnIndex(
                            stmt,
                            "published"
                        )
                    )
                )
            }.getOrDefault(OffsetDateTime.now()),
            authorName = stmt.getTextOrNull(getColumnIndex(stmt, "author_name")) ?: "",
            summary = stmt.getTextOrNull(getColumnIndex(stmt, "summary")) ?: "",
            extRead = stmt.getInt(getColumnIndex(stmt, "ext_read")) == 1,
            extOpenEntriesInBrowser = stmt.getInt(
                getColumnIndex(
                    stmt,
                    "ext_open_entries_in_browser"
                )
            ) == 1,
        )
    }

    private fun statementToSelectByQuery(stmt: SQLiteStatement): SelectByQuery {
        return SelectByQuery(
            id = stmt.getTextOrNull(0) ?: "",
            extShowPreviewImages = stmt.getBoolOrNull(1),
            extOpenGraphImageUrl = stmt.getTextOrNull(2) ?: "",
            extOpenGraphImageWidth = stmt.getInt(3),
            extOpenGraphImageHeight = stmt.getInt(4),
            title = stmt.getTextOrNull(5) ?: "",
            feedTitle = stmt.getTextOrNull(6) ?: "",
            published = runCatching { OffsetDateTime.parse(stmt.getTextOrNull(7)) }.getOrDefault(
                OffsetDateTime.now()
            ),
            summary = stmt.getTextOrNull(8) ?: "",
            extRead = stmt.getInt(9) == 1,
            extOpenEntriesInBrowser = stmt.getInt(10) == 1,
            authorName = stmt.getTextOrNull(11) ?: "",
        )
    }

    private fun statementToEntryWithoutContent(stmt: SQLiteStatement): EntryWithoutContent {
        return EntryWithoutContent(
            summary = stmt.getTextOrNull(0),
            id = stmt.getTextOrNull(1) ?: "",
            feedId = stmt.getTextOrNull(2) ?: "",
            title = stmt.getTextOrNull(3) ?: "",
            published = runCatching { OffsetDateTime.parse(stmt.getTextOrNull(4)) }.getOrDefault(
                OffsetDateTime.now()
            ),
            updated = runCatching { OffsetDateTime.parse(stmt.getTextOrNull(5)) }.getOrDefault(
                OffsetDateTime.now()
            ),
            authorName = stmt.getTextOrNull(6) ?: "",
            extRead = stmt.getInt(7) == 1,
            extReadSynced = stmt.getInt(8) == 1,
            extBookmarked = stmt.getInt(9) == 1,
            extBookmarkedSynced = stmt.getInt(10) == 1,
            extCommentsUrl = stmt.getTextOrNull(11) ?: "",
            extOpenGraphImageChecked = stmt.getInt(12) == 1,
            extOpenGraphImageUrl = stmt.getTextOrNull(13) ?: "",
            extOpenGraphImageWidth = stmt.getInt(14),
            extOpenGraphImageHeight = stmt.getInt(15),
            extOpenGraphImageFetchedAt = stmt.getTextOrNull(16)?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
            },
            extOpenGraphImageLog = stmt.getTextOrNull(17) ?: "[]",
        )
    }

    data class SelectByQuery(
        override val id: String,
        override val extShowPreviewImages: Boolean?,
        override val extOpenGraphImageUrl: String,
        override val extOpenGraphImageWidth: Int,
        override val extOpenGraphImageHeight: Int,
        override val title: String,
        override val feedTitle: String,
        override val published: OffsetDateTime,
        override val authorName: String,
        override val summary: String?,
        override val extRead: Boolean,
        override val extOpenEntriesInBrowser: Boolean,
    ) : org.vestifeed.entries.EntryRowMappable

    fun selectByQuery(query: String): List<SelectByQuery> {
        val searchQuery = "%$query%"
        val sql = """
            SELECT e.id, f.ext_show_preview_images, e.ext_og_image_url, e.ext_og_image_width,
                   e.ext_og_image_height, e.title, f.title as feed_title, e.published,
                   e.summary, e.ext_read, f.ext_open_entries_in_browser, e.author_name
            FROM entry e
            JOIN feed f ON f.id = e.feed_id
            WHERE e.title LIKE ? OR e.summary LIKE ? OR e.content_text LIKE ?
            LIMIT 500
        """.trimIndent()

        return conn.prepare(sql).use { stmt ->
            stmt.bindText(1, searchQuery)
            stmt.bindText(2, searchQuery)
            stmt.bindText(3, searchQuery)
            buildList {
                while (stmt.step()) {
                    add(
                        SelectByQuery(
                            id = stmt.getText(0),
                            extShowPreviewImages = stmt.getBoolOrNull(1),
                            extOpenGraphImageUrl = stmt.getText(2),
                            extOpenGraphImageWidth = stmt.getInt(3),
                            extOpenGraphImageHeight = stmt.getInt(4),
                            title = stmt.getText(5),
                            feedTitle = stmt.getText(6),
                            published = OffsetDateTime.parse(stmt.getText(7)),
                            summary = stmt.getTextOrNull(8),
                            extRead = stmt.getInt(9) == 1,
                            extOpenEntriesInBrowser = stmt.getInt(10) == 1,
                            authorName = stmt.getText(11),
                        )
                    )
                }
            }
        }
    }
}