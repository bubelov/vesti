package org.vestifeed.opml

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Url
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.vestifeed.backend.Embedded
import org.vestifeed.db.Database
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.db.testDb
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.util.toInstant
import org.vestifeed.util.toUrl

class OpmlTest {

    private val sampleElements = listOf(
        OpmlOutline(
            text = "WirelessMoves",
            outlines = emptyList(),
            xmlUrl = "https://blog.wirelessmoves.com/feed",
            htmlUrl = "https://blog.wirelessmoves.com/",
            extOpenEntriesInBrowser = true,
            extShowPreviewImages = false,
            extBlockedWords = "abc",
        ),
        OpmlOutline(
            text = "Nextcloud",
            outlines = emptyList(),
            xmlUrl = "https://nextcloud.com/blogfeed",
            htmlUrl = "https://nextcloud.com/",
            extOpenEntriesInBrowser = false,
            extBlockedWords = "",
            extShowPreviewImages = true,
        ),
        OpmlOutline(
            text = "PINE64",
            outlines = emptyList(),
            xmlUrl = "https://www.pine64.org/feed/",
            htmlUrl = "https://www.pine64.org/",
            extOpenEntriesInBrowser = true,
            extBlockedWords = "xyz",
            extShowPreviewImages = null,
        ),
    )

    @Test
    fun readsSampleDocument() {
        val doc = readFile("sample.opml").toOpml()
        assertArrayEquals(sampleElements.toTypedArray(), doc.outlines.toTypedArray())
    }

    @Test
    fun writesSampleDocument() {
        val feeds = sampleElements.map {
            val feedId = UUID.randomUUID().toString()

            val selfLink = LinkTable.Link(
                id = null,
                feedId = feedId,
                entryId = null,
                href = it.xmlUrl!!,
                rel = AtomLinkRel.Self,
                type = null,
                hreflang = null,
                title = it.text,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            )

            val alternateLink = LinkTable.Link(
                id = null,
                feedId = feedId,
                entryId = null,
                href = it.htmlUrl!!,
                rel = AtomLinkRel.Alternate,
                type = "text/html",
                hreflang = null,
                title = it.text,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            )

            val feed = FeedTable.Feed(
                id = UUID.randomUUID().toString(),
                title = it.text,
                extOpenEntriesInBrowser = it.extOpenEntriesInBrowser!!,
                extBlockedWords = it.extBlockedWords!!,
                extShowPreviewImages = it.extShowPreviewImages,
            )

            Triple(feed, selfLink, alternateLink)
        }

        val outlines = feeds.map { (feed, selfLink, alternateLink) ->
            OpmlOutline(
                text = feed.title,
                outlines = emptyList(),
                xmlUrl = selfLink.href,
                htmlUrl = alternateLink.href,
                extOpenEntriesInBrowser = feed.extOpenEntriesInBrowser,
                extBlockedWords = feed.extBlockedWords,
                extShowPreviewImages = feed.extShowPreviewImages,
            )
        }

        var opmlDocument = OpmlDocument(
            version = OpmlVersion.V_2_0,
            outlines = outlines,
        )

        assertTrue(opmlDocument.toXml().lines().size > 1)

        opmlDocument = opmlDocument.toXml().toOpml()

        assertArrayEquals(sampleElements.toTypedArray(), opmlDocument.outlines.toTypedArray())
    }

    @Test
    fun readNestedOpml() {
        val document = readFile("nested.opml").toOpml()
        assertEquals(6, document.leafOutlines().size)
    }

    @Test
    fun readsMozillaOpml() {
        val document = readFile("mozilla.opml").toOpml()
        assertEquals(2, document.outlines.size)
    }

    @Test
    fun readsFeederOpml() = runBlocking {
        val document = readFile("feeder.opml").toOpml()

        assertEquals(OpmlVersion.V_1_1, document.version)
        assertEquals(4, document.outlines.size)
        assertEquals(4, document.leafOutlines().size)

        val expected = listOf(
            "Free software jobs" to "https://static.fsf.org/fsforg/rss/jobs.xml",
            "FSF News" to "https://static.fsf.org/fsforg/rss/news.xml",
            "fossjobs.net" to "https://www.fossjobs.net/rss/all/",
            "L'Agenda du Libre" to "https://www.agendadulibre.org/events.rss",
        )

        val parsed = document.leafOutlines().map { it.text to it.xmlUrl }
        assertEquals(expected, parsed)

        document.leafOutlines().forEach { outline ->
            assertEquals("", outline.htmlUrl)
            assertEquals(false, outline.extOpenEntriesInBrowser)
            assertNull(outline.extShowPreviewImages)
            assertEquals("", outline.extBlockedWords)
        }

        importsAllFourFeeds(document)
    }

