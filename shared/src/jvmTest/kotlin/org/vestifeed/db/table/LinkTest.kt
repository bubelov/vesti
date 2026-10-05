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

    private fun createFeed(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Feed",
    ) = FeedTable.Feed(
        id = id,
        title = title,
        extOpenEntriesInBrowser = null,
        extBlockedWords = "",
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
