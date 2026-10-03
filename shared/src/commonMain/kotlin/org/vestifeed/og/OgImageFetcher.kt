package org.vestifeed.og

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.json.parseJson
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.platform.proxiedUrl

/**
 * Fetches each entry's OpenGraph image: the article page's `og:image`, then the
 * image itself, storing the URL and its pixel size in the entry.
 *
 * Shares [OgImagePlanning]'s pure gating and [imageSize]'s header parsing with
 * every host. Feed/article/image requests go through [proxiedUrl], so on the
 * browser they are relayed by the same-origin proxy.
 */
class OgImageFetcher(
    private val db: Database,
    private val httpClient: HttpClient = HttpClient(),
    private val isOnline: () -> Boolean = { true },
    private val isForeground: () -> Boolean = { true },
) {

    /** Long-lived loop; stops when its coroutine is cancelled. */
    suspend fun fetchAndWatch() {
        // The host may start this before its UI has connected the database.
        db.connect()
        while (coroutineContext.isActive) {
            runOnce()
        }
    }

    suspend fun runOnce() {
        when (OgImagePlanning.ogRunSkip(isOnline(), isForeground())) {
            OgRunSkip.Offline, OgRunSkip.NotForeground -> delay(OFFLINE_INTERVAL)
            OgRunSkip.No -> runOnceBody()
        }
    }

    private suspend fun runOnceBody() {
        val conf = db.conf.select()
        val candidates = db.entry.selectPendingOgImageEntries(BATCH_SIZE)
        when (val plan = OgImagePlanning.planOgImageFetch(conf, candidates)) {
            OgFetchPlan.GlobalOff -> delay(GLOBAL_OFF_INTERVAL)
            OgFetchPlan.Empty -> delay(EMPTY_INTERVAL)
            is OgFetchPlan.Fetch -> plan.candidates.forEach { fetchOne(it) }
        }
    }

    private suspend fun fetchOne(candidate: EntryTable.OgImageCandidate): Boolean {
        appendLog(candidate.id, "Attempting to fetch OG image for entry ${candidate.id}")

        val links = db.link.selectByEntryId(candidate.id)
        val htmlLink = links.firstOrNull { it.rel is AtomLinkRel.Alternate && it.type == "text/html" }
            ?: links.firstOrNull { it.rel is AtomLinkRel.Alternate }
        if (htmlLink == null) {
            appendLog(candidate.id, "No HTML alternate link found, marking as checked")
            db.entry.updateOgImageChecked(true, candidate.id)
            return false
        }

        appendLog(candidate.id, "Fetching HTML from ${htmlLink.href}")
        val html = try {
            val response = httpClient.get(proxiedUrl(htmlLink.href))
            if (!response.status.isSuccess()) {
                appendLog(
                    candidate.id,
                    "HTML fetch returned ${response.status.value}, marking as checked",
                )
                db.entry.updateOgImageChecked(true, candidate.id)
                return false
            }
            response.bodyAsText()
        } catch (t: Throwable) {
            appendLog(candidate.id, "Transient failure fetching HTML (${t.message}); will retry")
            return false
        }

        val imageUrl = Ksoup.parse(html)
            .select("meta[property=\"og:image\"]")
            .firstOrNull()
            ?.attr("content")
            .orEmpty()

        if (imageUrl.isBlank()) {
            appendLog(candidate.id, "No og:image meta tag found, marking as checked")
            db.entry.updateOgImageChecked(true, candidate.id)
            return false
        }

        appendLog(candidate.id, "Found OG image URL: $imageUrl")
        val size = try {
            imageSize(httpClient.get(proxiedUrl(imageUrl)).readBytes())
        } catch (t: Throwable) {
            appendLog(candidate.id, "Transient failure fetching OG image (${t.message}); will retry")
            return false
        }

        if (size == null) {
            appendLog(candidate.id, "Could not read OG image dimensions, marking as checked")
            db.entry.updateOgImageChecked(true, candidate.id)
            return false
        }

        db.entry.updateOgImage(
            extOgImageUrl = imageUrl,
            extOgImageWidth = size.first.toLong(),
            extOgImageHeight = size.second.toLong(),
            extOgImageFetchedAt = Clock.System.now(),
            id = candidate.id,
        )
        appendLog(candidate.id, "OG image stored (${size.first}x${size.second})")
        return true
    }

    private suspend fun appendLog(entryId: String, message: String) {
        val entry = db.entry.selectById(entryId) ?: return
        db.entry.updateOgLog(appendLogSync(entry.extOpenGraphImageLog, message), entryId)
    }

    private fun appendLogSync(existing: String, message: String): String {
        val array = runCatching { parseJson(existing).jsonArray }.getOrDefault(JsonArray(emptyList()))
        val entry = buildJsonObject {
            put("timestamp", Clock.System.now().toString())
            put("message", message)
        }
        return JsonArray((array + entry).takeLast(MAX_LOG_ENTRIES)).toString()
    }

    companion object {
        const val MAX_LOG_ENTRIES = 50

        /** How many unchecked entries to load per iteration. */
        const val BATCH_SIZE = 10L

        val GLOBAL_OFF_INTERVAL: Duration = 5.seconds
        val EMPTY_INTERVAL: Duration = 2.seconds
        val OFFLINE_INTERVAL: Duration = 5.seconds
    }
}
