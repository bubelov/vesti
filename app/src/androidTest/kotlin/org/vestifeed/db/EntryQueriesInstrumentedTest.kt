package org.vestifeed.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vestifeed.app.db
import org.vestifeed.db.table.EntryTable.Entry
import org.vestifeed.db.table.FeedTable.Feed
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EntryQueriesInstrumentedTest {

    private lateinit var database: Database

    @Before
    fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        database = instrumentation.targetContext.db()
    }

    @Test
    fun selectByQuery_inRussian() {
        val feed = createFeed()
        database.feed.insertOrReplace(listOf(feed))

        val entries = listOf(
            entry().copy(feedId = feed.id, contentText = "Роулинг рулет гуляш"),
            entry().copy(feedId = feed.id, contentText = "РоулинГ рулет гуляш"),
            entry().copy(feedId = feed.id, contentText = "роулинг рулет гуляш"),
        )

        database.entry.insertOrReplace(entries)

        assertEquals(3, database.entry.selectByQuery("Роулинг").size)
        assertEquals(3, database.entry.selectByQuery("РоулинГ").size)
        assertEquals(3, database.entry.selectByQuery("роулинг").size)
    }
}

private fun createFeed(
    id: String = UUID.randomUUID().toString(),
    title: String = "Test Feed",
    extOpenEntriesInBrowser: Boolean? = null,
    extBlockedWords: String = "",
    extShowPreviewImages: Boolean? = null,
) = Feed(
    id = id,
    title = title,
    extOpenEntriesInBrowser = extOpenEntriesInBrowser,
    extBlockedWords = extBlockedWords,
    extShowPreviewImages = extShowPreviewImages,
)

private fun entry() = Entry(
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