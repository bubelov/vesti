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
    ) = FeedTable.Feed(
        id = id,
        title = id,
        extOpenEntriesInBrowser = extOpenEntriesInBrowser,
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
    fun exportsAllOverrideFields() {
        val feeds = listOf(
            feed(
                id = "1",
                extOpenEntriesInBrowser = true,
                extShowPreviewImages = false,
            ),
        )

        val obj = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray[0].asJsonObject

        assertEquals("miniflux:1", obj.getValue("id").asString)
        assertTrue(obj.getValue("open_entries_in_browser").asBoolean)
        assertFalse(obj.getValue("show_preview_images").asBoolean)
    }

    @Test
    fun omitsNullOverrideFields() {
        val feeds = listOf(
            feed(id = "1", extOpenEntriesInBrowser = true),
        )

        val obj = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray[0].asJsonObject

        assertTrue(obj.has("open_entries_in_browser"))
        assertFalse(obj.has("show_preview_images"))
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
        )

        val array = parseJson(
            exportFeedSettings(feeds, ConfTable.Backend.Miniflux)
        ).asJsonArray

        val ids = array.map { it.asJsonObject.getValue("id").asString }
        assertEquals(listOf("miniflux:2", "miniflux:3"), ids)
    }
}
