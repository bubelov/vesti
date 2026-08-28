package org.vestifeed.backend

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomLinkRel
import java.time.OffsetDateTime

class MinifluxTest {
    private lateinit var db: Database

    @Before
    fun before() {
        db = Database(BundledSQLiteDriver(), ":memory:")
    }

    @Test
    fun syncFeeds() {
        val freshFeed = FeedTable.Feed(
            id = "1",
            title = "feed 1",
            extOpenEntriesInBrowser = false,
            extBlockedWords = "",
            extShowPreviewImages = null,
        )
        val freshLinks = listOf(
            LinkTable.Link(
                id = null,
                feedId = "1",
                entryId = null,
                href = "https://bubelov.com/index.xml",
                rel = AtomLinkRel.Self,
                type = null,
                hreflang = null,
                title = null,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            ),
            LinkTable.Link(
                id = null,
                feedId = "1",
                entryId = null,
                href = "https://bubelov.com/",
                rel = AtomLinkRel.Alternate,
                type = "text/html",
                hreflang = null,
                title = null,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            ),
        )
        val api = object : Miniflux(
            client = OkHttpClient(),
            baseUrl = "http://localhost".toHttpUrl(),
            db = db,
        ) {
            override suspend fun addFeed(url: okhttp3.HttpUrl, categoryId: Long?): Backend.AddFeedResult =
                throw NotImplementedError()

            override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun deleteFeed(feedId: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun getFeedsWithLinks(): List<Miniflux.FreshFeed> =
                listOf(Miniflux.FreshFeed(feed = freshFeed, links = freshLinks, categoryId = null))

            override suspend fun getUnreadEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> =
                emptyList()

            override suspend fun getStarredEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> =
                emptyList()

            override suspend fun getEntriesChangedAfter(
                changedAfter: OffsetDateTime,
                limit: Long,
            ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> = emptyList()

            override suspend fun markEntriesAsRead(entriesIds: List<String>, read: Boolean) = Unit

            override suspend fun markEntriesAsBookmarked(
                entries: List<EntryTable.EntryWithoutContent>,
                bookmarked: Boolean,
            ) = Unit

            override suspend fun getCategories(): List<Miniflux.MinifluxCategory> =
                emptyList()

            override suspend fun createCategory(title: String): Miniflux.MinifluxCategory =
                throw NotImplementedError()

            override suspend fun updateCategory(id: Long, title: String): Miniflux.MinifluxCategory =
                throw NotImplementedError()

            override suspend fun deleteCategory(id: Long): Result<Unit> =
                Result.success(Unit)

            override suspend fun moveFeedToCategory(feedId: String, categoryId: Long) = Unit
        }
        runBlocking { api.sync(initial = true) }
        val cacheFeeds = db.feed.selectAll()
        val cacheFeed1 = cacheFeeds.first()
        assert(cacheFeed1.id == freshFeed.id)
        assert(cacheFeed1.title == freshFeed.title)
        val cacheFeed1Links = db.link.selectByFeedId(freshFeed.id)
        assert(cacheFeed1Links.size == 2)
        assert(cacheFeed1Links.singleOrNull { it.href == freshLinks[0].href } != null)
        assert(cacheFeed1Links.singleOrNull { it.href == freshLinks[1].href } != null)
        runBlocking { api.sync(initial = true) }
        val cacheFeed1Links2 = db.link.selectByFeedId(freshFeed.id)
        assert(cacheFeed1Links2.size == 2)
        assert(cacheFeed1Links2.singleOrNull { it.href == freshLinks[0].href } != null)
        assert(cacheFeed1Links2.singleOrNull { it.href == freshLinks[1].href } != null)
    }

    @Test
    fun syncCategories_createsTagsAndAssociatesFeeds() {
        val freshFeed = FeedTable.Feed(
            id = "1",
            title = "feed 1",
            extOpenEntriesInBrowser = false,
            extBlockedWords = "",
            extShowPreviewImages = null,
        )
        val freshLinks = emptyList<LinkTable.Link>()
        val api = object : Miniflux(
            client = OkHttpClient(),
            baseUrl = "http://localhost".toHttpUrl(),
            db = db,
        ) {
            override suspend fun addFeed(url: okhttp3.HttpUrl, categoryId: Long?): Backend.AddFeedResult =
                throw NotImplementedError()

            override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun deleteFeed(feedId: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun getFeedsWithLinks(): List<Miniflux.FreshFeed> =
                listOf(Miniflux.FreshFeed(feed = freshFeed, links = freshLinks, categoryId = 42L))

            override suspend fun getUnreadEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> =
                emptyList()

            override suspend fun getStarredEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> =
                emptyList()

            override suspend fun getEntriesChangedAfter(
                changedAfter: OffsetDateTime,
                limit: Long,
            ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> = emptyList()

            override suspend fun markEntriesAsRead(entriesIds: List<String>, read: Boolean) = Unit

            override suspend fun markEntriesAsBookmarked(
                entries: List<EntryTable.EntryWithoutContent>,
                bookmarked: Boolean,
            ) = Unit

            override suspend fun getCategories(): List<Miniflux.MinifluxCategory> =
                listOf(
                    Miniflux.MinifluxCategory(id = 42L, title = "Tech"),
                    Miniflux.MinifluxCategory(id = 99L, title = "News"),
                )

            override suspend fun createCategory(title: String): Miniflux.MinifluxCategory =
                throw NotImplementedError()

            override suspend fun updateCategory(id: Long, title: String): Miniflux.MinifluxCategory =
                throw NotImplementedError()

            override suspend fun deleteCategory(id: Long): Result<Unit> =
                Result.success(Unit)

            override suspend fun moveFeedToCategory(feedId: String, categoryId: Long) = Unit
        }

        runBlocking { api.sync(initial = true) }

        val tags = db.tag.selectAll()
        assertEquals(2, tags.size)
        val tech = tags.single { it.extMinifluxId == 42L }
        assertEquals("Tech", tech.name)
        assertEquals(org.vestifeed.db.table.TagTable.Source.Miniflux, tech.extSource)

        // The tag id is a runtime-generated UUID. Verify the association by
        // looking up the tag that points at category 42 and checking the
        // feed_tag rows match.
        val tagIds = db.feedTag.selectTagIdsByFeedId("1")
        assertEquals(1, tagIds.size)
        assertEquals(tech.id, tagIds.single())
    }

    @Test
    fun syncCategories_removesDeletedCategories() {
        val freshFeed = FeedTable.Feed(
            id = "1",
            title = "feed 1",
            extOpenEntriesInBrowser = false,
            extBlockedWords = "",
            extShowPreviewImages = null,
        )
        val remoteCategoriesFirstRun = listOf(
            Miniflux.MinifluxCategory(id = 42L, title = "Tech"),
            Miniflux.MinifluxCategory(id = 99L, title = "News"),
        )
        val remoteCategoriesSecondRun = listOf(
            Miniflux.MinifluxCategory(id = 42L, title = "Tech"),
        )

        var currentCategories = remoteCategoriesFirstRun
        val api = object : Miniflux(
            client = OkHttpClient(),
            baseUrl = "http://localhost".toHttpUrl(),
            db = db,
        ) {
            override suspend fun addFeed(url: okhttp3.HttpUrl, categoryId: Long?): Backend.AddFeedResult =
                throw NotImplementedError()

            override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun deleteFeed(feedId: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun getFeedsWithLinks(): List<Miniflux.FreshFeed> =
                listOf(Miniflux.FreshFeed(feed = freshFeed, links = emptyList(), categoryId = 42L))

            override suspend fun getUnreadEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> =
                emptyList()

            override suspend fun getStarredEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> =
                emptyList()

            override suspend fun getEntriesChangedAfter(
                changedAfter: OffsetDateTime,
                limit: Long,
            ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> = emptyList()

            override suspend fun markEntriesAsRead(entriesIds: List<String>, read: Boolean) = Unit

            override suspend fun markEntriesAsBookmarked(
                entries: List<EntryTable.EntryWithoutContent>,
                bookmarked: Boolean,
            ) = Unit

            override suspend fun getCategories(): List<Miniflux.MinifluxCategory> = currentCategories

            override suspend fun createCategory(title: String): Miniflux.MinifluxCategory =
                throw NotImplementedError()

            override suspend fun updateCategory(id: Long, title: String): Miniflux.MinifluxCategory =
                throw NotImplementedError()

            override suspend fun deleteCategory(id: Long): Result<Unit> =
                Result.success(Unit)

            override suspend fun moveFeedToCategory(feedId: String, categoryId: Long) = Unit
        }

        runBlocking {
            api.sync(initial = true)
        }
        assertEquals(2, db.tag.selectAll().size)

        // Pretend the user removed category 99 on the server.
        currentCategories = remoteCategoriesSecondRun
        runBlocking {
            api.sync(initial = true)
        }
        assertEquals(1, db.tag.selectAll().size)
        assertEquals(42L, db.tag.selectAll().single().extMinifluxId)
    }

    @Test
    fun addFeedSendsCategoryIdWhenProvided() {
        val server = MockWebServer().apply { start() }
        try {
            // POST /v1/feeds -> 201 with feed_id
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"feed_id": 42}""")
            )
            // GET /v1/feeds/42 -> 200 with the feed metadata
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": 42,
                          "title": "Test Feed",
                          "feed_url": "https://example.com/feed.xml",
                          "site_url": "https://example.com/",
                          "category": null
                        }
                        """.trimIndent()
                    )
            )

            val api = Miniflux(
                client = OkHttpClient(),
                baseUrl = server.url("/v1/"),
                db = db,
            )

            runBlocking { api.addFeed("https://example.com/feed.xml".toHttpUrl(), categoryId = 7L) }

            val createRequest = server.takeRequest()
            assertEquals("POST", createRequest.method)
            assertEquals("/v1/feeds", createRequest.path)
            val body = createRequest.body.readUtf8()
            assertTrue(
                "Expected request body to include category_id, got: $body",
                body.contains("\"category_id\":7"),
            )
            assertTrue(
                "Expected request body to include feed_url, got: $body",
                body.contains("\"feed_url\":\"https://example.com/feed.xml\""),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun addFeedOmitsCategoryIdWhenNull() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"feed_id": 42}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": 42,
                          "title": "Test Feed",
                          "feed_url": "https://example.com/feed.xml",
                          "site_url": "https://example.com/",
                          "category": null
                        }
                        """.trimIndent()
                    )
            )

            val api = Miniflux(
                client = OkHttpClient(),
                baseUrl = server.url("/v1/"),
                db = db,
            )

            runBlocking { api.addFeed("https://example.com/feed.xml".toHttpUrl(), categoryId = null) }

            val createRequest = server.takeRequest()
            assertEquals("POST", createRequest.method)
            assertEquals("/v1/feeds", createRequest.path)
            val body = createRequest.body.readUtf8()
            assertFalse(
                "Expected request body to omit category_id when null, got: $body",
                body.contains("category_id"),
            )
            assertTrue(
                "Expected request body to include feed_url, got: $body",
                body.contains("\"feed_url\":\"https://example.com/feed.xml\""),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun findOrCreateCategoryReturnsExistingWhenPresent() {
        val server = MockWebServer().apply { start() }
        try {
            // GET /v1/categories -> 200 with one matching category
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        [
                          {"id": 42, "title": "Tech"},
                          {"id": 99, "title": "OPML Import 2026-08-18"}
                        ]
                        """.trimIndent()
                    )
            )
            // createCategory should NOT be called. If it is, MockWebServer will
            // fail the test when it has no more queued responses.

            val api = Miniflux(
                client = OkHttpClient(),
                baseUrl = server.url("/v1/"),
                db = db,
            )

            val result = runBlocking { api.findOrCreateCategory("OPML Import 2026-08-18") }

            assertEquals(Miniflux.MinifluxCategory(id = 99L, title = "OPML Import 2026-08-18"), result)
            assertEquals(1, server.requestCount)
            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertEquals("/v1/categories", listRequest.path)
        } finally {
            server.shutdown()
        }
    }

@Test
    fun findOrCreateCategoryCreatesWhenAbsent() {
        val server = MockWebServer().apply { start() }
        try {
            // GET /v1/categories -> 200 with no matching category
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""[{"id": 42, "title": "Tech"}]""")
            )
            // POST /v1/categories -> 201 with the new category
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id": 7, "title": "OPML Import 2026-08-18"}""")
            )

