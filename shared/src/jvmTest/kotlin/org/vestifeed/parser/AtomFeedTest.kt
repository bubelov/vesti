package org.vestifeed.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 4287 fidelity for the fields the reader depends on: which date the lists
 * sort by, where an entry's author comes from, and what makes an entry
 * unreadable.
 */
class AtomFeedTest {

    private fun parse(xml: String): AtomFeed {
        val result = feed(xml.trimIndent().toByteArray(), "application/atom+xml")
        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)
        val parsed = (result as FeedResult.Success).feed
        assertTrue("Expected AtomFeed but got ${parsed::class.simpleName}", parsed is AtomFeed)
        return parsed as AtomFeed
    }

    /** A feed document wrapping [entries], with a title and no author. */
    private fun atomFeedOf(vararg entries: String): AtomFeed = parse(
        """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>urn:feed</id>
          <title>Example feed</title>
          <link href="https://example.com/feed.xml" rel="self"/>
        ${entries.joinToString("\n")}
        </feed>
        """,
    )

    @Test
    fun entryIsOrderedByItsPublishedDateNotItsUpdateDate() {
        val atom = atomFeedOf(
            """
            <entry>
              <id>urn:one</id>
              <title>An edited old post</title>
              <updated>2026-11-01T00:00:00Z</updated>
              <published>2024-03-05T00:00:00Z</published>
              <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
            </entry>
            """,
        )

        val entry = atom.entries.single()
        assertEquals("2024-03-05T00:00:00Z", entry.published)
        assertEquals("2026-11-01T00:00:00Z", entry.updated)
    }

    @Test
    fun entryWithoutPublishedDateFallsBackToUpdated() {
        val atom = atomFeedOf(
            """
            <entry>
              <id>urn:one</id>
              <title>No published element</title>
              <updated>2026-11-01T00:00:00Z</updated>
              <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
            </entry>
            """,
        )

        assertEquals("2026-11-01T00:00:00Z", atom.entries.single().published)
    }

    @Test
    fun entryWithUnparseablePublishedDateFallsBackToUpdated() {
        val atom = atomFeedOf(
            """
            <entry>
              <id>urn:one</id>
              <title>Broken date</title>
              <updated>2026-11-01T00:00:00Z</updated>
              <published>sometime last tuesday</published>
              <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
            </entry>
            """,
        )

        assertEquals("2026-11-01T00:00:00Z", atom.entries.single().published)
    }

    @Test
    fun entryWithNeitherContentNorAlternateLinkIsDropped() {
        val atom = atomFeedOf(
            """
            <entry>
              <id>urn:empty</id>
              <title>Nothing to read</title>
              <updated>2026-11-01T00:00:00Z</updated>
            </entry>
            """,
            """
            <entry>
              <id>urn:linked</id>
              <title>Body lives at the link</title>
              <updated>2026-11-02T00:00:00Z</updated>
              <link href="https://example.com/article" rel="alternate" type="text/html"/>
            </entry>
            """,
        )

        val entry = atom.entries.single()
        assertEquals("urn:linked", entry.id)
        assertEquals("", entry.content.text)
        assertEquals(
            "https://example.com/article",
            entry.links.single { it.rel is AtomLinkRel.Alternate }.href,
        )
    }

    @Test
    fun entryInheritsTheFeedAuthorWhenItHasNone() {
        val atom = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:feed</id>
              <title>Example feed</title>
              <author><name>Feed Author</name></author>
              <entry>
                <id>urn:one</id>
                <title>Anonymous entry</title>
                <updated>2026-11-01T00:00:00Z</updated>
                <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
              </entry>
            </feed>
            """,
        )

        assertEquals("Feed Author", atom.entries.single().authorName)
    }

    @Test
    fun entryInheritsTheSourceAuthorBeforeTheFeedAuthor() {
        val atom = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:feed</id>
              <title>Example feed</title>
              <author><name>Feed Author</name></author>
              <entry>
                <id>urn:one</id>
                <title>Syndicated entry</title>
                <updated>2026-11-01T00:00:00Z</updated>
                <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
                <source>
                  <id>urn:origin</id>
                  <title>Origin</title>
                  <author><name>Source Author</name></author>
                </source>
              </entry>
            </feed>
            """,
        )

        assertEquals("Source Author", atom.entries.single().authorName)
    }

    @Test
    fun entryAuthorWinsOverTheFeedAuthor() {
        val atom = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:feed</id>
              <title>Example feed</title>
              <author><name>Feed Author</name></author>
              <entry>
                <id>urn:one</id>
                <title>Signed entry</title>
                <updated>2026-11-01T00:00:00Z</updated>
                <author><name>Entry Author</name></author>
                <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
              </entry>
            </feed>
            """,
        )

        assertEquals("Entry Author", atom.entries.single().authorName)
    }

    /**
     * A feed with no title is rejected rather than adopting the first entry's
     * title, which is what a descendant search would have done.
     */
    @Test
    fun feedWithoutATitleIsRejectedInsteadOfBorrowingAnEntryTitle() {
        val result = feed(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:feed</id>
              <entry>
                <id>urn:one</id>
                <title>Entry title</title>
                <updated>2026-11-01T00:00:00Z</updated>
                <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
              </entry>
            </feed>
            """.trimIndent().toByteArray(),
            "application/atom+xml",
        )

        assertTrue(
            "Expected a ParserError but got $result",
            result is FeedResult.ParserError,
        )
    }
}
