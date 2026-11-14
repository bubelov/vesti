package org.vestifeed.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.util.toUrl

/**
 * The HTTP half: paths, methods, query parameters, request bodies and the JSON
 * that comes back. Nothing here touches a database.
 */
class MinifluxHttpApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun before() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun after() {
        server.close()
    }

    /** The API rooted at the mock server, with the plain (unauthenticated) client. */
    private fun api(client: HttpClient = HttpClient(CIO)) =
        MinifluxHttpApi(client = client, baseUrl = server.url("/v1/").toString().toUrl())

    // ---- Feeds -------------------------------------------------------------

    @Test
    fun addFeedSendsCategoryIdWhenProvided() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body("""{"feed_id": 42}""")
                .build(),
        )
        server.enqueue(jsonResponse(feedJson(id = 42)))

        api().addFeed("https://example.com/feed.xml", categoryId = 7L)

        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/v1/feeds", createRequest.target)
        val body = createRequest.body?.utf8().orEmpty()
        assertTrue(
            "Expected request body to include category_id, got: $body",
            body.contains("\"category_id\":7"),
        )
        assertTrue(
            "Expected request body to include feed_url, got: $body",
            body.contains("\"feed_url\":\"https://example.com/feed.xml\""),
        )
    }

    @Test
    fun addFeedOmitsCategoryIdWhenNull() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body("""{"feed_id": 42}""")
                .build(),
        )
        server.enqueue(jsonResponse(feedJson(id = 42)))

        api().addFeed("https://example.com/feed.xml", categoryId = null)

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertFalse(
            "Expected request body to omit category_id when null, got: $body",
            body.contains("category_id"),
        )
        assertTrue(
            "Expected request body to include feed_url, got: $body",
            body.contains("\"feed_url\":\"https://example.com/feed.xml\""),
        )
    }

    /** The create endpoint answers with an ID only, so the feed is read back. */
    @Test
    fun addFeedReturnsTheFeedTheServerCreated() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body("""{"feed_id": 42}""")
                .build(),
        )
        server.enqueue(jsonResponse(feedJson(id = 42)))

        val feed = api().addFeed("https://example.com/feed.xml", categoryId = null)

        assertEquals(42L, feed.id)
        assertEquals("Test Feed", feed.title)
        assertEquals("https://example.com/feed.xml", feed.feedUrl)
        assertEquals("https://example.com/", feed.siteUrl)

        server.takeRequest()
        val readBack = server.takeRequest()
        assertEquals("GET", readBack.method)
        assertEquals("/v1/feeds/42", readBack.target)
    }

    @Test
    fun getFeedsReadsCategoryAndUrls() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                [
                  {
                    "id": 1,
                    "title": "Tech feed",
                    "feed_url": "https://example.com/feed.xml",
                    "site_url": "https://example.com/",
                    "category": {"id": 9, "title": "Tech"}
                  }
                ]
                """.trimIndent(),
            ),
        )

        val feed = api().getFeeds().single()

        assertEquals(1L, feed.id)
        assertEquals("Tech feed", feed.title)
        assertEquals("https://example.com/feed.xml", feed.feedUrl)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals(9L, feed.categoryId)
    }

    @Test
    fun deleteFeedExpectsNoContent() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        api().deleteFeed("42")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/feeds/42", request.target)
    }

    // ---- Entries -----------------------------------------------------------

    @Test
    fun getUnreadEntriesPaginatesWhenTotalExceedsPageSize() = runBlocking {
        // Three full pages and a short one; the short page ends the walk.
        server.enqueue(jsonResponse(unreadPayload(total = 3439L, ids = (1L..1000L).toList())))
        server.enqueue(jsonResponse(unreadPayload(total = 3439L, ids = (1001L..2000L).toList())))
        server.enqueue(jsonResponse(unreadPayload(total = 3439L, ids = (2001L..3000L).toList())))
        server.enqueue(jsonResponse(unreadPayload(total = 3439L, ids = (3001L..3439L).toList())))

        val entries = api().getUnreadEntries()

        assertEquals(3439, entries.size)
        assertEquals(1L, entries.first().id)
        assertEquals(3439L, entries.last().id)
        assertEquals(4, server.requestCount)

        listOf(0, 1000, 2000, 3000).forEach { offset ->
            val target = server.takeRequest().target
            assertTrue(
                "Expected offset=$offset limit=1000, got: $target",
                target.contains("offset=$offset") && target.contains("limit=1000"),
            )
        }
    }

    /**
     * The unfiltered listing also returns `removed` entries, so the wire status
     * has to survive parsing rather than collapse into a boolean.
     */
    @Test
    fun getEntriesChangedAfterParsesEveryStatus() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "total": 3,
                  "entries": [
                    ${entryJson(id = 1, status = "unread")},
                    ${entryJson(id = 2, status = "read")},
                    ${entryJson(id = 3, status = "removed")}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val entries = api().getEntriesChangedAfter(
            changedAfter = Instant.parse("2024-01-01T00:00:00Z"),
            limit = 100,
        )

        assertEquals(
            listOf(
                MinifluxEntryStatus.UNREAD,
                MinifluxEntryStatus.READ,
                MinifluxEntryStatus.REMOVED,
            ),
            entries.map { it.status },
        )
    }

    @Test
    fun getEntriesChangedAfterAsksForTheChangeLogOrder() = runBlocking {
        server.enqueue(jsonResponse("""{"total": 0, "entries": []}"""))

        api().getEntriesChangedAfter(
            changedAfter = Instant.parse("2024-01-01T00:00:00Z"),
            limit = 100,
        )

        val target = server.takeRequest().target
        assertTrue("got: $target", target.contains("changed_after=${Instant.parse("2024-01-01T00:00:00Z").epochSeconds}"))
        assertTrue("got: $target", target.contains("order=changed_at"))
        assertTrue("got: $target", target.contains("direction=asc"))
        assertTrue("got: $target", target.contains("limit=100"))
    }

    @Test
    fun getEntryIdsAsksForLargePagesOfIds() = runBlocking {
        server.enqueue(jsonResponse("""{"total": 2, "entry_ids": [15548, 15540]}"""))

        val ids = api().getEntryIds()

        assertEquals(setOf(15548L, 15540L), ids)
        val target = server.takeRequest().target
        assertEquals("/v1/entries/ids?limit=10000&offset=0", target)
    }

    /**
     * The production client turns a 404 into [MinifluxHttpException] before the
     * caller sees the response, so `getEntryIds` has to catch it for servers
     * without the endpoint (Miniflux < v2.3.2).
     */
    @Test
    fun getEntryIdsReturnsNullWhenTheServerHasNoIdEndpoint() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())

        val ids = api(client = minifluxHttpClient(token = "token")).getEntryIds()

        assertNull(ids)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun markEntriesAsReadSendsTheStatusInOneRequest() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        api().markEntriesAsRead(listOf("42", "43"), read = true)

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/entries", request.target)
        val body = request.body?.utf8().orEmpty()
        assertTrue("expected the read status, got: $body", body.contains("\"status\":\"read\""))
        assertTrue("expected entry 42, got: $body", body.contains("42"))
        assertTrue("expected entry 43, got: $body", body.contains("43"))
    }

    @Test
    fun markEntriesAsBookmarkedSendsTheAbsoluteStateInOneRequest() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        api().markEntriesAsBookmarked(listOf("42", "43"), bookmarked = true)

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/entries", request.target)
        val body = request.body?.utf8().orEmpty()
        assertTrue("expected an absolute starred state, got: $body", body.contains("\"starred\":true"))
        assertTrue("expected entry 42, got: $body", body.contains("42"))
        assertTrue("expected entry 43, got: $body", body.contains("43"))
    }

    @Test
    fun markEntriesAsBookmarkedSendsFalseWhenUnbookmarking() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        api().markEntriesAsBookmarked(listOf("42"), bookmarked = false)

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue("expected an absolute unstarred state, got: $body", body.contains("\"starred\":false"))
    }

    @Test
    fun markingNoEntriesMakesNoRequest() = runBlocking {
        api().markEntriesAsBookmarked(emptyList(), bookmarked = true)

        assertEquals(0, server.requestCount)
    }

    /**
     * Miniflux < 2.3.2 rejects the `starred` field carried by a bulk update (it
     * validates that a `status` is present). The fallback toggles only the
     * entries whose server state actually differs, so a retried push cannot
     * flip back an entry an earlier attempt already updated.
     */
    @Test
    fun markEntriesAsBookmarkedFallsBackToTogglingOnOldServers() = runBlocking {
        // The bulk update is rejected by the old server.
        server.enqueue(MockResponse.Builder().code(400).build())
        // Entry 42 is not starred yet: read it, then toggle it.
        server.enqueue(jsonResponse("""{"id":42,"starred":false}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        // Entry 43 is already starred: it must not be toggled.
        server.enqueue(jsonResponse("""{"id":43,"starred":true}"""))

        api(client = minifluxHttpClient(token = "token"))
            .markEntriesAsBookmarked(listOf("42", "43"), bookmarked = true)

        assertEquals(4, server.requestCount)

        val bulk = server.takeRequest()
        assertEquals("PUT", bulk.method)
        assertEquals("/v1/entries", bulk.target)

        val read42 = server.takeRequest()
        assertEquals("GET", read42.method)
        assertEquals("/v1/entries/42", read42.target)

        val toggle42 = server.takeRequest()
        assertEquals("PUT", toggle42.method)
        assertEquals("/v1/entries/42/bookmark", toggle42.target)

        val read43 = server.takeRequest()
        assertEquals("GET", read43.method)
        assertEquals("/v1/entries/43", read43.target)
    }

    /**
     * A legacy-server push is retried wholesale after a partial failure (the
     * local flags are only cleared once the whole loop succeeds). The retry must
     * not toggle again an entry the first attempt already updated, or the
     * bookmark flips back off on the server.
     */
    @Test
    fun markEntriesAsBookmarkedFallbackRetryDoesNotToggleTwice() = runBlocking {
        // Attempt 1: bulk update rejected, entry 42 toggled, entry 43 fails,
        // so the push as a whole throws and stays pending.
        server.enqueue(MockResponse.Builder().code(400).build())
        server.enqueue(jsonResponse("""{"id":42,"starred":false}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(500).build())

        // Attempt 2: entry 42 now reports the desired state and must be left
        // alone; entry 43 still needs its toggle.
        server.enqueue(MockResponse.Builder().code(400).build())
        server.enqueue(jsonResponse("""{"id":42,"starred":true}"""))
        server.enqueue(jsonResponse("""{"id":43,"starred":false}"""))
        server.enqueue(MockResponse.Builder().code(204).build())

        val api = api(client = minifluxHttpClient(token = "token"))

        assertThrows(IOException::class.java) {
            runBlocking { api.markEntriesAsBookmarked(listOf("42", "43"), bookmarked = true) }
        }
        api.markEntriesAsBookmarked(listOf("42", "43"), bookmarked = true)

        val requests = (1..8).map { server.takeRequest() }
        val toggles = requests.filter { it.target.endsWith("/bookmark") }

        assertEquals(
            "only one toggle per entry across both attempts",
            listOf("/v1/entries/42/bookmark", "/v1/entries/43/bookmark"),
            toggles.map { it.target },
        )
    }

    // ---- Categories --------------------------------------------------------

    @Test
    fun getCategoriesReadsIdsAndTitles() = runBlocking {
        server.enqueue(jsonResponse("""[{"id": 42, "title": "Tech"}]"""))

        val category = api().getCategories().single()

        assertEquals(42L, category.id)
        assertEquals("Tech", category.title)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun jsonResponse(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun feedJson(id: Long) = """
        {
          "id": $id,
          "title": "Test Feed",
          "feed_url": "https://example.com/feed.xml",
          "site_url": "https://example.com/",
          "category": null
        }
    """.trimIndent()

    private fun entryJson(id: Long, status: String) = """
        {
          "id": $id,
          "feed_id": 1,
          "status": "$status",
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

    private fun unreadPayload(total: Long, ids: List<Long>): String {
        val entries = ids.joinToString(",") { entryJson(id = it, status = "unread") }
        return """{"total": $total, "entries": [$entries]}"""
    }
}
