package org.vestifeed.backend

import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.entry
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.TagTable
import org.vestifeed.db.testDb
import org.vestifeed.util.toUrl

/**
 * The backend and sync layer: what a sync does to the local database, driven by
 * a [FakeMinifluxApi]. The HTTP side is covered by [MinifluxHttpApiTest] and the
 * DTO-to-row mapping by [MinifluxMappingTest].
 */
class MinifluxTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = testDb()
    }

    @Test
    fun syncFeedsStoresFeedsAndTheirLinks() = runBlocking {
        val api = object : FakeMinifluxApi() {
            override suspend fun getFeeds() = listOf(
                MinifluxFeed(
                    id = 1,
                    title = "feed 1",
                    feedUrl = "https://bubelov.com/index.xml",
                    siteUrl = "https://bubelov.com/",
                    categoryId = null,
                ),
            )

            override suspend fun getCategories() = emptyList<MinifluxCategory>()
            override suspend fun getUnreadEntries() = emptyList<MinifluxEntry>()
            override suspend fun getStarredEntries() = emptyList<MinifluxEntry>()
        }

        val backend = Miniflux(api, db)
        backend.sync(initial = true)

        val cached = db.feed.selectAll().single()
        assertEquals("1", cached.id)
        assertEquals("feed 1", cached.title)

        val links = db.link.selectByFeedId("1")
        assertEquals(2, links.size)
        assertNotNull(links.singleOrNull { it.href == "https://bubelov.com/index.xml" })
        assertNotNull(links.singleOrNull { it.href == "https://bubelov.com/" })

        // A second sync refreshes the same links instead of duplicating them.
        backend.sync(initial = true)
        assertEquals(2, db.link.selectByFeedId("1").size)
    }

    /**
     * The feed list used to be fetched twice per sync (once for the feeds, once
     * by the category reconcile). One request is enough: the reconcile reuses
     * what the feed sync already pulled.
     */
    @Test
    fun syncRequestsTheFeedListOnlyOnce() = runBlocking {
        var feedRequests = 0
        val api = object : FakeMinifluxApi() {
            override suspend fun getFeeds(): List<MinifluxFeed> {
                feedRequests++
                return listOf(
                    MinifluxFeed(
                        id = 1,
                        title = "feed 1",
                        feedUrl = "https://example.com/feed.xml",
                        siteUrl = "https://example.com/",
                        categoryId = 42L,
                    ),
                )
            }

            override suspend fun getCategories() =
                listOf(MinifluxCategory(id = 42L, title = "Tech"))

            override suspend fun getUnreadEntries() = emptyList<MinifluxEntry>()
            override suspend fun getStarredEntries() = emptyList<MinifluxEntry>()
        }

        Miniflux(api, db).sync(initial = true)

        assertEquals(1, feedRequests)
    }

    @Test
    fun syncCategories_createsTagsAndAssociatesFeeds() = runBlocking {
        val api = object : FakeMinifluxApi() {
            override suspend fun getFeeds() = listOf(
                MinifluxFeed(
                    id = 1,
                    title = "feed 1",
                    feedUrl = "https://example.com/feed.xml",
                    siteUrl = "https://example.com/",
                    categoryId = 42L,
                ),
            )

            override suspend fun getCategories() = listOf(
                MinifluxCategory(id = 42L, title = "Tech"),
                MinifluxCategory(id = 99L, title = "News"),
            )

            override suspend fun getUnreadEntries() = emptyList<MinifluxEntry>()
            override suspend fun getStarredEntries() = emptyList<MinifluxEntry>()
        }

        Miniflux(api, db).sync(initial = true)

        val tags = db.tag.selectAll()
        assertEquals(2, tags.size)
        val tech = tags.single { it.extMinifluxId == 42L }
        assertEquals("Tech", tech.name)
        assertEquals(TagTable.Source.Miniflux, tech.extSource)

        // The tag id is a runtime-generated UUID, so the association is checked
        // through the tag that points at category 42.
        assertEquals(listOf(tech.id), db.feedTag.selectTagIdsByFeedId("1"))
    }

    @Test
    fun syncCategories_removesDeletedCategories() = runBlocking {
        val freshFeed = MinifluxFeed(
            id = 1,
            title = "feed 1",
            feedUrl = "https://example.com/feed.xml",
            siteUrl = "https://example.com/",
            categoryId = 42L,
        )
        var currentCategories = listOf(
            MinifluxCategory(id = 42L, title = "Tech"),
            MinifluxCategory(id = 99L, title = "News"),
        )

        val api = object : FakeMinifluxApi() {
            override suspend fun getFeeds() = listOf(freshFeed)
            override suspend fun getCategories() = currentCategories
            override suspend fun getUnreadEntries() = emptyList<MinifluxEntry>()
            override suspend fun getStarredEntries() = emptyList<MinifluxEntry>()
        }
        val backend = Miniflux(api, db)

        backend.sync(initial = true)
        assertEquals(2, db.tag.selectAll().size)

        // The user removed category 99 on the server.
        currentCategories = listOf(MinifluxCategory(id = 42L, title = "Tech"))
        backend.sync(initial = true)

        assertEquals(1, db.tag.selectAll().size)
        assertEquals(42L, db.tag.selectAll().single().extMinifluxId)
    }

    /**
     * Incremental sync advances the cursor after storing changed entries, and
     * it does so by calling `conf.update` from inside the entries transaction.
     * That nested transaction is what used to fail with "cannot start a
     * transaction within a transaction" right after a user marked an entry
     * read (which triggers a background sync).
     */
    @Test
    fun syncIncrementalStoresChangedEntriesAndAdvancesCursor() = runBlocking {
        db.conf.insert(
            ConfTable.defaultConf().copy(
                backend = ConfTable.Backend.Miniflux,
                minifluxUrl = "http://localhost",
                minifluxToken = "token",
                minifluxIncrementalSyncTimestamp = "2024-01-01T00:00:00Z",
            ),
        )

        val api = object : FakeMinifluxApi() {
            override suspend fun getFeeds() = emptyList<MinifluxFeed>()
            override suspend fun getCategories() = emptyList<MinifluxCategory>()
            override suspend fun getEntriesChangedAfter(
                changedAfter: Instant,
                limit: Long,
            ) = listOf(
                minifluxEntry(
                    id = 42,
                    status = MinifluxEntryStatus.UNREAD,
                    changedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )
        }

        Miniflux(api, db).sync(initial = false)

        assertEquals("42", db.entry.selectById("42")?.id)
        assertEquals(
            "2024-01-02T00:00:00Z",
            db.conf.select().minifluxIncrementalSyncTimestamp,
        )
    }

    /**
     * A push sends only the entries whose local state has not reached the
     * server, with their absolute value — no feed or category re-fetch.
     */
    @Test
    fun pushPendingChangesSendsOnlyThePendingEntryUpdates() = runBlocking {
        db.entry.insertOrReplace(
            listOf(
                entry().copy(id = "42", extRead = true, extReadSynced = false),
                entry().copy(id = "43", extRead = true, extReadSynced = true),
            ),
        )

        val pushes = mutableListOf<Pair<List<String>, Boolean>>()
        val api = object : FakeMinifluxApi() {
            override suspend fun markEntriesAsRead(entryIds: List<String>, read: Boolean) {
                pushes += entryIds to read
            }
        }

        Miniflux(api, db).pushPendingChanges()

        assertEquals(listOf(listOf("42") to true), pushes)
        assertTrue(db.entry.selectByReadSynced(false).isEmpty())
    }

    @Test
    fun pruneDeletedEntries_removesLocalEntriesMissingOnServer() = runBlocking {
        db.entry.insertOrReplace(
            listOf(entry().copy(id = "1"), entry().copy(id = "2"), entry().copy(id = "3")),
        )

        var scans = 0
        val api = object : FakeMinifluxApi() {
            override suspend fun getEntryIds(): Set<Long> {
                scans++
                return setOf(1L, 2L)
            }
        }

        assertTrue(Miniflux(api, db).pruneDeletedEntries())

        assertNotNull(db.entry.selectById("1"))
        assertNotNull(db.entry.selectById("2"))
        assertNull(db.entry.selectById("3"))
        // Two scans are unioned so a page shift cannot hide a live entry.
        assertEquals(2, scans)
    }

    @Test
    fun pruneDeletedEntries_unsupportedServerIsANoOp() = runBlocking {
        db.entry.insertOrReplace(listOf(entry().copy(id = "1")))

        val api = object : FakeMinifluxApi() {
            // Miniflux < 2.3.2 has no /v1/entries/ids endpoint.
            override suspend fun getEntryIds(): Set<Long>? = null
        }

        // The attempt still counts as done, so the caller records it and does
        // not retry on every sync.
        assertTrue(Miniflux(api, db).pruneDeletedEntries())
        assertNotNull(db.entry.selectById("1"))
    }

    @Test
    fun deleteFeedReportsFailureWithoutThrowing() = runBlocking {
        val api = object : FakeMinifluxApi() {
            override suspend fun deleteFeed(feedId: String) = throw java.io.IOException("boom")
        }

        val result = Miniflux(api, db).deleteFeed("1")

        assertTrue(result.isFailure)
    }

    @Test
    fun addFeedStoresNothingAndReturnsTheMappedFeed() = runBlocking {
        val api = object : FakeMinifluxApi() {
            override suspend fun addFeed(feedUrl: String, categoryId: Long?) = MinifluxFeed(
                id = 7,
                title = "New feed",
                feedUrl = feedUrl,
                siteUrl = "https://example.com/",
                categoryId = categoryId,
            )
        }

        val result = Miniflux(api, db).addFeed("https://example.com/feed.xml".toUrl(), null)

        assertEquals("7", result.feed.id)
        assertEquals("New feed", result.feed.title)
        assertEquals(
            listOf("https://example.com/feed.xml", "https://example.com/"),
            result.feedLinks.map { it.href },
        )
        assertTrue("addFeed does not persist; the caller does", db.feed.selectAll().isEmpty())
    }
}

/** An entry DTO with everything the mapping needs, for backend-level tests. */
internal fun minifluxEntry(
    id: Long,
    status: MinifluxEntryStatus = MinifluxEntryStatus.UNREAD,
    publishedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    changedAt: Instant = publishedAt,
) = MinifluxEntry(
    id = id,
    feedId = 1,
    status = status,
    title = "entry $id",
    url = "",
    commentsUrl = "",
    publishedAt = publishedAt,
    changedAt = changedAt,
    content = "",
    author = "",
    starred = false,
    enclosures = emptyList(),
)
