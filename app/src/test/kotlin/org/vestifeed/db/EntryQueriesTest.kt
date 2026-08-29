package org.vestifeed.db

import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Before
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.TagTable
import kotlin.collections.sortedByDescending

class EntryQueriesTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = db()
    }

    @Test
    fun insertOrReplace() {
        val item = entry()
        db.entry.insertOrReplace(listOf(item))
        assertEquals(item, db.entry.selectById(item.id))
    }

    @Test
    fun selectById() {
        val items = listOf(
            db.entry.insertOrReplace(),
            db.entry.insertOrReplace(),
            db.entry.insertOrReplace(),
        )

        assertEquals(
            items[1],
            db.entry.selectById(items[1].id),
        )
    }

    @Test
    fun selectByReadAndBookmarked() {
        val feed = createFeed()
        db.feed.insertOrReplace(feed)

        val all = listOf(
            entry().copy(feedId = feed.id, extRead = true, extBookmarked = true),
            entry().copy(feedId = feed.id, extRead = true, extBookmarked = false),
            entry().copy(feedId = feed.id, extRead = false, extBookmarked = false),
        )

        db.entry.insertOrReplace(all)

        assertEquals(
            all.filter { !it.extRead && !it.extBookmarked }.map { it.id },
            db.entry.selectUnread().map { it.id },
        )
    }

    @Test
    fun selectByReadSynced() {
        val all = listOf(
            entry().copy(extReadSynced = true),
            entry().copy(extReadSynced = false),
            entry().copy(extReadSynced = true),
        )

        db.entry.insertOrReplace(all)

        assertEquals(
            all.filter { it.extReadSynced }.map { it.withoutContent() }
                .sortedByDescending { it.published },
            db.entry.selectByReadSynced(true),
        )

        assertEquals(
            all.filter { !it.extReadSynced }.map { it.withoutContent() }
                .sortedByDescending { it.published },
            db.entry.selectByReadSynced(false),
        )
    }

    @Test
    fun selectByQuery() {
        val db = db()
        val feed = createFeed()
        db.feed.insertOrReplace(feed)

        val entries = listOf(
            entry().copy(feedId = feed.id, contentText = "Linux 5.19 introduces RSS API"),
            entry().copy(feedId = feed.id, contentText = "LinuX 5.19 introduces RSS API"),
            entry().copy(feedId = feed.id, contentText = "linux 5.19 introduces RSS API"),
            entry().copy(feedId = feed.id, contentText = "Injured Irons Destroy Specifically")
        )

        db.entry.insertOrReplace(entries)

        assertEquals(3, db.entry.selectByQuery("Linux").size)
        assertEquals(3, db.entry.selectByQuery("LinuX").size)
        assertEquals(3, db.entry.selectByQuery("linux").size)
        assertEquals(1, db.entry.selectByQuery("call").size)
    }

    @Test
    fun selectUnread() {
        val feed1 = createFeed(title = "Feed 1")
        val feed2 = createFeed(title = "Feed 2")
        db.feed.insertOrReplace(listOf(feed1, feed2))

        val entries = listOf(
            entry().copy(feedId = feed1.id, title = "Entry 1", extRead = false, extBookmarked = false),
            entry().copy(feedId = feed1.id, title = "Entry 2", extRead = true, extBookmarked = false),
            entry().copy(feedId = feed1.id, title = "Entry 3", extRead = false, extBookmarked = true),
            entry().copy(feedId = feed2.id, title = "Entry 4", extRead = false, extBookmarked = false),
            entry().copy(feedId = feed2.id, title = "Entry 5", extRead = true, extBookmarked = true),
        )

        db.entry.insertOrReplace(entries)

        val unread = db.entry.selectUnread()

        assertEquals(2, unread.size)
        assertEquals(2, db.entry.selectUnreadCount())
        assertEquals("Entry 1", unread.find { it.id == entries[0].id }?.title)
        assertEquals("Entry 4", unread.find { it.id == entries[3].id }?.title)
        assertEquals(feed1.title, unread.find { it.id == entries[0].id }?.feedTitle)
        assertEquals(feed2.title, unread.find { it.id == entries[3].id }?.feedTitle)
    }

    @Test
    fun selectBookmarked() {
        val feed1 = createFeed(title = "Feed 1")
        val feed2 = createFeed(title = "Feed 2")
        db.feed.insertOrReplace(listOf(feed1, feed2))

        val entries = listOf(
            entry().copy(feedId = feed1.id, title = "Entry 1", extBookmarked = true),
            entry().copy(feedId = feed1.id, title = "Entry 2", extBookmarked = false),
            entry().copy(feedId = feed2.id, title = "Entry 3", extBookmarked = true),
            entry().copy(feedId = feed2.id, title = "Entry 4", extBookmarked = false),
        )

        db.entry.insertOrReplace(entries)

        val bookmarked = db.entry.selectBookmarked()

        assertEquals(2, bookmarked.size)
        assertEquals(2, db.entry.selectBookmarkedCount())
        assertEquals("Entry 1", bookmarked.find { it.id == entries[0].id }?.title)
        assertEquals("Entry 3", bookmarked.find { it.id == entries[2].id }?.title)
        assertEquals(feed1.title, bookmarked.find { it.id == entries[0].id }?.feedTitle)
        assertEquals(feed2.title, bookmarked.find { it.id == entries[2].id }?.feedTitle)
    }

    @Test
    fun selectByFeedId() {
        val feed1 = createFeed(title = "Feed 1")
        val feed2 = createFeed(title = "Feed 2")
        db.feed.insertOrReplace(listOf(feed1, feed2))

        val entries = listOf(
            entry().copy(feedId = feed1.id, title = "Entry 1", extRead = false),
            entry().copy(feedId = feed1.id, title = "Entry 2", extRead = true),
            entry().copy(feedId = feed2.id, title = "Entry 3", extRead = false),
        )

        db.entry.insertOrReplace(entries)

        val feed1Entries = db.entry.selectByFeedId(feed1.id)
        val feed2Entries = db.entry.selectByFeedId(feed2.id)

        assertEquals(2, feed1Entries.size)
        assertEquals(1, feed2Entries.size)
        assertEquals("Entry 1", feed1Entries.find { it.id == entries[0].id }?.title)
        assertEquals("Entry 2", feed1Entries.find { it.id == entries[1].id }?.title)
        assertEquals("Entry 3", feed2Entries.find { it.id == entries[2].id }?.title)
    }

    @Test
    fun selectUnreadByFeedIds() {
        val feed1 = createFeed(title = "Feed 1")
        val feed2 = createFeed(title = "Feed 2")
        val feed3 = createFeed(title = "Feed 3")
        db.feed.insertOrReplace(listOf(feed1, feed2, feed3))

        val entries = listOf(
            entry().copy(feedId = feed1.id, title = "F1 unread", extRead = false, extBookmarked = false),
            entry().copy(feedId = feed1.id, title = "F1 read", extRead = true, extBookmarked = false),
            entry().copy(feedId = feed1.id, title = "F1 bookmarked", extRead = false, extBookmarked = true),
            entry().copy(feedId = feed2.id, title = "F2 unread", extRead = false, extBookmarked = false),
            entry().copy(feedId = feed3.id, title = "F3 read", extRead = true, extBookmarked = false),
        )
        db.entry.insertOrReplace(entries)

        assertEquals(2, db.entry.selectUnreadByFeedIds(listOf(feed1.id, feed2.id)).size)
        assertEquals(
            setOf("F1 unread", "F2 unread"),
            db.entry.selectUnreadByFeedIds(listOf(feed1.id, feed2.id)).map { it.title }.toSet(),
        )
        assertEquals(0, db.entry.selectUnreadByFeedIds(listOf(feed3.id)).size)
        assertEquals(0, db.entry.selectUnreadByFeedIds(emptyList()).size)
    }

    @Test
    fun selectUnreadIds() {
        val feed = createFeed()
        db.feed.insertOrReplace(feed)

        val now = OffsetDateTime.now()
        val entries = listOf(
            entry().copy(feedId = feed.id, published = now.minusSeconds(300), extRead = false, extBookmarked = false),
            entry().copy(feedId = feed.id, published = now.minusSeconds(100), extRead = true, extBookmarked = false),
            entry().copy(feedId = feed.id, published = now.minusSeconds(200), extRead = false, extBookmarked = true),
            entry().copy(feedId = feed.id, published = now, extRead = false, extBookmarked = false),
        )
        db.entry.insertOrReplace(entries)

        val unreadIds = db.entry.selectUnreadIds()

        assertEquals(
            listOf(entries[3].id, entries[0].id),
            unreadIds,
        )
    }

    @Test
    fun countByOgImageFetchedAfter() {
        val now = OffsetDateTime.now()
        val longAgo = now.minusSeconds(3600)
        val recent = now.minusSeconds(60)

        db.entry.insertOrReplace(
            listOf(
                entry().copy(extOpenGraphImageFetchedAt = null),
                entry().copy(extOpenGraphImageFetchedAt = longAgo),
                entry().copy(extOpenGraphImageFetchedAt = recent),
                entry().copy(extOpenGraphImageFetchedAt = now),
            ),
        )

        assertEquals(0L, db.entry.countByOgImageFetchedAfter(now.plusSeconds(1)))
        assertEquals(0L, db.entry.countByOgImageFetchedAfter(now))
        assertEquals(1L, db.entry.countByOgImageFetchedAfter(recent))
        assertEquals(2L, db.entry.countByOgImageFetchedAfter(longAgo))
    }

    @Test
    fun selectUnreadCountByTagId() {
        val taggedFeed = createFeed(title = "Tagged feed")
        val untaggedFeed = createFeed(title = "Untagged feed")
        val otherTaggedFeed = createFeed(title = "Other tagged feed")
        db.feed.insertOrReplace(listOf(taggedFeed, untaggedFeed, otherTaggedFeed))

        val tag = createTag(id = "tag-1", name = "Tech")
        val otherTag = createTag(id = "tag-2", name = "News")
        db.tag.insertOrReplace(listOf(tag, otherTag))
        db.feedTag.insert(taggedFeed.id, tag.id)
        db.feedTag.insert(otherTaggedFeed.id, otherTag.id)

        db.entry.insertOrReplace(
            listOf(
                entry().copy(feedId = taggedFeed.id, extRead = false, extBookmarked = false),
                entry().copy(feedId = taggedFeed.id, extRead = false, extBookmarked = true),
                entry().copy(feedId = taggedFeed.id, extRead = true, extBookmarked = false),
                entry().copy(feedId = untaggedFeed.id, extRead = false, extBookmarked = false),
                entry().copy(feedId = otherTaggedFeed.id, extRead = false, extBookmarked = false),
            ),
        )

        assertEquals(2L, db.entry.selectUnreadCountByTagId(tag.id))
        assertEquals(1L, db.entry.selectUnreadCountByTagId(otherTag.id))
    }

    @Test
    fun selectUnreadCountByTagId_noMatchingFeeds_returnsZero() {
        val tag = createTag(id = "tag-orphan", name = "Empty")
        db.tag.insertOrReplace(tag)

        assertEquals(0L, db.entry.selectUnreadCountByTagId(tag.id))
    }

    @Test
    fun selectUnreadCountByTagId_unknownTag_returnsZero() {
        assertEquals(0L, db.entry.selectUnreadCountByTagId("not-a-real-tag"))
    }

    @Test
    fun updateOgImageWritesFetchedAt() {
        val now = OffsetDateTime.now()
        val inserted = entry()
        db.entry.insertOrReplace(listOf(inserted))

        db.entry.updateOgImage(
            extOgImageUrl = "https://example.com/og.png",
            extOgImageWidth = 800L,
            extOgImageHeight = 600L,
            extOgImageFetchedAt = now,
            id = inserted.id,
        )

        val updated = db.entry.selectById(inserted.id)
        assertEquals("https://example.com/og.png", updated?.extOpenGraphImageUrl)
        assertEquals(now, updated?.extOpenGraphImageFetchedAt)
        assertEquals(1L, db.entry.countByOgImageFetchedAfter(now.minusSeconds(1)))
    }

    @Test
    fun updateOgLog_overwritesStoredLog() {
        val inserted = entry()
        db.entry.insertOrReplace(listOf(inserted))

        val log = """[{"timestamp":"2026-08-28T10:00:00Z","message":"hello"}]"""
        db.entry.updateOgLog(extOgLog = log, id = inserted.id)

        val updated = db.entry.selectById(inserted.id)
        assertEquals(log, updated?.extOpenGraphImageLog)
    }

    @Test
    fun insertOrReplace_defaultsOgLogToEmptyArray() {
        val inserted = entry().copy(extOpenGraphImageLog = "[]")
        db.entry.insertOrReplace(listOf(inserted))

        val updated = db.entry.selectById(inserted.id)
        assertEquals("[]", updated?.extOpenGraphImageLog)
    }

    @Test
    fun selectUnread_preservesNullExtShowPreviewImagesFromFeed() {
        val followSettingsFeed = createFeed(title = "Follow settings", extShowPreviewImages = null)
        val hideFeed = createFeed(title = "Hide", extShowPreviewImages = false)
        val showFeed = createFeed(title = "Show", extShowPreviewImages = true)
        db.feed.insertOrReplace(listOf(followSettingsFeed, hideFeed, showFeed))

        db.entry.insertOrReplace(
            listOf(
                entry().copy(feedId = followSettingsFeed.id, title = "follow"),
                entry().copy(feedId = hideFeed.id, title = "hide"),
                entry().copy(feedId = showFeed.id, title = "show"),
            ),
        )

        val unread = db.entry.selectUnread().associateBy { it.title }

        assertEquals(null, unread.getValue("follow").extShowPreviewImages)
        assertEquals(false, unread.getValue("hide").extShowPreviewImages)
        assertEquals(true, unread.getValue("show").extShowPreviewImages)
    }

    @Test
    fun selectBookmarked_preservesNullExtShowPreviewImagesFromFeed() {
        val followSettingsFeed = createFeed(title = "Follow settings", extShowPreviewImages = null)
        val hideFeed = createFeed(title = "Hide", extShowPreviewImages = false)
        db.feed.insertOrReplace(listOf(followSettingsFeed, hideFeed))

        db.entry.insertOrReplace(
            listOf(
                entry().copy(feedId = followSettingsFeed.id, title = "follow", extBookmarked = true),
                entry().copy(feedId = hideFeed.id, title = "hide", extBookmarked = true),
            ),
        )

        val bookmarked = db.entry.selectBookmarked().associateBy { it.title }

        assertEquals(null, bookmarked.getValue("follow").extShowPreviewImages)
        assertEquals(false, bookmarked.getValue("hide").extShowPreviewImages)
    }

    @Test
    fun selectByFeedId_preservesNullExtShowPreviewImagesFromFeed() {
        val followSettingsFeed = createFeed(title = "Follow settings", extShowPreviewImages = null)
        db.feed.insertOrReplace(followSettingsFeed)

        db.entry.insertOrReplace(
            listOf(entry().copy(feedId = followSettingsFeed.id, title = "follow")),
        )

        val rows = db.entry.selectByFeedId(followSettingsFeed.id)
        assertEquals(1, rows.size)
        assertEquals(null, rows[0].extShowPreviewImages)
    }

    @Test
    fun selectUnreadByFeedIds_preservesNullExtShowPreviewImagesFromFeed() {
        val followSettingsFeed = createFeed(title = "Follow settings", extShowPreviewImages = null)
        db.feed.insertOrReplace(followSettingsFeed)

        db.entry.insertOrReplace(
            listOf(entry().copy(feedId = followSettingsFeed.id, title = "follow")),
        )

        val rows = db.entry.selectUnreadByFeedIds(listOf(followSettingsFeed.id))
        assertEquals(1, rows.size)
        assertEquals(null, rows[0].extShowPreviewImages)
    }

    @Test
    fun selectByQuery_preservesNullExtShowPreviewImagesFromFeed() {
        val followSettingsFeed = createFeed(title = "Follow settings", extShowPreviewImages = null)
        val hideFeed = createFeed(title = "Hide", extShowPreviewImages = false)
        db.feed.insertOrReplace(listOf(followSettingsFeed, hideFeed))

        db.entry.insertOrReplace(
            listOf(
                entry().copy(
                    feedId = followSettingsFeed.id,
                    title = "Follow settings entry",
                    contentText = "unique marker",
                ),
                entry().copy(
                    feedId = hideFeed.id,
                    title = "Hide entry",
                    contentText = "unique marker",
                ),
            ),
        )

        val rows = db.entry.selectByQuery("unique marker").associateBy { it.title }

        assertEquals(null, rows.getValue("Follow settings entry").extShowPreviewImages)
        assertEquals(false, rows.getValue("Hide entry").extShowPreviewImages)
    }
}

