package org.vestifeed.backend

import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.testDb
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomLinkRel

/**
 * Embedded RSS items without a guid are keyed by a hash of their content. An
 * edited description changes that hash; the article URL must keep the sync
 * updating the row that already exists instead of inserting a duplicate.
 */
class EmbeddedEntryIdsTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = testDb()
    }

    @Test
    fun reusesTheExistingEntryForTheSameArticleUrl() = runBlocking<Unit> {
        val feed = feed()
        db.feed.insertOrReplace(feed)
        val existing = entry(id = "feed-id:feed-1,old", feedId = feed.id)
        db.entry.insertOrReplace(listOf(existing))
        db.link.insertForEntry(
            existing.id,
            listOf(alternateLink(href = "https://example.com/article")),
        )

        val fresh = entry(id = "feed-id:feed-1,new", feedId = feed.id).copy(title = "Edited title")
        val resolved = resolveEntryIds(
            db = db,
            feedId = feed.id,
            entries = listOf(
                fresh to listOf(
                    alternateLink(
                        href = "https://example.com/article",
                        entryId = fresh.id,
                    ),
                ),
            ),
        )

        assertEquals("feed-id:feed-1,old", resolved.single().first.id)
        assertEquals("feed-id:feed-1,old", resolved.single().second.single().entryId)
    }

    @Test
    fun keepsTheFreshIdWhenTheArticleIsNew() = runBlocking<Unit> {
        val feed = feed()
        db.feed.insertOrReplace(feed)
        val fresh = entry(id = "feed-id:feed-1,new", feedId = feed.id)

        val resolved = resolveEntryIds(
            db = db,
            feedId = feed.id,
            entries = listOf(
                fresh to listOf(
                    alternateLink(
                        href = "https://example.com/new-article",
                        entryId = fresh.id,
                    ),
                ),
            ),
        )

        assertEquals("feed-id:feed-1,new", resolved.single().first.id)
    }

    @Test
    fun doesNotReuseAnEntryFromAnotherFeed() = runBlocking<Unit> {
        val feed = feed(id = "feed-1")
        val otherFeed = feed(id = "feed-2")
        db.feed.insertOrReplace(listOf(feed, otherFeed))

        val other = entry(id = "feed-id:feed-2,old", feedId = otherFeed.id)
        db.entry.insertOrReplace(listOf(other))
        db.link.insertForEntry(
            other.id,
            listOf(alternateLink(href = "https://example.com/article")),
        )

        val fresh = entry(id = "feed-id:feed-1,new", feedId = feed.id)
        val resolved = resolveEntryIds(
            db = db,
            feedId = feed.id,
            entries = listOf(
                fresh to listOf(
                    alternateLink(href = "https://example.com/article", entryId = fresh.id),
                ),
            ),
        )

        assertEquals("feed-id:feed-1,new", resolved.single().first.id)
    }

    @Test
    fun doesNotMergeDistinctGuidEntriesThatShareAUrl() = runBlocking<Unit> {
        val feed = feed()
        db.feed.insertOrReplace(feed)

        val existing = entry(id = "guid:one", feedId = feed.id)
        db.entry.insertOrReplace(listOf(existing))
        db.link.insertForEntry(
            existing.id,
            listOf(alternateLink(href = "https://example.com/article")),
        )

        val fresh = entry(id = "guid:two", feedId = feed.id)
        val resolved = resolveEntryIds(
            db = db,
            feedId = feed.id,
            entries = listOf(
                fresh to listOf(
                    alternateLink(href = "https://example.com/article", entryId = fresh.id),
                ),
            ),
        )

        assertEquals("guid:two", resolved.single().first.id)
    }

    private fun feed(id: String = "feed-1") = FeedTable.Feed(
        id = id,
        title = "Feed",
        extOpenEntriesInBrowser = null,
        extShowPreviewImages = null,
    )

    private fun entry(id: String, feedId: String) = EntryTable.Entry(
        contentType = "html",
        contentSrc = "",
        contentText = "Body",
        summary = "",
        id = id,
        feedId = feedId,
        title = "Article",
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

    private fun alternateLink(href: String, entryId: String? = null) = LinkTable.Link(
        id = null,
        feedId = null,
        entryId = entryId,
        href = href,
        rel = AtomLinkRel.Alternate,
        type = "text/html",
        hreflang = null,
        title = null,
        length = null,
        extEnclosureDownloadProgress = null,
        extCacheUri = null,
    )
}
