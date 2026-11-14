package org.vestifeed.backend

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.parser.AtomLinkRel

/**
 * DTO-to-row mapping, with neither HTTP nor a database. These are the decisions
 * whose details are easy to lose in a refactor: which status counts as read,
 * which URL becomes the `alternate` link, and where enclosures go.
 */
class MinifluxMappingTest {

    private fun entry(
        status: MinifluxEntryStatus = MinifluxEntryStatus.UNREAD,
        url: String = "",
        enclosures: List<MinifluxEnclosure> = emptyList(),
    ) = MinifluxEntry(
        id = 42,
        feedId = 7,
        status = status,
        title = "An entry",
        url = url,
        commentsUrl = "https://example.com/comments",
        publishedAt = Instant.parse("2024-01-01T00:00:00Z"),
        changedAt = Instant.parse("2024-02-02T00:00:00Z"),
        content = "<p>Body</p>",
        author = "Ada",
        starred = true,
        enclosures = enclosures,
    )

    @Test
    fun unreadStatusIsTheOnlyUnreadRow() {
        assertTrue(MinifluxMapping.toEntry(entry(MinifluxEntryStatus.UNREAD)).first.extRead.not())
    }

    /**
     * `removed` entries are not articles waiting to be read; if they counted as
     * unread they would reappear in the Unread tab. An unknown status is
     * treated the same way rather than as unread.
     */
    @Test
    fun readRemovedAndUnknownStatusesAreStoredAsRead() {
        listOf(
            MinifluxEntryStatus.READ,
            MinifluxEntryStatus.REMOVED,
            MinifluxEntryStatus.UNKNOWN,
        ).forEach { status ->
            assertTrue(
                "$status should be stored as read",
                MinifluxMapping.toEntry(entry(status)).first.extRead,
            )
        }
    }

    @Test
    fun entryFieldsLandInTheRightColumns() {
        val row = MinifluxMapping.toEntry(entry()).first

        assertEquals("42", row.id)
        assertEquals("7", row.feedId)
        assertEquals("An entry", row.title)
        assertEquals("html", row.contentType)
        assertEquals("<p>Body</p>", row.contentText)
        assertNull("Miniflux resolves the body; there is no separate summary", row.summary)
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), row.published)
        assertEquals(Instant.parse("2024-02-02T00:00:00Z"), row.updated)
        assertEquals("Ada", row.authorName)
        assertEquals("https://example.com/comments", row.extCommentsUrl)
        assertTrue(row.extBookmarked)
        // The server's state is what these flags describe: it has been told.
        assertTrue(row.extReadSynced)
        assertTrue(row.extBookmarkedSynced)
        assertEquals("[]", row.extOpenGraphImageLog)
    }

    @Test
    fun entryUrlBecomesTheAlternateLink() {
        val links = MinifluxMapping.toEntry(entry(url = "https://example.com/article")).second

        val alternate = links.single()
        assertEquals("https://example.com/article", alternate.href)
        assertEquals(AtomLinkRel.Alternate, alternate.rel)
        assertEquals("text/html", alternate.type)
        assertEquals("42", alternate.entryId)
    }

    @Test
    fun entryWithoutUrlHasNoLinks() {
        val links = MinifluxMapping.toEntry(entry(url = "")).second

        assertTrue("an entry with no URL cannot be opened, so it gets no link", links.isEmpty())
    }

    @Test
    fun enclosuresBecomeEnclosureLinksCarryingTheirSize() {
        val links = MinifluxMapping.toEntry(
            entry(
                url = "https://example.com/article",
                enclosures = listOf(
                    MinifluxEnclosure(
                        id = 1,
                        url = "https://example.com/episode.mp3",
                        mimeType = "audio/mpeg",
                        size = 1234,
                    ),
                ),
            ),
        ).second

        assertEquals(2, links.size)
        val enclosure = links.single { it.rel is AtomLinkRel.Enclosure }
        assertEquals("https://example.com/episode.mp3", enclosure.href)
        assertEquals("audio/mpeg", enclosure.type)
        assertEquals(1234L, enclosure.length)
    }

    @Test
    fun feedMapsToARowAndItsSelfAndAlternateLinks() {
        val dto = MinifluxFeed(
            id = 7,
            title = "A feed",
            feedUrl = "https://example.com/feed.xml",
            siteUrl = "https://example.com/",
            categoryId = 9L,
        )

        val feed = MinifluxMapping.toFeed(dto)
        assertEquals("7", feed.id)
        assertEquals("A feed", feed.title)
        // The per-feed display overrides belong to the user and start unset;
        // the sync copies the cached values back over them. `false` (rather than
        // null) is the historical default this mapping has always produced.
        assertNull(feed.extShowPreviewImages)
        assertEquals(false, feed.extOpenEntriesInBrowser)
        assertEquals("", feed.extBlockedWords)

        val links = MinifluxMapping.toFeedLinks(dto)
        assertEquals(2, links.size)
        assertEquals(
            "https://example.com/feed.xml",
            links.single { it.rel is AtomLinkRel.Self }.href,
        )
        val alternate = links.single { it.rel is AtomLinkRel.Alternate }
        assertEquals("https://example.com/", alternate.href)
        assertEquals("text/html", alternate.type)
        links.forEach { assertEquals("7", it.feedId) }
    }
}
