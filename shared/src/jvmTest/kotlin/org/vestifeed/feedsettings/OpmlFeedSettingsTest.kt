package org.vestifeed.feedsettings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.vestifeed.db.table.FeedTable
import org.vestifeed.opml.OpmlOutline

class OpmlFeedSettingsTest {

    private fun feed(
        title: String = "Fetched title",
        extOpenEntriesInBrowser: Boolean? = null,
        extShowPreviewImages: Boolean? = null,
        extBlockedWords: String = "",
    ) = FeedTable.Feed(
        id = "feed-1",
        title = title,
        extOpenEntriesInBrowser = extOpenEntriesInBrowser,
        extBlockedWords = extBlockedWords,
        extShowPreviewImages = extShowPreviewImages,
    )

    private fun outline(
        text: String = "OPML title",
        extOpenEntriesInBrowser: Boolean? = false,
        extShowPreviewImages: Boolean? = null,
        extBlockedWords: String? = "",
    ) = OpmlOutline(
        text = text,
        outlines = emptyList(),
        xmlUrl = "https://example.com/feed.xml",
        htmlUrl = "https://example.com/",
        extOpenEntriesInBrowser = extOpenEntriesInBrowser,
        extShowPreviewImages = extShowPreviewImages,
        extBlockedWords = extBlockedWords,
    )

    @Test
    fun appliesEveryPerFeedOverrideFromTheOutline() {
        val result = feed().withOpmlOverrides(
            outline(
                extOpenEntriesInBrowser = true,
                extShowPreviewImages = false,
                extBlockedWords = "spoilers, leaks",
            ),
        )

        assertEquals(true, result.extOpenEntriesInBrowser)
        assertEquals(false, result.extShowPreviewImages)
        assertEquals("spoilers, leaks", result.extBlockedWords)
    }

    @Test
    fun keepsTheFetchedTitleAndOnlyFallsBackToTheOutlineText() {
        assertEquals("Fetched title", feed().withOpmlOverrides(outline()).title)
        assertEquals("OPML title", feed(title = "").withOpmlOverrides(outline()).title)
    }

    @Test
    fun preservesFollowGlobalForPreviewImages() {
        assertEquals(null, feed().withOpmlOverrides(outline(extShowPreviewImages = null)).extShowPreviewImages)
    }
}