    private suspend fun importsAllFourFeeds(document: OpmlDocument) {
        val server = MockWebServer().apply { start() }
        val db = testDb()
        val client = HttpClient(CIO)
        val api = Embedded(db = db, httpClient = client)

        try {
            val fixtures = mapOf(
                "https://static.fsf.org/fsforg/rss/jobs.xml" to "fsf.jobs.rdf.xml",
                "https://static.fsf.org/fsforg/rss/news.xml" to "fsf.news.rdf.xml",
                "https://www.fossjobs.net/rss/all/" to "fossjobs.net.rss.xml",
                "https://www.agendadulibre.org/events.rss" to "agendadulibre.events.rdf.xml",
            )

            val mockUrlByOriginal = mutableMapOf<String, Url>()

            for ((originalUrl, fixture) in fixtures) {
                val body = readResource("/rss/$fixture")
                val mockUrl = server.url("/$fixture").toString().toUrl()
                mockUrlByOriginal[originalUrl] = mockUrl
                server.enqueue(
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/rss+xml")
                        .body(body)
                        .build(),
                )
            }

            document.leafOutlines().forEach { leaf ->
                val mockUrl = mockUrlByOriginal.getValue(leaf.xmlUrl!!)
                val result = api.addFeed(mockUrl, null)
                db.transaction {
                    db.feed.insertOrReplace(result.feed)
                    db.link.insertForFeed(result.feed.id, result.feedLinks)
                    result.entries.forEach { (entry, links) ->
                        db.entry.insertOrReplace(listOf(entry))
                        db.link.insertForEntry(entry.id, links)
                    }
                }
            }

            val storedFeeds = db.feed.selectAll()
            assertEquals(4, storedFeeds.size)

            val titles = storedFeeds.map { it.title }.toSet()
            assertEquals(
                setOf("Free software jobs", "FSF News", "fossjobs.net", "L'Agenda du Libre"),
                titles,
            )

            val feedIds = storedFeeds.map { it.id }.toSet()
            assertEquals(
                setOf(
                    "http://www.fsf.org/resources/jobs/listing",
                    "http://www.fsf.org/news/aggregator",
                    "https://www.fossjobs.net/",
                    "https://www.agendadulibre.org/",
                ),
                feedIds,
            )

            for (feed in storedFeeds) {
                val entries = db.entry.selectByFeedId(feed.id)
                assertTrue(
                    "Feed ${feed.title} should have at least one entry",
                    entries.isNotEmpty(),
                )
            }

            assertFirstEntryPublished(
                title = "Free software jobs",
                storedFeeds = storedFeeds,
                db = db,
                expectedPublished = "2026-03-10T12:04:09Z".toInstant(),
            )
            assertFirstEntryPublished(
                title = "FSF News",
                storedFeeds = storedFeeds,
                db = db,
                expectedPublished = "2026-06-19T21:13:07Z".toInstant(),
            )
            assertFirstEntryPublished(
                title = "fossjobs.net",
                storedFeeds = storedFeeds,
                db = db,
                expectedPublished = "2026-07-09T11:19:01Z".toInstant(),
            )
            assertFirstEntryPublished(
                title = "L'Agenda du Libre",
                storedFeeds = storedFeeds,
                db = db,
                expectedPublished = "2026-07-18T04:24:06Z".toInstant(),
            )
        } finally {
            server.close()
            client.close()
        }
    }

    private suspend fun assertFirstEntryPublished(
        title: String,
        storedFeeds: List<FeedTable.Feed>,
        db: Database,
        expectedPublished: Instant,
    ) {
        val feed = storedFeeds.single { it.title == title }
        val firstEntry = db.entry.selectByFeedId(feed.id).first()
        assertEquals(
            "Feed '$title' first entry should have published date parsed from the feed",
            expectedPublished,
            firstEntry.published,
        )
    }

    @Test
    fun readsNullFailureOpml() = runBlocking {
        val document = readFile("null-failure.opml").toOpml()

        assertEquals(OpmlVersion.V_1_1, document.version)
        assertEquals(9, document.outlines.size)
        assertEquals(9, document.leafOutlines().size)

        val firstLeaf = document.leafOutlines().first()
        assertEquals("Gagallium", firstLeaf.text)
        assertEquals("https://gallium.inria.fr/blog/index.rss", firstLeaf.xmlUrl)

        importsGalliumFeedServedAsX_rss_xml(firstLeaf.xmlUrl!!)
    }

    private suspend fun importsGalliumFeedServedAsX_rss_xml(feedUrl: String) {
        val server = MockWebServer().apply { start() }
        val db = testDb()
        val client = HttpClient(CIO)
        val api = Embedded(db = db, httpClient = client)

        try {
            val fixture = "gallium.inria.fr.rss.xml"
            val body = readResource("/rss/$fixture")
            val mockUrl = server.url("/$fixture").toString().toUrl()

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/x-rss+xml")
                    .body(body)
                    .build(),
            )

            val result = api.addFeed(mockUrl, null)
            db.transaction {
                db.feed.insertOrReplace(result.feed)
                db.link.insertForFeed(result.feed.id, result.feedLinks)
                result.entries.forEach { (entry, links) ->
                    db.entry.insertOrReplace(listOf(entry))
                    db.link.insertForEntry(entry.id, links)
                }
            }

            val storedFeeds = db.feed.selectAll()
            assertEquals(1, storedFeeds.size)

            val feed = storedFeeds.single()
            assertEquals("Gagallium", feed.title)
            assertEquals("https://cambium.inria.fr/blog/index.rss", feed.id)

            val entries = db.entry.selectByFeedId(feed.id)
            assertEquals(
                "Feed served as application/x-rss+xml must parse 10 entries",
                10,
                entries.size,
            )
            assertTrue(
                "Feed served as application/x-rss+xml must produce a non-empty entry title",
                entries.any { it.title.isNotBlank() },
            )
        } finally {
            server.close()
            client.close()
        }

        assertEquals(feedUrl, "https://gallium.inria.fr/blog/index.rss")
    }

    private fun readFile(path: String) = readResource("/opml/$path")

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
}
