package org.vestifeed.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.IOException
import org.vestifeed.json.asJsonArray
import org.vestifeed.json.asJsonObject
import org.vestifeed.json.asLong
import org.vestifeed.json.booleanOrNull
import org.vestifeed.json.getAsJsonArray
import org.vestifeed.json.longOrNull
import org.vestifeed.json.objectOrNull
import org.vestifeed.json.parseJson
import org.vestifeed.json.parseJsonObject
import org.vestifeed.json.stringOrNull
import org.vestifeed.util.toInstant

/**
 * [MinifluxApi] over HTTP, using the shared Ktor client.
 *
 * [client] must be built by [minifluxHttpClient] (or an equivalent): the
 * per-request `X-Auth-Token` header comes from it, and its response validator is
 * what turns a non-2xx status into [MinifluxHttpException] before a call here
 * sees the response. A 401 additionally reports [org.vestifeed.auth.AuthEvents].
 *
 * @param baseUrl the server's API root, e.g. `https://reader.example.com/v1/`
 */
class MinifluxHttpApi(
    private val client: HttpClient,
    private val baseUrl: Url,
) : MinifluxApi {

    /** Builds a `baseUrl`-relative endpoint URL. */
    private fun endpoint(vararg segments: String): Url =
        URLBuilder(baseUrl).apply { appendPathSegments(*segments) }.build()

    private fun unexpectedStatus(status: Int) =
        IOException("unexpected response code $status")

    // ---- Feeds -------------------------------------------------------------

    override suspend fun getFeeds(): List<MinifluxFeed> {
        // https://miniflux.app/docs/api.html#endpoint-get-feeds
        val res = client.get(endpoint("feeds"))
        if (res.status.value != 200) throw unexpectedStatus(res.status.value)
        return parseJson(res.bodyAsText()).asJsonArray
            .map { it.asJsonObject.toMinifluxFeed() }
    }

    override suspend fun addFeed(feedUrl: String, categoryId: Long?): MinifluxFeed {
        // https://miniflux.app/docs/api.html#endpoint-create-feed
        val args = buildJsonObject {
            put("feed_url", JsonPrimitive(feedUrl))
            if (categoryId != null) {
                put("category_id", JsonPrimitive(categoryId))
            }
        }
        val res = client.post(endpoint("feeds")) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        if (res.status.value != 201) throw unexpectedStatus(res.status.value)
        val feedId = parseJsonObject(res.bodyAsText()).longOrNull("feed_id") ?: 0L
        return getFeed(feedId)
    }

    /** The create endpoint returns only an ID, so the feed is read back. */
    private suspend fun getFeed(id: Long): MinifluxFeed {
        // https://miniflux.app/docs/api.html#endpoint-get-feed
        val res = client.get(endpoint("feeds", id.toString()))
        if (res.status.value != 200) throw unexpectedStatus(res.status.value)
        return parseJsonObject(res.bodyAsText()).toMinifluxFeed()
    }

    override suspend fun updateFeedTitle(feedId: String, newTitle: String) {
        // https://miniflux.app/docs/api.html#endpoint-update-feed
        val args = buildJsonObject { put("title", JsonPrimitive(newTitle)) }
        val res = client.put(endpoint("feeds", feedId)) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        if (!res.status.isSuccess()) throw unexpectedStatus(res.status.value)
    }

    override suspend fun deleteFeed(feedId: String) {
        // https://miniflux.app/docs/api.html#endpoint-remove-feed
        val res = client.delete(endpoint("feeds", feedId))
        if (res.status.value != 204) throw unexpectedStatus(res.status.value)
    }

    // ---- Entries -----------------------------------------------------------

    override suspend fun getUnreadEntries(): List<MinifluxEntry> =
        fetchEntriesByFilter { offset, limit ->
            URLBuilder(baseUrl).apply {
                appendPathSegments("entries")
                parameters.append("status", "unread")
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }.build()
        }

    override suspend fun getStarredEntries(): List<MinifluxEntry> =
        fetchEntriesByFilter { offset, limit ->
            URLBuilder(baseUrl).apply {
                appendPathSegments("entries")
                parameters.append("starred", "1")
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }.build()
        }

    /**
     * Walks `/v1/entries` in [MinifluxApi.MAX_PAGE_SIZE] pages until the server
     * reports every entry matching [urlBuilder].
     *
     * The API caps `limit` at 1000, so a single request never returns more rows
     * than that even when the caller asks for an "unlimited" page. A short final
     * page ends the walk; the `total` field is an additional guard against
     * looping forever on a server that keeps answering with full pages.
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-entries>
     */
    private suspend fun fetchEntriesByFilter(
        urlBuilder: (offset: Long, limit: Long) -> Url,
    ): List<MinifluxEntry> {
        val collected = mutableListOf<MinifluxEntry>()
        var offset = 0L
        while (true) {
            val res = client.get(urlBuilder(offset, MinifluxApi.MAX_PAGE_SIZE))
            if (!res.status.isSuccess()) {
                throw IOException("http request failed with response code ${res.status.value}")
            }
            val payload = parseJsonObject(res.bodyAsText()).toEntriesPayload()
            collected += payload.entries
            if (payload.entries.size < MinifluxApi.MAX_PAGE_SIZE.toInt()) break
            if (payload.total > 0 && collected.size >= payload.total) break
            offset += MinifluxApi.MAX_PAGE_SIZE
        }
        return collected
    }

    override suspend fun getEntriesChangedAfter(
        changedAfter: Instant,
        limit: Long,
    ): List<MinifluxEntry> {
        // https://miniflux.app/docs/api.html#endpoint-get-entries
        val url = URLBuilder(baseUrl).apply {
            appendPathSegments("entries")
            parameters.append("changed_after", changedAfter.epochSeconds.toString())
            parameters.append("limit", limit.toString())
            // The API defaults to the user's preferred sort order (e.g.
            // `published_at desc`), which means `changed_after` pagination skips
            // entries whose `published_at` is older even though their
            // `changed_at` is newer than the cursor. Pin the order to
            // `changed_at asc` so the cursor walks the change log chronologically.
            parameters.append("order", "changed_at")
            parameters.append("direction", "asc")
        }.build()
        val res = client.get(url)
        if (!res.status.isSuccess()) {
            throw IOException("http request failed with response code ${res.status.value}")
        }
        return parseJsonObject(res.bodyAsText()).toEntriesPayload().entries
    }

    override suspend fun getEntryIds(): Set<Long>? {
        val ids = mutableSetOf<Long>()
        var offset = 0L
        while (true) {
            val url = URLBuilder(baseUrl).apply {
                appendPathSegments("entries", "ids")
                parameters.append("limit", MinifluxApi.ID_PAGE_SIZE.toString())
                parameters.append("offset", offset.toString())
            }.build()
            val res = try {
                client.get(url)
            } catch (e: MinifluxHttpException) {
                // With the production client the response validator throws on
                // any non-2xx, so the status is only visible on the exception.
                if (e.statusCode == 404) return null
                throw e
            }
            if (res.status.value == 404) return null
            if (!res.status.isSuccess()) {
                throw IOException("unexpected response code ${res.status.value}")
            }
            val payload = parseJsonObject(res.bodyAsText())
            // A 200 without the expected field is not a reliable "no entries"
            // answer; treat it as unsupported rather than pruning everything.
            val page = payload.getAsJsonArray("entry_ids") ?: return null
            page.forEach { ids.add(it.asLong) }
            val total = payload.longOrNull("total") ?: 0L
            if (page.size < MinifluxApi.ID_PAGE_SIZE.toInt() || (total > 0 && ids.size >= total)) break
            offset += MinifluxApi.ID_PAGE_SIZE - MinifluxApi.ID_PAGE_OVERLAP
        }
        return ids
    }

    override suspend fun markEntriesAsRead(entryIds: List<String>, read: Boolean) {
        // https://miniflux.app/docs/api.html#endpoint-update-entries
        val args = buildJsonObject {
            put(
                "entry_ids",
                buildJsonArray { entryIds.forEach { add(JsonPrimitive(it.toLong())) } },
            )
            put("status", JsonPrimitive(if (read) "read" else "unread"))
        }
        val res = client.put(endpoint("entries")) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        if (!res.status.isSuccess() || res.status.value != 204) {
            throw unexpectedStatus(res.status.value)
        }
    }

    override suspend fun markEntriesAsBookmarked(entryIds: List<String>, bookmarked: Boolean) {
        if (entryIds.isEmpty()) return

        if (setEntriesStarred(entryIds, bookmarked)) return

        // Legacy path: toggle, but only when the server disagrees with us.
        entryIds.forEach { entryId ->
            if (currentStarred(entryId) == bookmarked) return@forEach
            val res = try {
                client.put(endpoint("entries", entryId, "bookmark"))
            } catch (e: MinifluxHttpException) {
                // The entry is gone; there is nothing left to update.
                if (e.statusCode == 404) return@forEach
                throw e
            }
            if (res.status.value == 404) return@forEach
            if (!res.status.isSuccess()) {
                throw IOException("http request failed with response code ${res.status.value}")
            }
        }
    }

    /**
     * Applies [starred] to [entryIds] with the bulk endpoint. Returns false when
     * the server does not understand the `starred` field, which is how Miniflux
     * < v2.3.2 answers: it validates that a `status` is present and rejects the
     * request with HTTP 400.
     */
    private suspend fun setEntriesStarred(entryIds: List<String>, starred: Boolean): Boolean {
        val args = buildJsonObject {
            put(
                "entry_ids",
                buildJsonArray { entryIds.forEach { add(JsonPrimitive(it.toLong())) } },
            )
            put("starred", JsonPrimitive(starred))
        }
        val res = try {
            client.put(endpoint("entries")) {
                contentType(ContentType.Application.Json)
                setBody(args.toString())
            }
        } catch (e: MinifluxHttpException) {
            if (e.statusCode == 400) return false
            throw e
        }
        if (res.status.value == 400) return false
        if (!res.status.isSuccess() || res.status.value != 204) {
            throw unexpectedStatus(res.status.value)
        }
        return true
    }

    /** The server's current starred state for [entryId], or null when unknown. */
    private suspend fun currentStarred(entryId: String): Boolean? {
        val res = try {
            client.get(endpoint("entries", entryId))
        } catch (e: MinifluxHttpException) {
            // A removed entry cannot be updated; a toggle attempt is skipped.
            if (e.statusCode == 404) return null
            throw e
        }
        if (!res.status.isSuccess()) return null
        return runCatching {
            parseJsonObject(res.bodyAsText()).booleanOrNull("starred")
        }.getOrNull()
    }

    // ---- Categories --------------------------------------------------------

    override suspend fun getCategories(): List<MinifluxCategory> {
        // https://miniflux.app/docs/api.html#endpoint-get-categories
        val res = client.get(endpoint("categories"))
        if (res.status.value != 200) throw unexpectedStatus(res.status.value)
        return parseJson(res.bodyAsText()).asJsonArray
            .map { it.asJsonObject.toMinifluxCategory() }
    }

    // ---- JSON --------------------------------------------------------------

    private class EntriesPayload(val total: Long, val entries: List<MinifluxEntry>)

    private fun JsonObject.toEntriesPayload(): EntriesPayload {
        val total = longOrNull("total") ?: 0
        val entriesArray = getAsJsonArray("entries") ?: JsonArray(emptyList())
        return EntriesPayload(
            total = total,
            entries = entriesArray.map { it.asJsonObject.toMinifluxEntry() },
        )
    }

    private fun JsonObject.toMinifluxEntry(): MinifluxEntry {
        val enclosures = getAsJsonArray("enclosures")
            ?.map { it.asJsonObject.toMinifluxEnclosure() }
            .orEmpty()
        return MinifluxEntry(
            id = longOrNull("id") ?: 0L,
            feedId = longOrNull("feed_id") ?: 0L,
            status = MinifluxEntryStatus.fromWire(stringOrNull("status").orEmpty()),
            title = stringOrNull("title").orEmpty(),
            url = stringOrNull("url").orEmpty(),
            commentsUrl = stringOrNull("comments_url").orEmpty(),
            publishedAt = stringOrNull("published_at").orEmpty().toInstant(),
            changedAt = stringOrNull("changed_at").orEmpty().toInstant(),
            content = stringOrNull("content").orEmpty(),
            author = stringOrNull("author").orEmpty(),
            starred = booleanOrNull("starred") ?: false,
            enclosures = enclosures,
        )
    }

    private fun JsonObject.toMinifluxEnclosure(): MinifluxEnclosure = MinifluxEnclosure(
        id = longOrNull("id") ?: 0L,
        url = stringOrNull("url").orEmpty(),
        mimeType = stringOrNull("mime_type").orEmpty(),
        size = longOrNull("size") ?: 0L,
    )

    private fun JsonObject.toMinifluxFeed(): MinifluxFeed = MinifluxFeed(
        id = longOrNull("id") ?: 0L,
        title = stringOrNull("title").orEmpty(),
        feedUrl = stringOrNull("feed_url").orEmpty(),
        siteUrl = stringOrNull("site_url").orEmpty(),
        categoryId = objectOrNull("category")?.longOrNull("id"),
    )

    private fun JsonObject.toMinifluxCategory(): MinifluxCategory = MinifluxCategory(
        id = longOrNull("id") ?: 0L,
        title = stringOrNull("title").orEmpty(),
    )
}
