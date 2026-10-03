package org.vestifeed.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedTest {

    /**
     * The fixtures live in the Android app's test resources. Prefer the test
     * classpath (used once the fixtures are copied into this module) and fall
     * back to the app module's source tree for the in-repo layout.
     */
    private fun rssBytes(name: String): ByteArray {
        val path = "/rss/$name"
        javaClass.getResourceAsStream(path)?.use { return it.readBytes() }

        val candidates = listOf(
            File("src/jvmTest/resources$path"),
            File("../app/src/test/resources$path"),
            File("app/src/test/resources$path"),
        )
        return candidates.firstOrNull { it.isFile }?.readBytes()
            ?: throw IllegalStateException("Test fixture not found: $path")
    }

    @Test
    fun parsesResearchSwtchAtomFeedServedAsTextPlain() {
        val result = feed(rssBytes("research.swtch.com.feed.atom.xml"), "text/plain; charset=utf-8")

        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)

        val feed = (result as FeedResult.Success).feed
        assertTrue("Expected AtomFeed but got ${feed::class.simpleName}", feed is AtomFeed)

        val atom = feed as AtomFeed
        assertEquals("research!rsc", atom.title)
        assertTrue(
            "research!rsc should expose at least one entry",
            atom.entries.isNotEmpty(),
        )

        atom.entries.forEachIndexed { index, entry ->
            assertTrue(
                "Entry $index should have a non-blank title",
                entry.title.isNotBlank(),
            )
            assertTrue(
                "Entry $index should have at least one link",
                entry.links.isNotEmpty(),
            )
        }
    }

    @Test
    fun parsesGalliumRssFeedServedAsX_rss_xml() {
        val result = feed(rssBytes("gallium.inria.fr.rss.xml"), "application/x-rss+xml")

        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)

        val feed = (result as FeedResult.Success).feed
        assertTrue("Expected RssFeed but got ${feed::class.simpleName}", feed is RssFeed)

        val rss = feed as RssFeed
        assertEquals(RssVersion.RSS_2_0, rss.version)
        assertEquals("Gagallium", rss.channel.title)
        assertEquals("https://cambium.inria.fr/blog/index.rss", rss.channel.link)

        val items = rss.channel.items.getOrThrow()
        assertEquals(10, items.size)

        items.forEachIndexed { index, itemResult ->
            assertTrue("Item $index failed to parse: $itemResult", itemResult.isSuccess)
        }
    }

    @Test
    fun parsesHvgRssFeed() {
        val result = feed(rssBytes("hvg.hu.rss.xml"), "application/rss+xml")

        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)

        val feed = (result as FeedResult.Success).feed
        assertTrue("Expected RssFeed but got ${feed::class.simpleName}", feed is RssFeed)

        val rss = feed as RssFeed
        assertEquals(RssVersion.RSS_2_0, rss.version)
        assertEquals("HVG", rss.channel.title)
        assertEquals("https://hvg.hu", rss.channel.link)
        assertEquals("Friss hírek a HVG.hu hírportálról.", rss.channel.description)

        val items = rss.channel.items.getOrThrow()
        assertTrue("Expected at least one item but got ${items.size}", items.isNotEmpty())

        items.forEachIndexed { index, itemResult ->
            assertTrue("Item $index failed to parse: $itemResult", itemResult.isSuccess)
        }

        val first = items.first().getOrThrow()
        assertNotNull(first.title)
        assertNotNull(first.link)
        assertNotNull(first.description)
    }

    @Test
    fun parsesTrattRss092Feed() {
        val result = feed(rssBytes("tratt.net.laurie.blog.entries.rss.xml"), "application/rss+xml")

        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)

        val feed = (result as FeedResult.Success).feed
        assertTrue("Expected RssFeed but got ${feed::class.simpleName}", feed is RssFeed)

        val rss = feed as RssFeed
        assertEquals(RssVersion.RSS_0_92, rss.version)
        assertEquals("Laurence Tratt: Blog", rss.channel.title)
        assertEquals("https://tratt.net/", rss.channel.link)
        assertEquals("Laurence Tratt", rss.channel.description)

        val items = rss.channel.items.getOrThrow()
        assertTrue("Expected at least one item but got ${items.size}", items.isNotEmpty())

        items.forEachIndexed { index, itemResult ->
            assertTrue("Item $index failed to parse: $itemResult", itemResult.isSuccess)
        }

        val first = items.first().getOrThrow()
        assertNotNull(first.title)
        assertNotNull(first.link)
        assertNotNull(first.pubDate)
    }

    @Test
    fun parsesExpleTiveBlargRssFeed() {
        val result = feed(rssBytes("exple.tive.org.blarg.rss.xml"), "application/rss+xml")

        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)

        val feed = (result as FeedResult.Success).feed
        assertTrue("Expected RssFeed but got ${feed::class.simpleName}", feed is RssFeed)

        val rss = feed as RssFeed
        assertEquals(RssVersion.RSS_2_0, rss.version)
        assertEquals("blarg", rss.channel.title)
        assertEquals("https://exple.tive.org/blarg", rss.channel.link)
        assertEquals("a message, and part of a system of messages", rss.channel.description)

        val items = rss.channel.items.getOrThrow()
        // Ksoup's XML parser is lenient, so the formerly malformed item is
        // recovered and parsed instead of being dropped.
        assertEquals("Expected all 10 items to parse", 10, items.size)

        items.forEachIndexed { index, itemResult ->
            assertTrue("Item $index failed to parse: $itemResult", itemResult.isSuccess)
        }

        val titles = items.mapNotNull { it.getOrNull()?.title }
        assertTrue(
            "The formerly malformed item 'The Personal Is Operational' should now be parsed",
            "The Personal Is Operational" in titles,
        )

        val first = items.first().getOrThrow()
        assertEquals("Spicy", first.title)
        assertNotNull(first.link)
        assertNotNull(first.description)
    }

    @Test
    fun parsesFsfJobsRss10Feed() {
        val result = feed(rssBytes("fsf.jobs.rdf.xml"), "application/rss+xml")

        assertTrue("Expected FeedResult.Success but got $result", result is FeedResult.Success)

        val feed = (result as FeedResult.Success).feed
        assertTrue("Expected RssFeed but got ${feed::class.simpleName}", feed is RssFeed)

        val rss = feed as RssFeed
        assertEquals(RssVersion.RSS_1_0, rss.version)
        assertEquals("Free software jobs", rss.channel.title)
        assertEquals("http://www.fsf.org/resources/jobs/listing", rss.channel.link)
        assertEquals(
            "This is a meeting place where skilled and informed individuals working in the world of free software come to find job opportunities they can believe in.",
            rss.channel.description,
        )

        val items = rss.channel.items.getOrThrow()
        assertTrue("Expected at least one item but got ${items.size}", items.isNotEmpty())

        items.forEachIndexed { index, itemResult ->
            assertTrue("Item $index failed to parse: $itemResult", itemResult.isSuccess)
        }

        val first = items.first().getOrThrow()
        assertNotNull(first.title)
        assertNotNull(first.link)
        assertNotNull(first.description)
    }
}
