package org.vestifeed.db.table

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlin.time.Clock
import kotlin.time.Instant
import org.vestifeed.db.bindTextOrNull
import org.vestifeed.db.getTextOrNull
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.util.toInstant
import org.vestifeed.util.toInstantOrNull

class LinkTable(private val conn: SQLiteConnection) {
    companion object {
        const val SCHEMA = """
            CREATE TABLE link (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                href TEXT NOT NULL,
                rel TEXT,
                type TEXT,
                hreflang TEXT,
                title TEXT,
                length TEXT,
                feed_id TEXT REFERENCES feed(id),
                entry_id TEXT REFERENCES entry(id),
                ext_enclosure_download_progress REAL,
                ext_cache_uri TEXT,
                ext_played INTEGER NOT NULL DEFAULT 0,
                ext_played_at TEXT,
                UNIQUE(feed_id, href, rel),
                UNIQUE(entry_id, href, rel),
                CHECK ((feed_id IS NULL) <> (entry_id IS NULL))
            ) STRICT;
        """

        /**
         * The projection every `Link` query returns, in exactly the order
         * [toLink] reads it. Shared by every select so the rows cannot drift
         * apart, and so only one place has to stay in step with [Link].
         */
        private const val COLUMNS = """
            id,
            feed_id,
            entry_id,
            ext_enclosure_download_progress,
            ext_cache_uri,
            ext_played,
            ext_played_at,
            href,
            rel,
            type,
            hreflang,
            title,
            length
        """
    }

    data class Link(
        // meta
        val id: Long?,
        val feedId: String?,
        val entryId: String?,
        // core rfc fields
        val href: String,
        val rel: AtomLinkRel?,
        val type: String?,
        val hreflang: String?,
        val title: String?,
        val length: Long?,
        // extensions
        val extEnclosureDownloadProgress: Double?,
        val extCacheUri: String?,
        val extPlayed: Boolean = false,
        val extPlayedAt: Instant? = null,
    )

    /**
     * One row per audio enclosure of an entry that hasn't been soft-deleted
     * (`entry.ext_read` aside — every entry with an audio enclosure is
     * surfaced regardless of read state so the Podcasts tab mirrors a
     * typical feed-reader podcast experience). Sorted by entry publish date
     * desc, with the link's own id as a tiebreaker so two enclosures of the
     * same entry keep a stable order across queries.
     */
    data class AudioEnclosureRow(
        val linkId: Long,
        val entryId: String,
        val feedId: String,
        val entryTitle: String,
        val entryPublished: Instant,
        val feedTitle: String,
        val href: String,
        val type: String,
        val extEnclosureDownloadProgress: Double?,
        val extCacheUri: String?,
        val extPlayed: Boolean,
        val extRead: Boolean,
        val extBookmarked: Boolean,
    )

    suspend fun insertForFeed(feedId: String, links: List<Link>) {
        conn.prepare(
            """
            INSERT OR REPLACE INTO link (
                feed_id,
                href,
                rel,
                type,
                hreflang,
                title,
                length,
                ext_enclosure_download_progress,
                ext_cache_uri,
                ext_played,
                ext_played_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """
        ).use { stmt ->
            links.forEach { link ->
                stmt.bindText(1, feedId)
                stmt.bindText(2, link.href)
                stmt.bindTextOrNull(3, relToString(link.rel))
                stmt.bindTextOrNull(4, link.type)
                stmt.bindTextOrNull(5, link.hreflang)
                stmt.bindTextOrNull(6, link.title)
                stmt.bindTextOrNull(7, link.length?.toString())
                stmt.bindTextOrNull(8, link.extEnclosureDownloadProgress?.toString())
                stmt.bindTextOrNull(9, link.extCacheUri)
                stmt.bindLong(10, if (link.extPlayed) 1 else 0)
                stmt.bindTextOrNull(11, link.extPlayedAt?.toString())
                stmt.step()
                stmt.reset()
            }
        }
    }

    suspend fun insertForEntry(entryId: String, links: List<Link>) {
        conn.prepare(
            """
            INSERT OR IGNORE INTO link (
                entry_id,
                href,
                rel,
                type,
                hreflang,
                title,
                length,
                ext_enclosure_download_progress,
                ext_cache_uri,
                ext_played,
                ext_played_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """
        ).use { stmt ->
            links.forEach { link ->
                stmt.bindText(1, entryId)
                stmt.bindText(2, link.href)
                stmt.bindTextOrNull(3, relToString(link.rel))
                stmt.bindTextOrNull(4, link.type)
                stmt.bindTextOrNull(5, link.hreflang)
                stmt.bindTextOrNull(6, link.title)
                stmt.bindTextOrNull(7, link.length?.toString())
                stmt.bindTextOrNull(8, link.extEnclosureDownloadProgress?.toString())
                stmt.bindTextOrNull(9, link.extCacheUri)
                stmt.bindLong(10, if (link.extPlayed) 1 else 0)
                stmt.bindTextOrNull(11, link.extPlayedAt?.toString())
                stmt.step()
                stmt.reset()
            }
        }
    }