fun EntryTable.insertOrReplace(): EntryTable.Entry {
    val entry = entry()
    insertOrReplace(listOf(entry))
    return entry
}

fun entry() = EntryTable.Entry(
    contentType = "",
    contentSrc = "",
    contentText = "",
    summary = "",
    id = UUID.randomUUID().toString(),
    feedId = "",
    title = "",
    published = OffsetDateTime.now(),
    updated = OffsetDateTime.now(),
    authorName = "",
    extRead = false,
    extReadSynced = true,
    extBookmarked = false,
    extBookmarkedSynced = true,
    extCommentsUrl = "",
    extOpenGraphImageChecked = true,
    extOpenGraphImageUrl = "",
    extOpenGraphImageWidth = 0,
    extOpenGraphImageHeight = 0,
    extOpenGraphImageFetchedAt = null,
    extOpenGraphImageLog = "[]",
)

fun entryWithoutContent() = EntryTable.EntryWithoutContent(
    summary = "",
    id = UUID.randomUUID().toString(),
    feedId = "",
    title = "",
    published = OffsetDateTime.now(),
    updated = OffsetDateTime.now(),
    authorName = "",
    extRead = false,
    extReadSynced = true,
    extBookmarked = false,
    extBookmarkedSynced = true,
    extCommentsUrl = "",
    extOpenGraphImageChecked = true,
    extOpenGraphImageUrl = "",
    extOpenGraphImageWidth = 0,
    extOpenGraphImageHeight = 0,
    extOpenGraphImageFetchedAt = null,
    extOpenGraphImageLog = "[]",
)

