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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.entries.EntryRowMapper
import org.vestifeed.http.await
import org.vestifeed.parser.AtomLinkRel
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OpenGraphImageFetcher(
    private val db: Database,
    private val imageContext: PlatformContext,
) {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Long-lived background loop. Each iteration delegates to [runOnce],
     * which is what tests exercise. The loop only stops when the
     * coroutine running it is cancelled.
     */
    suspend fun fetchAndWatch() {
        while (coroutineContext.isActive) {
            runOnce()
        }
    }

    /**
     * One tick of the watcher. Reads conf, fetches a batch of unchecked
     * entries, partitions them by eligibility, then either fetches the
     * eligible ones, logs+skips the ineligible ones, or sleeps when
     * nothing is pending.
     *
     * Exposed as `internal` so tests can drive it without the infinite
     * loop. The interesting decision is in [planOgImageFetch], which is
     * pure and tested directly without needing a `PlatformContext`.
     */
    internal suspend fun runOnce() {
        val conf = withContext(Dispatchers.IO) { db.conf.select() }
        val candidates = withContext(Dispatchers.IO) {
            db.entry.selectPendingOgImageEntries(BATCH_SIZE)
        }
        when (val plan = planOgImageFetch(conf, candidates)) {
            is OgFetchPlan.GlobalOff -> delay(GLOBAL_OFF_INTERVAL)
            is OgFetchPlan.Empty -> delay(EMPTY_INTERVAL)
            is OgFetchPlan.AllSkipped -> {
                for (candidate in plan.candidates) {
                    appendLog(
                        candidate.id,
                        "Skipping OG image fetch: preview images disabled for feed '${candidate.title}'",
                    )
                }
                delay(ALL_SKIPPED_INTERVAL)
            }
            is OgFetchPlan.ToFetch -> {
                for (candidate in plan.skipped) {
                    appendLog(
                        candidate.id,
                        "Skipping OG image fetch: preview images disabled for feed '${candidate.title}'",
                    )
                }
                for (candidate in plan.toFetch) {
                    fetchOneEntry(candidate)
                }
            }
        }
    }

    private suspend fun fetchOneEntry(candidate: EntryTable.OgImageCandidate): Boolean {
        appendLog(candidate.id, "Attempting to fetch OG image for entry ${candidate.id}")

        val links = withContext(Dispatchers.IO) {
            db.link.selectByEntryId(candidate.id)
        }
        val htmlLink =
            links.firstOrNull { it.rel is AtomLinkRel.Alternate && it.type == "text/html" }
                ?: links.firstOrNull { it.rel is AtomLinkRel.Alternate }
        if (htmlLink == null) {
            appendLog(candidate.id, "No HTML alternate link found, marking as checked")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, candidate.id)
            }
            return false
        }

        appendLog(candidate.id, "Fetching HTML from ${htmlLink.href}")

        val htmlLinkResponse = try {
            httpClient.newCall(Request.Builder().url(htmlLink.href).build()).await()
        } catch (e: Throwable) {
            appendLog(candidate.id, "Failed to fetch HTML: ${describe(e)}")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, candidate.id)
            }
            return false
        }

        if (!htmlLinkResponse.isSuccessful) {
            appendLog(
                candidate.id,
                "HTML fetch returned unsuccessful status ${htmlLinkResponse.code}, marking as checked",
            )
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, candidate.id)
            }
            return false
        }

        val html = try {
            htmlLinkResponse.body.string()
        } catch (e: Throwable) {
            appendLog(candidate.id, "Failed to read HTML body: ${describe(e)}")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, candidate.id)
            }
            return false
        }

        appendLog(candidate.id, "Parsing HTML and looking for og:image meta tags")

        val metas = Jsoup.parse(html).select("meta[property=\"og:image\"]")
        val imageUrl = metas.firstOrNull()?.attr("content") ?: ""

        if (imageUrl.isBlank()) {
            appendLog(candidate.id, "No og:image meta tag found, marking as checked")
            withContext(Dispatchers.IO) {
                db.entry.updateOgImageChecked(true, candidate.id)
            }
            return false
        }

        appendLog(candidate.id, "Found OG image URL: $imageUrl")

        val imageRequest = ImageRequest.Builder(imageContext)
            .data(imageUrl)
            .size(800)
            .build()

        val bitmap = when (val imageResult = imageContext.imageLoader.execute(imageRequest)) {
            is SuccessResult -> {
                imageResult.image.toBitmap()
            }

            is ErrorResult -> {
                appendLog(candidate.id, "Failed to fetch OG image: ${describe(imageResult.throwable)}")
                withContext(Dispatchers.IO) {
                    db.entry.updateOgImageChecked(true, candidate.id)
                }
                return false
            }
        }

        appendLog(
            candidate.id,
            "OG image downloaded successfully (${bitmap.width}x${bitmap.height})",
        )

        db.entry.updateOgImage(
            extOgImageUrl = imageUrl,
            extOgImageWidth = bitmap.width.toLong(),
            extOgImageHeight = bitmap.height.toLong(),
            extOgImageFetchedAt = OffsetDateTime.now(),
            id = candidate.id,
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

        /**
         * How many unchecked entries to load per iteration. Larger than 1
         * so a batch that is entirely hidden by per-feed settings doesn't
         * tight-loop on the same row; small enough that a single iteration
         * can't pin Coil / OkHttp for too long.
         */
        const val BATCH_SIZE = 10L

        /**
         * How long to sleep between iterations when the global
         * "show preview images" toggle is off. Capped low enough that
         * flicking the toggle on in Settings is reflected quickly, but
         * high enough that we don't hammer SQLite with `SELECT` on every
         * loop while the user has the toggle disabled.
         */
        val GLOBAL_OFF_INTERVAL: Duration = 5.seconds

        /**
         * How long to sleep when there is nothing pending at all. The
         * entries view polls the DB every 5s for fresh OG images, so we
         * wake up faster than that to keep that watermark useful.
         */
        val EMPTY_INTERVAL: Duration = 1.seconds

        /**
         * How long to sleep when every candidate in the batch was
         * hidden by per-feed settings — prevents tight-looping on the
         * same skipped rows until the user flips a feed-level toggle.
         */
        val ALL_SKIPPED_INTERVAL: Duration = 2.seconds

        /**
         * Three-state resolution: an explicit per-feed value wins over the
         * global value; `null` means "follow settings" and falls back to
         * the global value. Delegates to [EntryRowMapper.resolveShowImage]
         * so the gating decision and the render decision can never drift
         * apart.
         */
        fun shouldFetchOgImage(perFeed: Boolean?, global: Boolean): Boolean =
            EntryRowMapper.resolveShowImage(perFeed = perFeed, global = global)

        /**
         * Pure gating decision. Given the current global setting and a
         * batch of pending candidates (each carrying its feed's
         * per-feed setting), decides what the fetcher should do next.
         * Tested directly without any Coil/OkHttp/Context dependencies.
         */
        internal fun planOgImageFetch(
            conf: ConfTable.Conf,
            candidates: List<EntryTable.OgImageCandidate>,
        ): OgFetchPlan {
            if (!shouldFetchOgImage(perFeed = null, global = conf.showPreviewImages)) {
                return OgFetchPlan.GlobalOff
            }
            if (candidates.isEmpty()) {
                return OgFetchPlan.Empty
            }
            val (eligible, skipped) = candidates.partition { candidate ->
                shouldFetchOgImage(
                    perFeed = candidate.feedShowPreviewImages,
                    global = conf.showPreviewImages,
                )
            }
            return when {
                eligible.isEmpty() -> OgFetchPlan.AllSkipped(skipped)
                else -> OgFetchPlan.ToFetch(toFetch = eligible, skipped = skipped)
            }
        }
    }
}

/**
 * What the fetcher should do on the next iteration. Pure value type;
 * produced by [OpenGraphImageFetcher.planOgImageFetch] and consumed by
 * the loop body.
 *
 * - [GlobalOff]: global toggle is off; sleep, do nothing.
 * - [Empty]: global on but no unchecked entries; sleep, do nothing.
 * - [AllSkipped]: global on, candidates exist, but every one is hidden
 *   by per-feed settings; log each and sleep. Crucially, none of these
 *   are marked `ext_og_image_checked = 1`, so they reappear once the
 *   per-feed toggle is flipped.
 * - [ToFetch]: at least one candidate passed both gates; fetch the
 *   eligible ones, log+skip the rest.
 */
internal sealed interface OgFetchPlan {
    object GlobalOff : OgFetchPlan
    object Empty : OgFetchPlan
    data class AllSkipped(val candidates: List<EntryTable.OgImageCandidate>) : OgFetchPlan
    data class ToFetch(
        val toFetch: List<EntryTable.OgImageCandidate>,
        val skipped: List<EntryTable.OgImageCandidate>,
    ) : OgFetchPlan
}
