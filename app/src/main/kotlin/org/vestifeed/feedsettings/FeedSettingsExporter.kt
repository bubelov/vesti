package org.vestifeed.feedsettings

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable

fun exportFeedSettings(
    feeds: List<FeedTable.Feed>,
    backend: ConfTable.Backend?,
): String {
    val array = JsonArray()

    feeds.forEach { feed ->
        val hasOverrides = feed.extOpenEntriesInBrowser == true
            || feed.extShowPreviewImages != null
            || feed.extBlockedWords.isNotEmpty()

        if (!hasOverrides) return@forEach

        val prefix = backend?.name?.lowercase() ?: "unknown"

        val obj = JsonObject().apply {
            addProperty("id", "$prefix:${feed.id}")
            if (feed.extOpenEntriesInBrowser == true) {
                addProperty("open_entries_in_browser", true)
            }
            feed.extShowPreviewImages?.let { addProperty("show_preview_images", it) }
            if (feed.extBlockedWords.isNotEmpty()) {
                val words = JsonArray()
                feed.extBlockedWords.split(",").forEach { words.add(it.trim()) }
                add("blocked_words", words)
            }
        }
        array.add(obj)
    }

    return GsonBuilder().setPrettyPrinting().create().toJson(array)
}