fun EntryTable.Entry.withoutContent() = EntryTable.EntryWithoutContent(
    summary = summary,
    id = id,
    feedId = feedId,
    title = title,
    published = published,
    updated = updated,
    authorName = authorName,
    extRead = extRead,
    extReadSynced = extReadSynced,
    extBookmarked = extBookmarked,
    extBookmarkedSynced = extBookmarkedSynced,
    extCommentsUrl = extCommentsUrl,
    extOpenGraphImageChecked = extOpenGraphImageChecked,
    extOpenGraphImageUrl = extOpenGraphImageUrl,
    extOpenGraphImageWidth = extOpenGraphImageWidth,
    extOpenGraphImageHeight = extOpenGraphImageHeight,
    extOpenGraphImageFetchedAt = extOpenGraphImageFetchedAt,
    extOpenGraphImageLog = extOpenGraphImageLog,
)

fun EntryTable.EntryWithoutContent.toEntry(): EntryTable.Entry {
    return EntryTable.Entry(
        contentType = "",
        contentSrc = "",
        contentText = "",
        summary = summary,
        id = id,
        feedId = feedId,
        title = title,
        published = published,
        updated = updated,
        authorName = authorName,
        extRead = extRead,
        extReadSynced = extReadSynced,
        extBookmarked = extBookmarked,
        extBookmarkedSynced = extBookmarkedSynced,
        extCommentsUrl = extCommentsUrl,
        extOpenGraphImageChecked = extOpenGraphImageChecked,
        extOpenGraphImageUrl = extOpenGraphImageUrl,
        extOpenGraphImageWidth = extOpenGraphImageWidth,
        extOpenGraphImageHeight = extOpenGraphImageHeight,
        extOpenGraphImageFetchedAt = extOpenGraphImageFetchedAt,
        extOpenGraphImageLog = extOpenGraphImageLog,
    )
}

private fun createFeed(
    id: String = UUID.randomUUID().toString(),
    title: String = "Test Feed",
    extOpenEntriesInBrowser: Boolean? = null,
    extBlockedWords: String = "",
    extShowPreviewImages: Boolean? = null,
) = FeedTable.Feed(
    id = id,
    title = title,
    extOpenEntriesInBrowser = extOpenEntriesInBrowser,
    extBlockedWords = extBlockedWords,
    extShowPreviewImages = extShowPreviewImages,
)

private fun createTag(
    id: String = UUID.randomUUID().toString(),
    name: String = "Test Tag",
) = TagTable.Tag(
    id = id,
    name = name,
    extSource = TagTable.Source.Embedded,
    extMinifluxId = null,
)
