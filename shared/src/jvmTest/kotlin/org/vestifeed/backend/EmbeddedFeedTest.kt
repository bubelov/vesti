package org.vestifeed.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.testDb
import org.vestifeed.parser.AtomFeed
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.parser.FeedResult
import org.vestifeed.parser.RssFeed
import org.vestifeed.parser.feed
import org.vestifeed.util.toUrl

class EmbeddedFeedTest {

    private lateinit var server: MockWebServer
    private lateinit var db: Database
    private lateinit var httpClient: HttpClient

    @Before
    fun before() {
        server = MockWebServer().apply { start() }
        db = testDb()
        httpClient = HttpClient(CIO)
    }

    @After
    fun after() {
        server.close()
    }

    @Test
    fun addsTwoFeedsFromSameDomainWithoutConflict() = runBlocking {
        val baseUrl = server.url("/").toString().toUrl()
        val feedOneUrl = URLBuilder(baseUrl).apply { appendPathSegments("one.xml") }.build()
        val feedTwoUrl = URLBuilder(baseUrl).apply { appendPathSegments("two.xml") }.build()

        val feedOneBody = rssText("example.com.one.rss.xml")
        val feedTwoBody = rssText("example.com.two.rss.xml")

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/rss+xml")
                .body(feedOneBody)
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/rss+xml")
                .body(feedTwoBody)
                .build(),
        )

        val api = Embedded(db = db, httpClient = httpClient)

        val resultOne = api.addFeed(feedOneUrl, null)
        val resultTwo = api.addFeed(feedTwoUrl, null)

        assertEquals(feedOneUrl.toString(), resultOne.feed.id)
        assertEquals("Example Feed One", resultOne.feed.title)
        assertEquals(feedTwoUrl.toString(), resultTwo.feed.id)
        assertEquals("Example Feed Two", resultTwo.feed.title)

        assertEquals(1, resultOne.entries.size)
        assertEquals("One item one", resultOne.entries.first().first.title)
        assertEquals(feedOneUrl.toString(), resultOne.entries.first().first.feedId)
        assertEquals(1, resultTwo.entries.size)
        assertEquals("Two item one", resultTwo.entries.first().first.title)
        assertEquals(feedTwoUrl.toString(), resultTwo.entries.first().first.feedId)

        db.transaction {
            db.feed.insertOrReplace(resultOne.feed)
            db.link.insertForFeed(resultOne.feed.id, resultOne.feedLinks)
            resultOne.entries.forEach { (entry, links) ->
                db.entry.insertOrReplace(listOf(entry))
                db.link.insertForEntry(entry.id, links)
            }

            db.feed.insertOrReplace(resultTwo.feed)
            db.link.insertForFeed(resultTwo.feed.id, resultTwo.feedLinks)
            resultTwo.entries.forEach { (entry, links) ->
                db.entry.insertOrReplace(listOf(entry))
                db.link.insertForEntry(entry.id, links)
            }
        }

        val feeds = db.feed.selectAll()
        assertEquals(2, feeds.size)
        val feedIds = feeds.map { it.id }.toSet()
        assertEquals(setOf(feedOneUrl.toString(), feedTwoUrl.toString()), feedIds)

        val feedTitles = feeds.map { it.title }.toSet()
        assertEquals(
            setOf("Example Feed One", "Example Feed Two"),
            feedTitles,
        )

        val linksOne = db.link.selectByFeedId(resultOne.feed.id)
        assertEquals(2, linksOne.size)
        assertNotNull(
            linksOne.singleOrNull {
                it.rel is AtomLinkRel.Self && it.href == feedOneUrl.toString()
            },
        )
        assertNotNull(
            linksOne.singleOrNull {
                it.rel is AtomLinkRel.Alternate && it.href == "https://example.com/one"
            },
        )

        val linksTwo = db.link.selectByFeedId(resultTwo.feed.id)
        assertEquals(2, linksTwo.size)
        assertNotNull(
            linksTwo.singleOrNull {
                it.rel is AtomLinkRel.Self && it.href == feedTwoUrl.toString()
            },
        )
        assertNotNull(
            linksTwo.singleOrNull {
                it.rel is AtomLinkRel.Alternate && it.href == "https://example.com/two"
            },
        )

        val entriesOne = db.entry.selectByFeedId(resultOne.feed.id)
        assertEquals(1, entriesOne.size)
        assertEquals("One item one", entriesOne.first().title)

        val entriesTwo = db.entry.selectByFeedId(resultTwo.feed.id)
        assertEquals(1, entriesTwo.size)
        assertEquals("Two item one", entriesTwo.first().title)

