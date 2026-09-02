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
    private val connectivityMonitor: ConnectivityMonitor,
    private val isForeground: () -> Boolean,
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
     * entries (the SQL query already excluded per-feed-hidden rows), then
     * either fetches them, or sleeps when nothing is pending.
     *
     * Exposed as `internal` so tests can drive it without the infinite
     * loop. The interesting decision is in [planOgImageFetch], which is
     * pure and tested directly without needing a `PlatformContext`.
     *
     * Skips entirely when the device has no validated internet
     * connection or when the app is in the background — both situations
     * produce a flood of `UnknownHostException` log entries from
     * Android throttling background network and from a network that has
     * dropped while the fetcher is mid-loop. The fetcher resumes
     * immediately when connectivity or foreground state returns.
     */
    internal suspend fun runOnce() {
        when (ogRunSkip(isOnline = connectivityMonitor.isOnline(), isForeground = isForeground())) {
            OgRunSkip.Offline -> delay(OFFLINE_INTERVAL)
            OgRunSkip.NotForeground -> delay(OFFLINE_INTERVAL)
            OgRunSkip.No -> runOnceBody()
        }
    }

    private suspend fun runOnceBody() {
        val conf = withContext(Dispatchers.IO) { db.conf.select() }
        val candidates = withContext(Dispatchers.IO) {
            db.entry.selectPendingOgImageEntries(BATCH_SIZE)
        }
        when (val plan = planOgImageFetch(conf, candidates)) {
            is OgFetchPlan.GlobalOff -> delay(GLOBAL_OFF_INTERVAL)
            is OgFetchPlan.Empty -> delay(EMPTY_INTERVAL)
            is OgFetchPlan.Fetch -> {
                for (candidate in plan.candidates) {
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
            if (isTransientNetwork(e)) {
                appendLog(
                    candidate.id,
                    "Transient failure fetching HTML: ${describe(e)}; will retry next iteration",
                )
                return false
            }
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
                val throwable = imageResult.throwable
                if (isTransientNetwork(throwable)) {
                    appendLog(
                        candidate.id,
                        "Transient failure fetching OG image: ${describe(throwable)}; will retry next iteration",
                    )
                    return false
                }
                appendLog(candidate.id, "Failed to fetch OG image: ${describe(throwable)}")
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
         * How many unchecked entries to load per iteration. The SQL
         * filter already excluded per-feed-hidden rows, so a batch is
         * always a string of work the fetcher can act on; small enough
         * that a single iteration can't pin Coil / OkHttp for too long.
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
         * How long to sleep when [runOnce] short-circuited because the
         * device has no validated network or the app is in the
         * background. Should be long enough that we don't hammer
         * `ConnectivityManager` while the user is offline or the OS is
         * throttling our background work, but short enough that we
         * resume fetching within a few seconds of Wi-Fi returning or
         * the user foregrounding the app.
         */
        val OFFLINE_INTERVAL: Duration = 5.seconds

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
         * Classifies network-level exceptions that almost certainly mean
         * "try again later" — a brief DNS blip or a stalled TCP connect
         * don't justify stamping `ext_og_image_checked = 1`, since that
         * locks the entry out of the queue forever (before this fix,
         * that was the source of the 2,000+ "checked but no image" rows
         * we kept finding). HTTP-level errors (4xx/5xx) and "no
         * og:image in HTML" failures still hit the permanent branch.
         */
        fun isTransientNetwork(t: Throwable): Boolean = when (t) {
            is java.net.UnknownHostException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
                -> true
            else -> false
        }

        /**
         * Pure gating decision. Given whether the device is online and
         * whether the app is in the foreground, decides whether [runOnce]
         * should short-circuit without consulting the DB at all. Tested
         * directly without any Coil/OkHttp/Context dependencies.
         */
        internal fun ogRunSkip(isOnline: Boolean, isForeground: Boolean): OgRunSkip =
            when {
                !isOnline -> OgRunSkip.Offline
                !isForeground -> OgRunSkip.NotForeground
                else -> OgRunSkip.No
            }

        /**
         * Pure gating decision. The SQL query already filtered out rows
         * whose feed explicitly hides preview images, so by the time we
         * reach this function every remaining candidate is eligible if
         * the global toggle is on. Tested directly without any
         * Coil/OkHttp/Context dependencies.
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
            return OgFetchPlan.Fetch(candidates)
        }
    }
}

/**
 * Short-circuit outcome produced by [OpenGraphImageFetcher.ogRunSkip].
 *
 * - [Offline]: device has no validated network — sleep, do nothing.
 * - [NotForeground]: app is in the background — sleep, do nothing,
 *   since Android may throttle our network even when it says we're
 *   online, which is what produced the "UnknownHostException" log
 *   noise in past releases.
 * - [No]: both gates pass — proceed with the normal planOgImageFetch
 *   flow.
 */
internal enum class OgRunSkip {
    Offline,
    NotForeground,
    No,
}

/**
 * What the fetcher should do on the next iteration. Pure value type;
 * produced by [OpenGraphImageFetcher.planOgImageFetch] and consumed by
 * the loop body.
 *
 * - [GlobalOff]: global toggle is off; sleep, do nothing.
 * - [Empty]: global on but no unchecked entries; sleep, do nothing.
 * - [Fetch]: at least one candidate passed both gates (the SQL query
 *   filters out per-feed-hidden rows before we get here); hand each
 *   one to the network path.
 */
internal sealed interface OgFetchPlan {
    object GlobalOff : OgFetchPlan
    object Empty : OgFetchPlan
    data class Fetch(val candidates: List<EntryTable.OgImageCandidate>) : OgFetchPlan
}