    suspend fun selectByFeedId(feedId: String): List<Link> {
        conn.prepare(
            """
            SELECT $COLUMNS
            FROM link
            WHERE feed_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            return buildList {
                while (stmt.step()) {
                    add(stmt.toLink())
                }
            }
        }
    }

    suspend fun selectByEntryId(entryId: String): List<Link> {
        conn.prepare(
            """
            SELECT $COLUMNS
            FROM link
            WHERE entry_id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, entryId)
            return buildList {
                while (stmt.step()) {
                    add(stmt.toLink())
                }
            }
        }
    }

    suspend fun selectAllByFeedId(feedIds: List<String>): Map<String, List<Link>> {
        if (feedIds.isEmpty()) return emptyMap()
        val placeholders = feedIds.joinToString(",") { "?" }
        conn.prepare(
            """
            SELECT $COLUMNS
            FROM link
            WHERE feed_id IN ($placeholders);
            """
        ).use { stmt ->
            feedIds.forEachIndexed { index, id ->
                stmt.bindText(index + 1, id)
            }
            val result = mutableMapOf<String, MutableList<Link>>()
            while (stmt.step()) {
                val feedId = stmt.getText(1)
                val link = stmt.toLink()
                result.getOrPut(feedId) { mutableListOf() }.add(link)
            }
            return result
        }
    }

    suspend fun updateEnclosureProgress(linkId: Long, progress: Double?, cacheUri: String?) {
        conn.prepare(
            """
            UPDATE link
            SET ext_enclosure_download_progress = ?, ext_cache_uri = ?
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindTextOrNull(1, progress?.toString())
            stmt.bindTextOrNull(2, cacheUri)
            stmt.bindLong(3, linkId)
            stmt.step()
        }
    }

    /**
     * Toggle the per-enclosure "played" flag and stamp [playedAt]. Pass
     * `null` for [playedAt] when clearing; the listener click only ever
     * sets a value, so callers should pass `Clock.System.now()` then.
     */
    suspend fun updatePlayedAndPlayedAt(linkId: Long, played: Boolean, playedAt: Instant?) {
        conn.prepare(
            """
            UPDATE link
            SET ext_played = ?, ext_played_at = ?
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindLong(1, if (played) 1 else 0)
            stmt.bindTextOrNull(2, playedAt?.toString())
            stmt.bindLong(3, linkId)
            stmt.step()
        }
    }

    suspend fun deleteById(id: Long) {
        conn.prepare("DELETE FROM link WHERE id = ?;")
            .use { stmt ->
                stmt.bindLong(1, id)
                stmt.step()
            }
    }

    suspend fun deleteByFeedId(feedId: String) {
        conn.prepare("DELETE FROM link WHERE feed_id = ?;")
            .use { stmt ->
                stmt.bindText(1, feedId)
                stmt.step()
            }
    }

    /**
     * Deletes every link owned by [feedId]: the feed's own links and those of
     * its entries. Entry links must be removed before the entries themselves,
     * otherwise the `entry_id` foreign key blocks the entry delete.
     */
    suspend fun deleteForFeed(feedId: String) {
        conn.prepare(
            """
            DELETE FROM link
            WHERE feed_id = ?
               OR entry_id IN (SELECT id FROM entry WHERE feed_id = ?);
            """
        ).use { stmt ->
            stmt.bindText(1, feedId)
            stmt.bindText(2, feedId)
            stmt.step()
        }
    }

    suspend fun deleteByEntryId(entryId: String) {
        conn.prepare("DELETE FROM link WHERE entry_id = ?;")
            .use { stmt ->
                stmt.bindText(1, entryId)
                stmt.step()
            }
    }

    suspend fun deleteAll() {
        conn.execSQL("DELETE FROM link")
    }

    suspend fun selectAll(): List<Link> {
        conn.prepare(
            """
            SELECT $COLUMNS
            FROM link;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(stmt.toLink())
                }
            }
        }
    }

    /**
     * Whether any entry has an audio enclosure — i.e. whether the Podcasts tab
     * has anything to show. Used to hide the tab for readers without any
     * podcast feeds.
     */
    suspend fun hasAudioEnclosures(): Boolean {
        conn.prepare(
            "SELECT EXISTS(SELECT 1 FROM link WHERE rel = 'Enclosure' AND type LIKE 'audio%');"
        ).use { stmt ->
            return stmt.step() && stmt.getInt(0) == 1
        }
    }

    /**
     * One row per audio enclosure of an entry, joined with the entry's read /
     * bookmark state and the feed title. Used by the Podcasts tab to render
     * its list. `WHERE l.type LIKE 'audio%'` follows the same predicate
     * [org.vestifeed.enclosures.EnclosuresRepo.downloadAudioEnclosure] uses to
     * gate downloads, so what's listed matches what can be downloaded.
     */
    suspend fun selectAudioEnclosureRows(): List<AudioEnclosureRow> {
        conn.prepare(
            """
            SELECT
                l.id,
                l.entry_id,
                e.feed_id,
                e.title,
                e.published,
                f.title,
                l.href,
                l.type,
                l.ext_enclosure_download_progress,
                l.ext_cache_uri,
                l.ext_played,
                e.ext_read,
                e.ext_bookmarked
            FROM link l
            JOIN entry e ON e.id = l.entry_id
            JOIN feed f ON f.id = e.feed_id
            WHERE l.rel = 'Enclosure' AND l.type LIKE 'audio%'
            ORDER BY e.published DESC, l.id ASC;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(
                        AudioEnclosureRow(
                            linkId = stmt.getLong(0),
                            entryId = stmt.getText(1),
                            feedId = stmt.getText(2),
                            entryTitle = stmt.getText(3),
                            entryPublished = runCatching { stmt.getText(4).toInstant() }
                                .getOrDefault(Clock.System.now()),
                            feedTitle = stmt.getText(5),
                            href = stmt.getText(6),
                            type = stmt.getText(7),
                            extEnclosureDownloadProgress = stmt.getTextOrNull(8)?.toDoubleOrNull(),
                            extCacheUri = stmt.getTextOrNull(9),
                            extPlayed = stmt.getInt(10) == 1,
                            extRead = stmt.getInt(11) == 1,
                            extBookmarked = stmt.getInt(12) == 1,
                        )
                    )
                }
            }
        }
    }

    suspend fun selectByEntryIdAndHref(entryId: String, href: String): Link? {
        conn.prepare(
            """
            SELECT $COLUMNS
            FROM link
            WHERE entry_id = ? AND href = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, entryId)
            stmt.bindText(2, href)
            return if (stmt.step()) {
                stmt.toLink()
            } else {
                null
            }
        }
    }

    suspend fun selectById(id: Long): Link? {
        conn.prepare(
            """
            SELECT $COLUMNS
            FROM link
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindLong(1, id)
            return if (stmt.step()) {
                stmt.toLink()
            } else {
                null
            }
        }
    }

    /**
     * Maps one row of [COLUMNS], positionally. `feed_id` and `entry_id` are
     * read from the row: the table's CHECK constraint guarantees exactly one
     * of them is set, so a feed link and an entry link both map correctly
     * without the caller passing the owner in.
     */
    private fun SQLiteStatement.toLink(): Link {
        return Link(
            id = getLong(0),
            feedId = getTextOrNull(1),
            entryId = getTextOrNull(2),
            extEnclosureDownloadProgress = getTextOrNull(3)?.toDoubleOrNull(),
            extCacheUri = getTextOrNull(4),
            extPlayed = getInt(5) == 1,
            extPlayedAt = getTextOrNull(6)?.toInstantOrNull(),
            href = getText(7),
            rel = stringToRel(getTextOrNull(8)),
            type = getTextOrNull(9),
            hreflang = getTextOrNull(10),
            title = getTextOrNull(11),
            length = getTextOrNull(12)?.toLongOrNull(),
        )
    }

    private fun relToString(rel: AtomLinkRel?): String? {
        return when (rel) {
            is AtomLinkRel.Alternate -> "Alternate"
            is AtomLinkRel.Enclosure -> "Enclosure"
            is AtomLinkRel.Self -> "Self"
            is AtomLinkRel.Related -> "Related"
            is AtomLinkRel.Via -> "Via"
            is AtomLinkRel.Custom -> rel.value
            null -> null
        }
    }

    private fun stringToRel(str: String?): AtomLinkRel? {
        if (str == null) return null
        return when (str) {
            "Alternate" -> AtomLinkRel.Alternate
            "Enclosure" -> AtomLinkRel.Enclosure
            "Self" -> AtomLinkRel.Self
            "Related" -> AtomLinkRel.Related
            "Via" -> AtomLinkRel.Via
            else -> AtomLinkRel.Custom(str)
        }
    }
}
