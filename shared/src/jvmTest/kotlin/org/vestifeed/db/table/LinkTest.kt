package org.vestifeed.db.table

import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.testDb
import org.vestifeed.parser.AtomLinkRel

class LinkTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = testDb()
    }

    @Test
    fun linkQueries_deleteForFeed_removesFeedAndEntryLinks() = runBlocking<Unit> {
        val feed = createFeed()
        db.feed.insertOrReplace(feed)
        val entry = createEntry(feed.id)
        db.entry.insertOrReplace(listOf(entry))
        db.link.insertForFeed(feed.id, listOf(createLink("https://example.com/feed.xml")))
        db.link.insertForEntry(entry.id, listOf(createLink("https://example.com/entry")))

        db.link.deleteForFeed(feed.id)

        assertEquals(0, db.link.selectByFeedId(feed.id).size)
        assertEquals(0, db.link.selectByEntryId(entry.id).size)
        // The entry can now be deleted without tripping the entry_id foreign key.
        db.entry.deleteByFeedId(feed.id)
        assertNull(db.entry.selectById(entry.id))
    }

    @Test
    fun hasAudioEnclosures_falseWithoutAudio() = runBlocking<Unit> {
        val feed = createFeed()
        db.feed.insertOrReplace(feed)
        val entry = createEntry(feed.id)
        db.entry.insertOrReplace(listOf(entry))
        db.link.insertForEntry(entry.id, listOf(createLink("https://example.com/article")))

        assertEquals(false, db.link.hasAudioEnclosures())
    }

    @Test
    fun hasAudioEnclosures_trueForAudioEnclosure() = runBlocking<Unit> {
        val feed = createFeed()
        db.feed.insertOrReplace(feed)
        val entry = createEntry(feed.id)
        db.entry.insertOrReplace(listOf(entry))
        db.link.insertForEntry(
            entry.id,
            listOf(
                createLink("https://example.com/ep.mp3")
                    .copy(rel = AtomLinkRel.Enclosure, type = "audio/mpeg"),
            ),
        )

        assertEquals(true, db.link.hasAudioEnclosures())
    }

    @Test
    fun reconcileForEntry_updatesMetadataAndKeepsPlaybackState() = runBlocking<Unit> {
        val entry = seedEntry()

        val enclosure = createLink("https://example.com/ep.mp3").copy(
            rel = AtomLinkRel.Enclosure,
            type = "audio/mpeg",
            length = 10L,
        )
        db.link.reconcileForEntry(entry.id, listOf(enclosure))

        val stored = db.link.selectByEntryId(entry.id).single()
        db.link.updateEnclosureProgress(stored.id!!, 0.5, "file:///cache/ep.mp3")
        db.link.updatePlayedAndPlayedAt(stored.id, true, Clock.System.now())

        db.link.reconcileForEntry(
            entry.id,
            listOf(enclosure.copy(type = "audio/ogg", length = 20L)),
        )

        val updated = db.link.selectByEntryId(entry.id).single()
        assertEquals("audio/ogg", updated.type)
        assertEquals(20L, updated.length)
        assertEquals(0.5, updated.extEnclosureDownloadProgress!!, 0.0001)
        assertEquals("file:///cache/ep.mp3", updated.extCacheUri)
        assertEquals(true, updated.extPlayed)
    }

    @Test
    fun reconcileForEntry_removesLinksTheServerNoLongerSends() = runBlocking<Unit> {
        val entry = seedEntry()
        val article = createLink("https://example.com/article")
            .copy(rel = AtomLinkRel.Alternate, type = "text/html")
        val oldEnclosure = createLink("https://example.com/old.mp3")
            .copy(rel = AtomLinkRel.Enclosure, type = "audio/mpeg")
        val newEnclosure = createLink("https://example.com/new.mp3")
            .copy(rel = AtomLinkRel.Enclosure, type = "audio/mpeg")

        db.link.reconcileForEntry(entry.id, listOf(article, oldEnclosure))
        assertEquals(2, db.link.selectByEntryId(entry.id).size)

        db.link.reconcileForEntry(entry.id, listOf(article, newEnclosure))

        assertEquals(
            setOf(article.href, newEnclosure.href),
            db.link.selectByEntryId(entry.id).map { it.href }.toSet(),
        )
    }

    @Test
    fun reconcileForEntry_withNoLinksDeletesEverything() = runBlocking<Unit> {
        val entry = seedEntry()
        db.link.reconcileForEntry(
            entry.id,
            listOf(createLink("https://example.com/article").copy(rel = AtomLinkRel.Alternate)),
        )

        db.link.reconcileForEntry(entry.id, emptyList())

        assertEquals(0, db.link.selectByEntryId(entry.id).size)
    }

    private suspend fun seedEntry(): EntryTable.Entry {
        val feed = createFeed()
        db.feed.insertOrReplace(feed)
        val entry = createEntry(feed.id)
        db.entry.insertOrReplace(listOf(entry))
        return entry
    }

    private fun createFeed(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Feed",
    ) = FeedTable.Feed(
        id = id,
        title = title,
        extOpenEntriesInBrowser = null,
        extShowPreviewImages = null,
    )

    private fun createEntry(feedId: String) = EntryTable.Entry(
        contentType = "",
        contentSrc = "",
        contentText = "",
        summary = "",
        id = UUID.randomUUID().toString(),
        feedId = feedId,
        title = "",
        published = Clock.System.now(),
        updated = Clock.System.now(),
        authorName = "",
        extRead = false,
        extReadSynced = true,
        extBookmarked = false,
        extBookmarkedSynced = true,
        extCommentsUrl = "",
        extOpenGraphImageChecked = true,
        extOpenGraphImageUrl = "",
        extOpenGraphImageFetchedAt = null,
        extOpenGraphImageLog = "[]",
    )

    private fun createLink(href: String) = LinkTable.Link(
        id = null,
        feedId = null,
        entryId = null,
        href = href,
        rel = null,
        type = null,
        hreflang = null,
        title = null,
        length = null,
        extEnclosureDownloadProgress = null,
        extCacheUri = null,
    )
}
