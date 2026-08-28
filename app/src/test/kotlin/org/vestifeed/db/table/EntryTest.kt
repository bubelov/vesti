package org.vestifeed.db.table

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import java.time.OffsetDateTime
import java.util.UUID

class EntryTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = Database(BundledSQLiteDriver(), ":memory:")
    }

    @Test
    fun entrySchema_createTableStatement() {
        val statement = EntryTable.SCHEMA
        assertTrue(statement.contains("CREATE TABLE entry"))
        assertTrue(statement.contains("id TEXT PRIMARY KEY NOT NULL"))
        assertTrue(statement.contains("feed_id TEXT NOT NULL"))
        assertTrue(statement.contains("ext_og_image_url TEXT NOT NULL"))
        assertTrue(statement.contains("ext_og_image_fetched_at TEXT NOT NULL DEFAULT ''"))
        assertTrue(statement.contains("ext_og_log TEXT NOT NULL DEFAULT '[]'"))
    }

    @Test
    fun entryQueries_insertOrReplace_insertsNewEntry() {
        val entry = createEntry(
            extOpenGraphImageChecked = true,
            extOpenGraphImageUrl = "https://example.com/image.png",
        )
        db.entry.insertOrReplace(listOf(entry))

        val result = db.entry.selectById(entry.id)!!
        assertEquals(entry.title, result.title)
        assertEquals("https://example.com/image.png", result.extOpenGraphImageUrl)
        assertTrue(result.extOpenGraphImageChecked)
    }

    @Test
    fun entryQueries_insertOrReplace_updatesServerFieldsOnExistingRow() {
        val entry = createEntry(
            title = "Original Title",
            summary = "Original summary",
            extRead = true,
            extBookmarked = true,
        )
        db.entry.insertOrReplace(listOf(entry))

        val resynced = entry.copy(
            title = "Updated Title",
            summary = "Updated summary",
        )
        db.entry.insertOrReplace(listOf(resynced))

        val result = db.entry.selectById(entry.id)!!
        assertEquals("Updated Title", result.title)
        assertEquals("Updated summary", result.summary)
        // Local state is preserved across upserts; mutation flows through
        // updateReadAndReadSynced / updateBookmarkedAndBookmarkedSynced.
        assertTrue(result.extRead)
        assertTrue(result.extBookmarked)
    }

    /**
     * Pull-to-refresh and any local mutation (`setRead`, `setBookmarked`)
     * trigger a sync that re-inserts every entry with the parser defaults
     * (e.g. `extRead = false`, blank OG image fields). `INSERT OR REPLACE`
     * would wipe the user's local state and undo their swipe. The upsert
     * now leaves all `ext_*` local-state columns untouched when a row
     * already exists, so re-syncing an entry can never blank a preview,
     * un-read a marked-as-read entry, or un-bookmark a bookmarked one.
     */
    @Test
    fun entryQueries_insertOrReplace_preservesLocalState() {
        val original = createEntry(
            extRead = true,
            extReadSynced = false,
            extBookmarked = true,
            extBookmarkedSynced = false,
            extOpenGraphImageChecked = true,
            extOpenGraphImageUrl = "https://example.com/og.png",
            extOpenGraphImageWidth = 1200,
            extOpenGraphImageHeight = 630,
            extOpenGraphImageFetchedAt = OffsetDateTime.parse("2026-07-15T10:00:00Z"),
        )
        db.entry.insertOrReplace(listOf(original))

        val resynced = original.copy(
            extRead = false,
            extReadSynced = true,
            extBookmarked = false,
            extBookmarkedSynced = true,
            extOpenGraphImageChecked = false,
            extOpenGraphImageUrl = "",
            extOpenGraphImageWidth = 0,
            extOpenGraphImageHeight = 0,
            extOpenGraphImageFetchedAt = null,
        )
        db.entry.insertOrReplace(listOf(resynced))

        val result = db.entry.selectById(original.id)!!
        assertTrue(result.extRead)
        assertEquals(false, result.extReadSynced)
        assertTrue(result.extBookmarked)
        assertEquals(false, result.extBookmarkedSynced)
        assertTrue(result.extOpenGraphImageChecked)
        assertEquals("https://example.com/og.png", result.extOpenGraphImageUrl)
        assertEquals(1200, result.extOpenGraphImageWidth)
        assertEquals(630, result.extOpenGraphImageHeight)
        assertEquals(OffsetDateTime.parse("2026-07-15T10:00:00Z"), result.extOpenGraphImageFetchedAt)
    }

    /**
     * When the row's `ext_read_synced` / `ext_bookmarked_synced` are already
     * `1` (local state matches the server), a re-sync that brings the
     * server's flipped values must overwrite them. Otherwise the unread
     * counter would keep showing entries the user has read on the server
     * (e.g. via the Miniflux web UI) and never converge with the server
     * count.
     */
    @Test
    fun entryQueries_insertOrReplace_overwritesSyncedStateFromServer() {
        val original = createEntry(
            extRead = false,
            extReadSynced = true,
            extBookmarked = false,
            extBookmarkedSynced = true,
        )
        db.entry.insertOrReplace(listOf(original))

        val fromServer = original.copy(
            extRead = true,
            extReadSynced = true,
            extBookmarked = true,
            extBookmarkedSynced = true,
        )
        db.entry.insertOrReplace(listOf(fromServer))

        val result = db.entry.selectById(original.id)!!
        assertTrue(result.extRead)
        assertEquals(true, result.extReadSynced)
        assertTrue(result.extBookmarked)
        assertEquals(true, result.extBookmarkedSynced)
    }

    @Test
    fun entryQueries_insertOrReplace_emptyList() {
        db.entry.insertOrReplace(emptyList())
        assertEquals(0L, db.entry.selectCount())
    }

    @Test
    fun entryQueries_markAllUnreadAsRead_marksUnreadAndClearsSyncedFlag() {
        val unread = createEntry(
            extRead = false,
            extReadSynced = true,
            extBookmarked = false,
        )
        val unreadUnsynced = createEntry(
            extRead = false,
            extReadSynced = false,
            extBookmarked = false,
        )
        val alreadyRead = createEntry(
            extRead = true,
            extReadSynced = true,
            extBookmarked = false,
        )
        val bookmarkedUnread = createEntry(
            extRead = false,
            extReadSynced = true,
            extBookmarked = true,
        )
        val bookmarkedRead = createEntry(
            extRead = true,
            extReadSynced = true,
            extBookmarked = true,
        )

        db.entry.insertOrReplace(
            listOf(unread, unreadUnsynced, alreadyRead, bookmarkedUnread, bookmarkedRead)
        )

        db.entry.markAllUnreadAsRead()

        val unreadAfter = db.entry.selectById(unread.id)!!
        assertEquals(true, unreadAfter.extRead)
        assertEquals(false, unreadAfter.extReadSynced)

        val unreadUnsyncedAfter = db.entry.selectById(unreadUnsynced.id)!!
        assertEquals(true, unreadUnsyncedAfter.extRead)
        assertEquals(false, unreadUnsyncedAfter.extReadSynced)

        val alreadyReadAfter = db.entry.selectById(alreadyRead.id)!!
        assertEquals(true, alreadyReadAfter.extRead)
        assertEquals(true, alreadyReadAfter.extReadSynced)

        val bookmarkedUnreadAfter = db.entry.selectById(bookmarkedUnread.id)!!
        assertEquals(false, bookmarkedUnreadAfter.extRead)
        assertEquals(true, bookmarkedUnreadAfter.extReadSynced)

        val bookmarkedReadAfter = db.entry.selectById(bookmarkedRead.id)!!
        assertEquals(true, bookmarkedReadAfter.extRead)
        assertEquals(true, bookmarkedReadAfter.extReadSynced)
    }

    @Test
    fun entryQueries_markAllUnreadAsRead_isNoOpWhenEmpty() {
        db.entry.markAllUnreadAsRead()
        assertEquals(0L, db.entry.selectCount())
    }

    private fun createEntry(
        id: String = UUID.randomUUID().toString(),
        feedId: String = UUID.randomUUID().toString(),
        title: String = "Test Entry",
        contentText: String = "",
        summary: String? = "",
        extRead: Boolean = false,
        extReadSynced: Boolean = true,
        extBookmarked: Boolean = false,
        extBookmarkedSynced: Boolean = true,
        extOpenGraphImageChecked: Boolean = false,
        extOpenGraphImageUrl: String = "",
        extOpenGraphImageWidth: Int = 0,
        extOpenGraphImageHeight: Int = 0,
        extOpenGraphImageFetchedAt: OffsetDateTime? = null,
        extOpenGraphImageLog: String = "[]",
    ) = EntryTable.Entry(
        contentType = "html",
        contentSrc = "",
        contentText = contentText,
        summary = summary,
        id = id,
        feedId = feedId,
        title = title,
        published = OffsetDateTime.now(),
        updated = OffsetDateTime.now(),
        authorName = "",
        extRead = extRead,
        extReadSynced = extReadSynced,
        extBookmarked = extBookmarked,
        extBookmarkedSynced = extBookmarkedSynced,
        extCommentsUrl = "",
        extOpenGraphImageChecked = extOpenGraphImageChecked,
        extOpenGraphImageUrl = extOpenGraphImageUrl,
        extOpenGraphImageWidth = extOpenGraphImageWidth,
        extOpenGraphImageHeight = extOpenGraphImageHeight,
        extOpenGraphImageFetchedAt = extOpenGraphImageFetchedAt,
        extOpenGraphImageLog = extOpenGraphImageLog,
    )
}
