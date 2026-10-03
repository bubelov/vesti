package org.vestifeed.feedsettings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable

private val prettyJson: Json = Json { prettyPrint = true }

/**
 * Exports the per-feed override settings as a pretty-printed JSON array.
 *
 * Feeds without any override are skipped. The `id` is prefixed with the
 * lower-cased backend name (or `unknown` when no backend is configured) so
 * the same feed id from two backends cannot collide.
 */
fun exportFeedSettings(
    feeds: List<FeedTable.Feed>,
    backend: ConfTable.Backend?,
): String {
    val array = buildJsonArray {
        feeds.forEach { feed ->
            val hasOverrides = feed.extOpenEntriesInBrowser == true
                || feed.extShowPreviewImages != null
                || feed.extBlockedWords.isNotEmpty()

            if (!hasOverrides) return@forEach

            val prefix = backend?.name?.lowercase() ?: "unknown"

            add(
                buildJsonObject {
                    put("id", "$prefix:${feed.id}")
                    if (feed.extOpenEntriesInBrowser == true) {
                        put("open_entries_in_browser", true)
                    }
                    feed.extShowPreviewImages?.let { put("show_preview_images", it) }
                    if (feed.extBlockedWords.isNotEmpty()) {
                        putJsonArray("blocked_words") {
                            feed.extBlockedWords.split(",").forEach { word ->
                                add(word.trim())
                            }
                        }
                    }
                },
            )
        }
    }

    return prettyJson.encodeToString(JsonArray.serializer(), array)
}