            val api = Miniflux(
                client = OkHttpClient(),
                baseUrl = server.url("/v1/"),
                db = db,
            )

            val result = runBlocking { api.findOrCreateCategory("OPML Import 2026-08-18") }

            assertEquals(Miniflux.MinifluxCategory(id = 7L, title = "OPML Import 2026-08-18"), result)
            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertEquals("/v1/categories", listRequest.path)
            val createRequest = server.takeRequest()
            assertEquals("POST", createRequest.method)
            assertEquals("/v1/categories", createRequest.path)
            val body = createRequest.body.readUtf8()
            assertTrue(
                "Expected POST body to include the requested title, got: $body",
                body.contains("\"title\":\"OPML Import 2026-08-18\""),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getUnreadEntriesPaginatesWhenTotalExceedsPageSize() {
        val server = MockWebServer().apply { start() }
        try {
            // First page: 1000 entries, total reported as 3439.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(unreadPayload(total = 3439L, ids = (1L..1000L).toList()))
            )
            // Second page: 1000 entries.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(unreadPayload(total = 3439L, ids = (1001L..2000L).toList()))
            )
            // Third page: 1000 entries.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(unreadPayload(total = 3439L, ids = (2001L..3000L).toList()))
            )
            // Final page: 439 entries — short page tells us we are done.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(unreadPayload(total = 3439L, ids = (3001L..3439L).toList()))
            )

            val api = Miniflux(
                client = OkHttpClient(),
                baseUrl = server.url("/v1/"),
                db = db,
            )

            val entries = runBlocking { api.getUnreadEntries() }

            assertEquals(3439, entries.size)
            assertEquals("1", entries.first().first.id)
            assertEquals("3439", entries.last().first.id)
            assertEquals(4, server.requestCount)

            val firstPath = server.takeRequest().path!!
            assertTrue(
                "Expected first page to ask for offset=0 limit=1000, got: $firstPath",
                firstPath.contains("offset=0") && firstPath.contains("limit=1000"),
            )
            val secondPath = server.takeRequest().path!!
            assertTrue(
                "Expected second page to ask for offset=1000 limit=1000, got: $secondPath",
                secondPath.contains("offset=1000") && secondPath.contains("limit=1000"),
            )
            val thirdPath = server.takeRequest().path!!
            assertTrue(
                "Expected third page to ask for offset=2000 limit=1000, got: $thirdPath",
                thirdPath.contains("offset=2000") && thirdPath.contains("limit=1000"),
            )
            val fourthPath = server.takeRequest().path!!
            assertTrue(
                "Expected fourth page to ask for offset=3000 limit=1000, got: $fourthPath",
                fourthPath.contains("offset=3000") && fourthPath.contains("limit=1000"),
            )
        } finally {
            server.shutdown()
        }
    }

    private fun unreadPayload(total: Long, ids: List<Long>): String {
        val entries = ids.joinToString(",") { id ->
            """
            {
              "id": $id,
              "feed_id": 1,
              "status": "unread",
              "title": "entry $id",
              "url": "",
              "comments_url": "",
              "published_at": "2024-01-01T00:00:00Z",
              "created_at": "2024-01-01T00:00:00Z",
              "changed_at": "2024-01-01T00:00:00Z",
              "content": "",
              "author": "",
              "starred": false,
              "enclosures": null
            }
            """.trimIndent()
        }
        return """{"total": $total, "entries": [$entries]}"""
    }
}
