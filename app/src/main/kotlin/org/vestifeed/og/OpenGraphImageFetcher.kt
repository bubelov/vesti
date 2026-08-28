package org.vestifeed.og

import coil3.PlatformContext
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.http.await
import org.vestifeed.parser.AtomLinkRel
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class OpenGraphImageFetcher(
    private val db: Database,
    private val imageContext: PlatformContext,
) {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun fetchAndWatch() {
        while (true) {
            val uncheckedEntries = withContext(Dispatchers.IO) {
                db.entry.selectByOgImageChecked(
                    extOgImageChecked = false,
                    limit = 1,
                )
            }

            if (uncheckedEntries.isEmpty()) {
                delay(1.seconds)
            } else {
                fetchEntryImages(uncheckedEntries)
            }
        }
    }

    private suspend fun fetchEntryImages(entries: List<EntryTable.EntryWithoutContent>): List<EntryTable.EntryWithoutContent> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val successfulEntries = mutableListOf<EntryTable.EntryWithoutContent>()

        for (entry in entries) {
            if (fetchEntryImage(entry)) {
                successfulEntries += entry
            }
        }

        return successfulEntries
    }

    private suspend fun fetchEntryImage(entry: EntryTable.EntryWithoutContent): Boolean {
        appendLog(entry.id, "Attempting to fetch OG image for entry ${entry.id}")

        val links = withContext(Dispatchers.IO) {
            db.link.selectByEntryId(entry.id)
        }
        val htmlLink =
            links.firstOrNull { it.rel is AtomLinkRel.Alternate && it.type == "text/html" }
                ?: links.firstOrNull { it.rel is AtomLinkRel.Alternate }
        if (htmlLink == null) {
            appendLog(entry.id, "No HTML alternate link found, marking as checked")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, entry.id)
            }
            return false
        }

        appendLog(entry.id, "Fetching HTML from ${htmlLink.href}")

        val htmlLinkResponse = try {
            httpClient.newCall(Request.Builder().url(htmlLink.href).build()).await()
        } catch (e: Throwable) {
            appendLog(entry.id, "Failed to fetch HTML: ${describe(e)}")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, entry.id)
            }
            return false
        }

        if (!htmlLinkResponse.isSuccessful) {
            appendLog(
                entry.id,
                "HTML fetch returned unsuccessful status ${htmlLinkResponse.code}, marking as checked",
            )
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, entry.id)
            }
            return false
        }

        val html = try {
            htmlLinkResponse.body.string()
        } catch (e: Throwable) {
            appendLog(entry.id, "Failed to read HTML body: ${describe(e)}")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, entry.id)
            }
            return false
        }

        appendLog(entry.id, "Parsing HTML and looking for og:image meta tags")

        val metas = Jsoup.parse(html).select("meta[property=\"og:image\"]")
        val imageUrl = metas.firstOrNull()?.attr("content") ?: ""

        if (imageUrl.isBlank()) {
            appendLog(entry.id, "No og:image meta tag found, marking as checked")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, entry.id)
            }
            return false
        }

        appendLog(entry.id, "Found OG image URL: $imageUrl")

        val imageRequest = ImageRequest.Builder(imageContext)
            .data(imageUrl)
            .size(800)
            .build()

        val bitmap = when (val imageResult = imageContext.imageLoader.execute(imageRequest)) {
            is SuccessResult -> {
                imageResult.image.toBitmap()
            }

            is ErrorResult -> {
                appendLog(entry.id, "Failed to fetch OG image: ${describe(imageResult.throwable)}")
                withContext(Dispatchers.IO) {
                    db.entry.updateOgImageChecked(true, entry.id)
                }
                return false
            }
        }

        appendLog(
            entry.id,
            "OG image downloaded successfully (${bitmap.width}x${bitmap.height})",
        )

        db.entry.updateOgImage(
            extOgImageUrl = imageUrl,
            extOgImageWidth = bitmap.width.toLong(),
            extOgImageHeight = bitmap.height.toLong(),
            extOgImageFetchedAt = OffsetDateTime.now(),
            id = entry.id,
        )

        return true
    }

    private suspend fun appendLog(entryId: String, message: String) {
        val entry = withContext(Dispatchers.IO) {
            db.entry.selectById(entryId)
        } ?: return

        val updated = withContext(Dispatchers.IO) {
            appendLogSync(entry.extOpenGraphImageLog, message)
        }

        withContext(Dispatchers.IO) {
            db.entry.updateOgLog(updated, entryId)
        }
    }

    private fun appendLogSync(existing: String, message: String): String {
        val array = runCatching {
            JsonParser.parseString(existing).asJsonArray
        }.getOrDefault(JsonArray())

        val entry = JsonObject().apply {
            addProperty("timestamp", OffsetDateTime.now().toString())
            addProperty("message", message)
        }
        array.add(entry)

        while (array.size() > MAX_LOG_ENTRIES) {
            array.remove(0)
        }

        return gson.toJson(array)
    }

    private fun describe(t: Throwable): String {
        val type = t.javaClass.simpleName
        val msg = t.message
        return if (msg.isNullOrBlank()) type else "$type: $msg"
    }

    companion object {
        const val MAX_LOG_ENTRIES = 50
    }
}
