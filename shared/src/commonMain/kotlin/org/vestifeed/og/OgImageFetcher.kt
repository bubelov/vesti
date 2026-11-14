package org.vestifeed.og

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.http.vestiFetchHttpClient
import org.vestifeed.json.parseJson
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.platform.proxiedUrl

/**
 * Resolves each entry's OpenGraph image: the article page's `og:image`. Only
 * the URL is stored; the image bytes are fetched once, lazily, by the UI's
 * image loader (Coil), which caches them. Downloading them here just to measure
 * the size would download every preview image twice for a value nothing reads.
 *
 * Shares [OgImagePlanning]'s pure gating with every host. Feed/article requests
 * go through [proxiedUrl], so on the browser they are relayed by the
 * same-origin proxy.
 */
class OgImageFetcher(
    private val db: Database,
    userAgent: String,
    private val httpClient: HttpClient = vestiFetchHttpClient(
        userAgent = userAgent,
        // The old Android fetcher capped the whole call at 10s; one silent
        // server must not stall the batch for every later entry.
        requestTimeoutMillis = 15_000,
    ),
    private val isOnline: () -> Boolean = { true },
    private val isForeground: () -> Boolean = { true },
) {

    /** Long-lived loop; stops when its coroutine is cancelled. */
    suspend fun fetchAndWatch() {
        // The host may start this before its UI has connected the database.
        db.connect()
        while (coroutineContext.isActive) {
            try {
                runOnce()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // A single bad iteration (a page the parser chokes on, a
                // transient database error) must not stop preview fetching for
                // the rest of the session: the hosts wrap this call in
                // runCatching, so an escaping exception would be silent.
                delay(ERROR_INTERVAL)
            }
        }
    }

    suspend fun runOnce() {
        when (OgImagePlanning.ogRunSkip(isOnline(), isForeground())) {
            OgRunSkip.Offline -> delay(OFFLINE_INTERVAL)
            // Preview images are supplementary: a backgrounded app keeps the
            // loop alive but stops spending network and battery on images
            // nobody is looking at. The pause stays short enough to notice the
            // reader coming back.
            OgRunSkip.NotForeground -> delay(NOT_FOREGROUND_INTERVAL)
            OgRunSkip.No -> runOnceBody()
        }
    }

    private suspend fun runOnceBody() {
        val conf = db.conf.select()
        val candidates = db.entry.selectPendingOgImageEntries(BATCH_SIZE)
        when (val plan = OgImagePlanning.planOgImageFetch(conf, candidates)) {
            OgFetchPlan.GlobalOff -> delay(GLOBAL_OFF_INTERVAL)
            OgFetchPlan.Empty -> delay(EMPTY_INTERVAL)
            is OgFetchPlan.Fetch -> {
                // One HTTP page fetch per entry dominates the loop, so overlap
                // them: the DB serialises its own writes, and the network wait
                // is what a large backlog is spent on. Chunked so at most
                // FETCH_CONCURRENCY requests are in flight at once.
                plan.candidates.chunked(FETCH_CONCURRENCY).forEach { chunk ->
                    coroutineScope {
                        chunk.map { async { fetchOne(it) } }.awaitAll()
                    }
                }
            }
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
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return recordTransientFailure(candidate.id, "fetching HTML", t)
        }

        val imageUrl = try {
            Ksoup.parse(html)
                .select("meta[property=\"og:image\"]")
                .firstOrNull()
                ?.attr("content")
                .orEmpty()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // A page the parser cannot handle must not escape and kill the
            // whole loop; count it like any other transient failure so a
            // deterministically broken page is eventually abandoned.
            return recordTransientFailure(candidate.id, "parsing HTML", t)
        }

        if (imageUrl.isBlank()) {
            appendLog(candidate.id, "No og:image meta tag found, marking as checked")
            db.entry.updateOgImageChecked(true, candidate.id)
            return false
        }

        // Store only the URL: the display layer fetches and caches the bytes.
        db.entry.updateOgImage(
            extOgImageUrl = imageUrl,
            extOgImageFetchedAt = Clock.System.now(),
            id = candidate.id,
        )
        appendLog(candidate.id, "Found OG image URL: $imageUrl")
        return true
    }

    /**
     * Records a transient failure against [entryId] and gives up on the entry
     * once it has failed too many times, so a page that will never yield an
     * image stops being retried. Always returns false, so a `catch` block can
     * `return recordTransientFailure(...)` and keep the "this entry produced no
     * image" contract in one line.
     */
    private suspend fun recordTransientFailure(
        entryId: String,
        stage: String,
        cause: Throwable,
    ): Boolean {
        val log = appendLog(entryId, "Transient failure $stage (${cause.message}); will retry")
        val failures = OgImagePlanning.transientFailureCount(log)
        if (OgImagePlanning.shouldAbandonAfterTransientFailures(failures)) {
            appendLog(entryId, "Abandoning after $failures transient failures, marking as checked")
            db.entry.updateOgImageChecked(true, entryId)
        }
        return false
    }

    /**
     * Appends one line to the entry's fetch log and returns the new log. Reads
     * only the log column: loading the whole entry would drag the article body
     * across for a field that is a few JSON objects long, several times per
     * entry.
     */
    private suspend fun appendLog(entryId: String, message: String): String {
        val existing = db.entry.selectOgLog(entryId) ?: return ""
        val updated = appendLogSync(existing, message)
        db.entry.updateOgLog(updated, entryId)
        return updated
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

        /** How many entry pages to fetch concurrently within a batch. */
        const val FETCH_CONCURRENCY = 6

        val GLOBAL_OFF_INTERVAL: Duration = 5.seconds
        val EMPTY_INTERVAL: Duration = 2.seconds
        val OFFLINE_INTERVAL: Duration = 5.seconds

        /**
         * How long to idle while the app is in the background. Long enough to
         * keep the loop off the network and out of the battery, short enough
         * that a returning reader sees new previews soon after.
         */
        val NOT_FOREGROUND_INTERVAL: Duration = 1.minutes

        /** Pause after an iteration that failed unexpectedly, before retrying. */
        val ERROR_INTERVAL: Duration = 5.seconds
    }
}
