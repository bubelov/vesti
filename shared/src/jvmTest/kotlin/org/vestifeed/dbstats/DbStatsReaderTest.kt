package org.vestifeed.dbstats

import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.testDb

class DbStatsReaderTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = testDb()
    }

    @Test
    fun read_returnsTheSchemaVersionAndEveryUserTable() = runBlocking<Unit> {
        val stats = db.readStats()

        assertEquals(16, stats.version)
        assertEquals(
            listOf("conf", "entry", "feed", "feed_tag", "link", "tag"),
            stats.tables.map { it.name },
        )
        stats.tables.forEach { assertEquals(0L, it.rowCount) }
    }

    @Test
    fun read_countsRowsPerTable() = runBlocking<Unit> {
        db.feed.insertOrReplace(feed())
        db.entry.insertOrReplace(listOf(entry(), entry(), entry()))

        val tables = db.readStats().tables.associateBy { it.name }

        assertEquals(1L, tables.getValue("feed").rowCount)
        assertEquals(3L, tables.getValue("entry").rowCount)
        assertEquals(0L, tables.getValue("link").rowCount)
    }

    @Test
    fun read_reportsReadBookmarkAndPendingCountsForEntries() = runBlocking<Unit> {
        val entries = listOf(
            // Read on the server, nothing pending.
            entry(extRead = true, extReadSynced = true),
            // Marked read locally, not pushed yet.
            entry(extRead = true, extReadSynced = false),
            // Bookmarked locally, not pushed yet.
            entry(extBookmarked = true, extBookmarkedSynced = false),
            // Fresh and untouched.
            entry(),
        )
        db.entry.insertOrReplace(entries)

        val entry = db.readStats().tables.single { it.name == "entry" }

        assertEquals(4L, entry.rowCount)
        assertEquals(2L, entry.unreadCount)
        assertEquals(1L, entry.bookmarkedCount)
        assertEquals(2L, entry.pendingSyncCount)
    }

    @Test
    fun read_leavesEntryFlagsNullForTablesThatDoNotTrackThem() = runBlocking<Unit> {
        val tables = db.readStats().tables.associateBy { it.name }

        val feed = tables.getValue("feed")
        assertNull(feed.unreadCount)
        assertNull(feed.bookmarkedCount)
        assertNull(feed.pendingSyncCount)

        val conf = tables.getValue("conf")
        assertNull(conf.unreadCount)
        assertNull(conf.bookmarkedCount)
        assertNull(conf.pendingSyncCount)
    }

    @Test
    fun read_reportsAPositiveSizeOnceTheSchemaExists() = runBlocking<Unit> {
        // An on-disk database, because an in-memory one reports zero pages.
        val file = kotlin.io.path.createTempFile(suffix = ".db").toFile()
        try {
            val diskDb = Database(
                driver = androidx.sqlite.driver.bundled.BundledSQLiteDriver(),
                path = file.absolutePath,
            )
            diskDb.connect()
            try {
                assertTrue(diskDb.readStats().sizeBytes > 0)
            } finally {
                diskDb.close()
            }
        } finally {
            file.delete()
        }
    }

    private fun feed() = org.vestifeed.db.table.FeedTable.Feed(
        id = UUID.randomUUID().toString(),
        title = "Feed",
        extOpenEntriesInBrowser = null,
        extBlockedWords = "",
        extShowPreviewImages = null,
    )

    private fun entry(
        extRead: Boolean = false,
        extReadSynced: Boolean = true,
        extBookmarked: Boolean = false,
        extBookmarkedSynced: Boolean = true,
    ) = EntryTable.Entry(
        contentType = "",
        contentSrc = "",
        contentText = "",
        summary = "",
        id = UUID.randomUUID().toString(),
        feedId = "",
        title = "",
        published = Clock.System.now(),
        updated = Clock.System.now(),
        authorName = "",
        extRead = extRead,
        extReadSynced = extReadSynced,
        extBookmarked = extBookmarked,
        extBookmarkedSynced = extBookmarkedSynced,
        extCommentsUrl = "",
        extOpenGraphImageChecked = true,
        extOpenGraphImageUrl = "",
        extOpenGraphImageFetchedAt = null,
        extOpenGraphImageLog = "[]",
    )
}
