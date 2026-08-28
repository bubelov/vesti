package org.vestifeed.entries

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.TagTable
import java.time.OffsetDateTime
import java.util.UUID

class EntriesFilterTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = Database(BundledSQLiteDriver(), ":memory:")
    }

    @Test
    fun belongToTag_loadEntries_returnsOnlyUnreadFromTaggedFeeds() = runBlocking {
        val taggedFeedId = "tagged-${UUID.randomUUID()}"
        val otherFeedId = "other-${UUID.randomUUID()}"
        val tagId = "tag-${UUID.randomUUID()}"

        db.feed.insertOrReplace(
            listOf(
                FeedTable.Feed(
                    id = taggedFeedId,
                    title = "Tagged feed",
                    extOpenEntriesInBrowser = false,
                    extBlockedWords = "",
                    extShowPreviewImages = false,
                ),
                FeedTable.Feed(
                    id = otherFeedId,
                    title = "Other feed",
                    extOpenEntriesInBrowser = false,
                    extBlockedWords = "",
                    extShowPreviewImages = false,
                ),
            ),
        )

        db.tag.insertOrReplace(
            TagTable.Tag(
                id = tagId,
                name = "Tech",
                extSource = TagTable.Source.Embedded,
                extMinifluxId = null,
            ),
        )
        db.feedTag.insert(feedId = taggedFeedId, tagId = tagId)

        val now = OffsetDateTime.now()
        db.entry.insertOrReplace(
            listOf(
                newEntry(taggedFeedId, "tagged-unread", now.minusMinutes(3), read = false, bookmarked = false),
                newEntry(taggedFeedId, "tagged-read", now.minusMinutes(2), read = true, bookmarked = false),
                newEntry(taggedFeedId, "tagged-bookmarked", now.minusMinutes(1), read = false, bookmarked = true),
                newEntry(otherFeedId, "other-unread", now, read = false, bookmarked = false),
            ),
        )

        val loaded = EntriesFilter.BelongToTag(tagId = tagId).loadEntries(db)
        assertEquals(listOf("tagged-unread"), loaded.map { it.title })
    }

    @Test
    fun belongToTag_loadEntries_isEmptyWhenTagHasNoFeeds() = runBlocking {
        val tagId = "tag-${UUID.randomUUID()}"
        db.tag.insertOrReplace(
            TagTable.Tag(
                id = tagId,
                name = "Empty",
                extSource = TagTable.Source.Embedded,
                extMinifluxId = null,
            ),
        )

        val loaded = EntriesFilter.BelongToTag(tagId = tagId).loadEntries(db)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun belongToTag_resolveTitle_usesTagName() = runBlocking {
        val tagId = "tag-${UUID.randomUUID()}"
        db.tag.insertOrReplace(
            TagTable.Tag(
                id = tagId,
                name = "Linux",
                extSource = TagTable.Source.Embedded,
                extMinifluxId = null,
            ),
        )

        val title = EntriesFilter.BelongToTag(tagId = tagId).resolveTitle(db)
        assertEquals(EntriesFilter.TitleFormat.Custom("Linux"), title)
    }

    private fun newEntry(
        feedId: String,
        title: String,
        published: OffsetDateTime,
        read: Boolean,
        bookmarked: Boolean,
    ) = EntryTable.Entry(
        contentType = null,
        contentSrc = null,
        contentText = null,
        summary = null,
        id = UUID.randomUUID().toString(),
        feedId = feedId,
        title = title,
        published = published,
        updated = published,
        authorName = "",
        extRead = read,
        extReadSynced = true,
        extBookmarked = bookmarked,
        extBookmarkedSynced = true,
        extCommentsUrl = "",
        extOpenGraphImageChecked = true,
        extOpenGraphImageUrl = "",
        extOpenGraphImageWidth = 0,
        extOpenGraphImageHeight = 0,
        extOpenGraphImageFetchedAt = null,
        extOpenGraphImageLog = "[]",
    )
}