        val allEntries = db.entry.selectByQuery("item")
        assertEquals(2, allEntries.size)
        assertEquals(
            setOf("One item one", "Two item one"),
            allEntries.map { it.title }.toSet(),
        )
    }

    /**
     * Regression test for author parsing. The fixture is the live
     * https://www.space.com/feeds.xml feed, captured once and stored at
     * `app/src/test/resources/rss/space.com.feeds.rss.xml` so the test
     * stays deterministic and does not hit the network.
     *
     * Each `<author>` element in that feed sits inside a heavily-indented
     * line and is wrapped in `<![CDATA[ ... ]]>` with a leading and trailing
     * space, e.g.:
     *
     *     \t\t<author><![CDATA[ stingrayghost@gmail.com (Jeff Spry) ]]></author>\t
     *
     * If the parser hands `textContent` straight to the database, the stored
     * author name is surrounded by tabs, newlines and the CDATA-internal
     * spaces. The UI now displays the author verbatim, so we must trim it.
     */
    @Test
    fun spaceComFeedSavesAuthorsWithoutSurroundingWhitespace() = runBlocking {
        val url = URLBuilder(server.url("/").toString().toUrl()).apply {
            appendPathSegments("feeds.xml")
        }.build()
        val feedBody = rssText("space.com.feeds.rss.xml")

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/rss+xml")
                .body(feedBody)
                .build(),
        )

        val api = Embedded(db = db, httpClient = httpClient)
        val result = api.addFeed(url, null)

        db.transaction {
            db.feed.insertOrReplace(result.feed)
            db.link.insertForFeed(result.feed.id, result.feedLinks)
            result.entries.forEach { (entry, links) ->
                db.entry.insertOrReplace(listOf(entry))
                db.link.insertForEntry(entry.id, links)
            }
        }

        val stored = db.entry.selectByFeedId(result.feed.id)
        assertEquals(result.entries.size, stored.size)

        val authorsWithName = stored.filter { it.authorName.isNotBlank() }
        assertTrue(
            "Expected at least one entry with an author name, got ${stored.size} stored",
            authorsWithName.isNotEmpty(),
        )

        for (entry in authorsWithName) {
            assertEquals(
                "Expected trimmed authorName for '${entry.title}', got '${entry.authorName}'",
                entry.authorName.trim(),
                entry.authorName,
            )
        }

        val authors = authorsWithName.map { it.authorName }.toSet()
        assertTrue(
            "Expected 'stingrayghost@gmail.com (Jeff Spry)' in stored authors, got $authors",
            "stingrayghost@gmail.com (Jeff Spry)" in authors,
        )
        assertTrue(
            "Expected 'mwall@space.com (Mike Wall)' in stored authors, got $authors",
            "mwall@space.com (Mike Wall)" in authors,
        )
    }

    /**
     * RFC 4287 does not require a rel="self" link. Adding such a feed used to
     * throw (`single` on an empty list); the requested feed URL is the natural
     * identity and sync target.
     */
    @Test
    fun atomFeedWithoutSelfLinkUsesTheRequestedUrl() {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:feed</id>
              <title>No self link</title>
            </feed>
        """.trimIndent()
        val parsed = feed(body.toByteArray(), "application/atom+xml")
        assertTrue("Expected a parsed Atom feed, got $parsed", parsed is FeedResult.Success)
        val atom = (parsed as FeedResult.Success).feed as AtomFeed

        val (feedRow, links) = atom.toFeed("https://example.com/feed.xml".toUrl())

        assertEquals("https://example.com/feed.xml", feedRow.id)
        val self = links.single { it.rel is AtomLinkRel.Self }
        assertEquals("https://example.com/feed.xml", self.href)
        assertEquals(feedRow.id, self.feedId)
    }

    /**
     * The channel's <link> is the site, not the feed document. Two feeds from
     * one site share it, so the feed URL is what identifies the subscription.
     */
    @Test
    fun rssFeedUsesTheRequestedFeedUrlAsItsIdentity() {
        val parsed = feed(rssText("example.com.one.rss.xml").toByteArray(), "application/rss+xml")
        assertTrue("Expected a parsed RSS feed, got $parsed", parsed is FeedResult.Success)
        val rss = (parsed as FeedResult.Success).feed as RssFeed
        val feedUrl = "https://mirror.example.com/feed.xml".toUrl()

        val (feedRow, links) = rss.toFeed(feedUrl)

        assertEquals(feedUrl.toString(), feedRow.id)
        assertEquals(feedUrl.toString(), links.single { it.rel is AtomLinkRel.Self }.href)
        assertEquals(
            "https://example.com/one",
            links.single { it.rel is AtomLinkRel.Alternate }.href,
        )
    }

    /**
     * Loads a feed fixture from the test classpath, falling back to the app
     * module's source tree for the in-repo layout (mirrors the parser's
     * `FeedTest`).
     */
    private fun rssText(name: String): String {
        val path = "/rss/$name"
        javaClass.getResourceAsStream(path)?.use { return it.readBytes().decodeToString() }

        val candidates = listOf(
            File("src/jvmTest/resources$path"),
            File("../app/src/test/resources$path"),
            File("app/src/test/resources$path"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw IllegalStateException("Test fixture not found: $path")
    }
}
