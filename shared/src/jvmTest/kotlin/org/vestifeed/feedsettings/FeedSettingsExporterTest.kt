package org.vestifeed.feedsettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.json.asBoolean
import org.vestifeed.json.asJsonArray
import org.vestifeed.json.asJsonObject
import org.vestifeed.json.asString
import org.vestifeed.json.has
import org.vestifeed.json.parseJson

class FeedSettingsExporterTest {

    private fun feed(
        id: String,
        extOpenEntriesInBrowser: Boolean? = null,
        extShowPreviewImages: Boolean? = null,
        extBlockedWords: String = "",
    ) = FeedTable.Feed(
        id = id,
        title = id,
        extOpenEntriesInBrowser = extOpenEntriesInBrowser,
        extBlockedWords = extBlockedWords,
        extShowPreviewImages = extShowPreviewImages,
    )

    @Test
    fun skipsFeedsWithNoOverrides() {
        val feeds = listOf(
            feed(id = "1"),
            feed(id = "500", extOpenEntriesInBrowser = true),
        )

        val array = parseJson(exportFeedSettings(feeds, ConfTable.Backend.Miniflux)).asJsonArray

        assertEquals(1, array.size)
        assertEquals("miniflux:500", array[0].asJsonObject.getValue("id").asString)
    }

    @Test
    fun prefixesIdWithBackendName() {
        val minifluxFeeds = listOf(feed(id = "500", extOpenEntriesInBrowser = true))
        val embeddedFeeds = listOf(feed(id = "abc", extShowPreviewImages = true))

        val minifluxArray = parseJson(
            exportFeedSettings(minifluxFeeds, ConfTable.Backend.Miniflux)
        ).asJsonArray
        assertEquals("miniflux:500", minifluxArray[0].asJsonObject.getValue("id").asString)

        val embeddedArray = parseJson(
            exportFeedSettings(embeddedFeeds, ConfTable.Backend.Embedded)
        ).asJsonArray
        assertEquals("embedded:abc", embeddedArray[0].asJsonObject.getValue("id").asString)
    }

    @Test
    fun prefixesIdWithUnknownWhenBackendIsNull() {
        val feeds = listOf(feed(id = "1", extOpenEntriesInBrowser = true))

        val array = parseJson(exportFeedSettings(feeds, null)).asJsonArray

        assertEquals("unknown:1", array[0].asJsonObject.getValue("id").asString)
    }

    @Test
    fun exportsAllThreeOverrideFields() {
        val feeds = listOf(
            feed(
                id = "1",
                extOpenEntriesInBrowser = true,
                extShowPreviewImages = false,
                extBlockedWords = "spam,ads",
            ),
        )

        val obj = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray[0].asJsonObject

        assertEquals("miniflux:1", obj.getValue("id").asString)
        assertTrue(obj.getValue("open_entries_in_browser").asBoolean)
        assertFalse(obj.getValue("show_preview_images").asBoolean)
        val blockedWords = obj.getValue("blocked_words").asJsonArray
        assertEquals(2, blockedWords.size)
        assertEquals("spam", blockedWords[0].asString)
        assertEquals("ads", blockedWords[1].asString)
    }

    @Test
    fun omitsNullOverrideFields() {
        val feeds = listOf(
            feed(id = "1", extOpenEntriesInBrowser = true, extBlockedWords = "spam"),
        )

        val obj = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray[0].asJsonObject

        assertTrue(obj.has("open_entries_in_browser"))
        assertTrue(obj.has("blocked_words"))
        assertFalse(obj.has("show_preview_images"))
    }

    @Test
    fun trimsWhitespaceFromBlockedWords() {
        val feeds = listOf(
            feed(id = "1", extBlockedWords = "spam , ads ,promo"),
        )

        val blockedWords = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray[0].asJsonObject.getValue("blocked_words").asJsonArray

        assertEquals(listOf("spam", "ads", "promo"), blockedWords.map { it.asString })
    }

    @Test
    fun treatsFalseOpenEntriesInBrowserAsNoOverride() {
        val feeds = listOf(
            feed(id = "1", extOpenEntriesInBrowser = false),
            feed(id = "2", extOpenEntriesInBrowser = true),
        )

        val array = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray

        val ids = array.map { it.asJsonObject.getValue("id").asString }
        assertEquals(listOf("miniflux:2"), ids)
    }

    @Test
    fun treatsEmptyBlockedWordsAsNoOverride() {
        val feeds = listOf(feed(id = "1", extBlockedWords = ""))

        val array = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray

        assertEquals(0, array.size)
    }

    @Test
    fun emptyFeedListProducesEmptyArray() {
        val json = exportFeedSettings(emptyList(), ConfTable.Backend.Miniflux)
        assertEquals("[]", json.replace("\\s".toRegex(), ""))
    }

    @Test
    fun mixedFeedsExportOnlyThoseWithOverrides() {
        val feeds = listOf(
            feed(id = "1"),
            feed(id = "2", extOpenEntriesInBrowser = true),
            feed(id = "3", extShowPreviewImages = false),
            feed(id = "4", extBlockedWords = "spam"),
            feed(id = "5", extOpenEntriesInBrowser = null, extShowPreviewImages = null, extBlockedWords = ""),
        )

        val array = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray

        val ids = array.map { it.asJsonObject.getValue("id").asString }
        assertEquals(
            listOf("miniflux:2", "miniflux:3", "miniflux:4"),
            ids,
        )
    }
}
