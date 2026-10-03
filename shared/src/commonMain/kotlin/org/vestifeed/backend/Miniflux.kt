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
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.json.asJsonArray
import org.vestifeed.json.asJsonObject
import org.vestifeed.json.booleanOrNull
import org.vestifeed.json.getAsJsonArray
import org.vestifeed.json.longOrNull
import org.vestifeed.json.objectOrNull
import org.vestifeed.json.parseJson
import org.vestifeed.json.parseJsonObject
import org.vestifeed.json.stringOrNull
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.util.toInstant

open class Miniflux(
    val client: HttpClient,
    val baseUrl: Url,
    db: Database,
) : Backend(db) {

    private data class MinifluxFeed(
        val id: Long,
        val title: String,
        val feedUrl: String,
        val siteUrl: String,
        val categoryId: Long?,
    )

    data class MinifluxCategory(
        val id: Long,
        val title: String,
    )

    /**
     * A snapshot of one feed as the Miniflux API returned it: the local
     * projection, the links, and the upstream `category_id`. The category id
     * is opaque to the rest of the app — it is what we use to reconcile the
     * remote category list into local [TagTable] / [FeedTagTable] rows.
     */
    data class FreshFeed(
        val feed: FeedTable.Feed,
        val links: List<LinkTable.Link>,
        val categoryId: Long?,
    )

    private data class EntriesPayload(
        val total: Long,
        val entries: List<EntryJson>,
    )

    private data class EntryJson(
        val id: Long,
        val feed_id: Long,
        val status: String,
        val title: String,
        val url: String,
        val comments_url: String,
        val published_at: String,
        val created_at: String,
        val changed_at: String,
        val content: String,
        val author: String,
        val starred: Boolean,
        val enclosures: List<EntryEnclosureJson>?,
    )

    private data class EntryEnclosureJson(
        val id: Long,
        val user_id: Long,
        val entry_id: Long,
        val url: String,
        val mime_type: String,
        val size: Long,
    )

    /** Builds a `baseUrl`-relative endpoint URL. */
    private fun endpoint(vararg segments: String): Url =
        URLBuilder(baseUrl).apply { appendPathSegments(*segments) }.build()

    private suspend fun getFeed(id: Long): MinifluxFeed {
        // https://miniflux.app/docs/api.html#endpoint-get-feed
        val res = client.get(endpoint("feeds", id.toString()))
        return if (res.status.value == 200) {
            parseJsonObject(res.bodyAsText()).toMinifluxFeed()
        } else {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    override suspend fun addFeed(url: Url, categoryId: Long?): AddFeedResult {
        // https://miniflux.app/docs/api.html#endpoint-create-feed
        val args = buildJsonObject {
            put("feed_url", JsonPrimitive(url.toString()))
            if (categoryId != null) {
                put("category_id", JsonPrimitive(categoryId))
            }
        }
        val res = client.post(endpoint("feeds")) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        if (res.status.value == 201) {
            val body = parseJsonObject(res.bodyAsText())
            val feedId = body.longOrNull("feed_id") ?: 0L
            val (feed, links) = getFeed(feedId).toVestiFeed()
            return AddFeedResult(
                feed = feed,
                feedLinks = links,
                entries = emptyList(),
            )
        } else {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    final override suspend fun getFeeds(): List<FeedTable.Feed> {
        return getFeedsWithLinks().map { it.feed }
    }

    open suspend fun getFeedsWithLinks(): List<FreshFeed> {
        // https://miniflux.app/docs/api.html#endpoint-get-feeds
        val res = client.get(endpoint("feeds"))
        return if (res.status.value == 200) {
            parseJson(res.bodyAsText()).asJsonArray.map { it.asJsonObject }
                .map { it.toMinifluxFeed() }
                .map { fresh ->
                    val (feed, links) = fresh.toVestiFeed()
                    FreshFeed(feed = feed, links = links, categoryId = fresh.categoryId)
                }
        } else {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> {
        // https://miniflux.app/docs/api.html#endpoint-update-feed
        val args = buildJsonObject { put("title", JsonPrimitive(newTitle)) }
        val res = client.put(endpoint("feeds", feedId)) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        return if (res.status.isSuccess()) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("unexpected response code ${res.status.value}"))
        }
    }

    override suspend fun deleteFeed(feedId: String): Result<Unit> {
        // https://miniflux.app/docs/api.html#endpoint-remove-feed
        val res = client.delete(endpoint("feeds", feedId))
        return if (res.status.value == 204) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("unexpected response code ${res.status.value}"))
        }
    }

    override suspend fun sync(initial: Boolean) {
        val sync = MinifluxSync(this, db)
        sync.syncFeeds()
        sync.syncCategories()
        sync.syncEntries(initial = initial)
    }

    open suspend fun getUnreadEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        return fetchEntriesByFilter { offset, limit ->
            URLBuilder(baseUrl).apply {
                appendPathSegments("entries")
                parameters.append("status", "unread")
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }.build()
        }
    }

    open suspend fun getStarredEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        return fetchEntriesByFilter { offset, limit ->
            URLBuilder(baseUrl).apply {
                appendPathSegments("entries")
                parameters.append("starred", "1")
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }.build()
        }
    }

    /**
     * Walks the `/v1/entries` endpoint in [MAX_PAGE_SIZE] pages until the
     * server reports every entry matching [urlBuilder]. The Miniflux API
     * caps `limit` at 1000 (rejects larger values with HTTP 400), so a
     * single request never returns more than that many rows even when the
     * caller asks for an "unlimited" page. The server terminates the walk
     * naturally by returning a short final page; if for some reason the
     * final page is also full we additionally guard against an infinite
     * loop using the `total` field the server reports on every response.
     */
    private suspend fun fetchEntriesByFilter(
        urlBuilder: (offset: Long, limit: Long) -> Url,
    ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        val collected = mutableListOf<Pair<EntryTable.Entry, List<LinkTable.Link>>>()
        var offset = 0L
        while (true) {
            val res = client.get(urlBuilder(offset, MAX_PAGE_SIZE))
            if (!res.status.isSuccess()) {
                throw IOException("http request failed with response code ${res.status.value}")
            }
            val payload = parseJsonObject(res.bodyAsText()).toEntriesPayload()
            val page = payload.entries.map { it.toEntry() }
            collected += page
            if (page.size < MAX_PAGE_SIZE.toInt()) break
            if (payload.total > 0 && collected.size >= payload.total) break
            offset += MAX_PAGE_SIZE
        }
        return collected
    }

    open suspend fun getEntriesChangedAfter(
        changedAfter: Instant,
        limit: Long,
    ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        // https://miniflux.app/docs/api.html#endpoint-get-entries
        val url = URLBuilder(baseUrl).apply {
            appendPathSegments("entries")
            parameters.append("changed_after", changedAfter.epochSeconds.toString())
            parameters.append("limit", limit.toString())
            // The Miniflux API defaults to the user's preferred sort order
            // (e.g. `published_at desc`), which means `changed_after` pagination
            // skips entries whose `published_at` is older even though their
            // `changed_at` is newer than the cursor. Pin the order to
            // `changed_at asc` so the cursor walks the change log chronologically.
            parameters.append("order", "changed_at")
            parameters.append("direction", "asc")
        }.build()
        val res = client.get(url)
        return if (res.status.isSuccess()) {
            val payload = parseJsonObject(res.bodyAsText()).toEntriesPayload()
            payload.entries.map { it.toEntry() }
        } else {
            throw IOException("http request failed with response code ${res.status.value}")
        }
    }

    open suspend fun markEntriesAsRead(entriesIds: List<String>, read: Boolean) {
        // https://miniflux.app/docs/api.html#endpoint-update-entries
        val args = buildJsonObject {
            put(
                "entry_ids",
                buildJsonArray { entriesIds.forEach { add(JsonPrimitive(it.toLong())) } },
            )
            put("status", JsonPrimitive(if (read) "read" else "unread"))
        }
        val res = client.put(endpoint("entries")) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        if (!res.status.isSuccess() || res.status.value != 204) {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    open suspend fun markEntriesAsBookmarked(
        entries: List<EntryTable.EntryWithoutContent>,
        bookmarked: Boolean,
    ) {
        // https://miniflux.app/docs/api.html#endpoint-update-entry
        entries.forEach { entry ->
            val rawRes = client.put(endpoint("entries", entry.id, "bookmark"))
            if (!rawRes.status.isSuccess()) {
                throw IOException("http request failed with response code ${rawRes.status.value}")
            }
        }
    }

    open suspend fun getCategories(): List<MinifluxCategory> {
        // https://miniflux.app/docs/api.html#endpoint-get-categories
        val res = client.get(endpoint("categories"))
        return if (res.status.value == 200) {
            parseJson(res.bodyAsText()).asJsonArray
                .map { it.asJsonObject.toMinifluxCategory() }
        } else {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    open suspend fun createCategory(title: String): MinifluxCategory {
        // https://miniflux.app/docs/api.html#endpoint-create-category
        val args = buildJsonObject { put("title", JsonPrimitive(title)) }
        val res = client.post(endpoint("categories")) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        return if (res.status.value == 201) {
            parseJsonObject(res.bodyAsText()).toMinifluxCategory()
        } else {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    open suspend fun findOrCreateCategory(title: String): MinifluxCategory {
        val existing = getCategories().firstOrNull { it.title == title }
        if (existing != null) return existing
        return createCategory(title)
    }

    open suspend fun updateCategory(id: Long, title: String): MinifluxCategory {
        // https://miniflux.app/docs/api.html#endpoint-update-category
        val args = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
        }
        val res = client.put(endpoint("categories", id.toString())) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        return if (res.status.value == 201) {
            parseJsonObject(res.bodyAsText()).toMinifluxCategory()
        } else {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    open suspend fun deleteCategory(id: Long): Result<Unit> {
        // https://miniflux.app/docs/api.html#endpoint-delete-category
        val res = client.delete(endpoint("categories", id.toString()))
        return if (res.status.value == 204) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("unexpected response code ${res.status.value}"))
        }
    }

    open suspend fun moveFeedToCategory(feedId: String, categoryId: Long) {
        // https://miniflux.app/docs/api.html#endpoint-update-feed
        val args = buildJsonObject { put("category_id", JsonPrimitive(categoryId)) }
        val res = client.put(endpoint("feeds", feedId)) {
            contentType(ContentType.Application.Json)
            setBody(args.toString())
        }
        if (!res.status.isSuccess()) {
            throw IOException("unexpected response code ${res.status.value}")
        }
    }

    private fun EntryJson.toEntry(): Pair<EntryTable.Entry, List<LinkTable.Link>> {
        val links = mutableListOf<LinkTable.Link>()

        if (url.isNotBlank()) {
            links += LinkTable.Link(
                id = null,
                feedId = null,
                entryId = id.toString(),
                href = url,
                rel = AtomLinkRel.Alternate,
                type = "text/html",
                hreflang = null,
                title = null,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            )
        }

        enclosures?.forEach { enclosure ->
            links += LinkTable.Link(
                id = null,
                feedId = null,
                entryId = id.toString(),
                href = enclosure.url,
                rel = AtomLinkRel.Enclosure,
                type = enclosure.mime_type,
                hreflang = null,
                title = null,
                length = enclosure.size,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            )
        }

        return Pair(
            EntryTable.Entry(
                contentType = "html",
                contentSrc = "",
                contentText = content,
                summary = null,
                id = id.toString(),
                feedId = feed_id.toString(),
                title = title,
                published = published_at.toInstant(),
                updated = changed_at.toInstant(),
                authorName = author,
                extRead = status == "read",
                extReadSynced = true,
                extBookmarked = starred,
                extBookmarkedSynced = true,
                extCommentsUrl = comments_url,
                extOpenGraphImageChecked = false,
                extOpenGraphImageUrl = "",
                extOpenGraphImageWidth = 0,
                extOpenGraphImageHeight = 0,
                extOpenGraphImageFetchedAt = null,
                extOpenGraphImageLog = "[]",
            ), links
        )
    }

    private fun JsonObject.toEntriesPayload(): EntriesPayload {
        val total = longOrNull("total") ?: 0
        val entriesArray = getAsJsonArray("entries") ?: JsonArray(emptyList())
        val entries = entriesArray.map { it.asJsonObject.toEntryJson() }
        return EntriesPayload(
            total = total,
            entries = entries,
        )
    }

    private fun JsonObject.toEntryJson(): EntryJson {
        return EntryJson(
            id = longOrNull("id") ?: 0L,
            feed_id = longOrNull("feed_id") ?: 0L,
            status = stringOrNull("status") ?: "",
            title = stringOrNull("title") ?: "",
            url = stringOrNull("url") ?: "",
            comments_url = stringOrNull("comments_url") ?: "",
            published_at = stringOrNull("published_at") ?: "",
            created_at = stringOrNull("created_at") ?: "",
            changed_at = stringOrNull("changed_at") ?: "",
            content = stringOrNull("content") ?: "",
            author = stringOrNull("author") ?: "",
            starred = booleanOrNull("starred") ?: false,
            enclosures = getAsJsonArray("enclosures")
                ?.map { it.asJsonObject.toEntryEnclosureJson() },
        )
    }

    private fun JsonObject.toEntryEnclosureJson(): EntryEnclosureJson {
        return EntryEnclosureJson(
            id = longOrNull("id") ?: 0L,
            user_id = longOrNull("user_id") ?: 0L,
            entry_id = longOrNull("entry_id") ?: 0L,
            url = stringOrNull("url") ?: "",
            mime_type = stringOrNull("mime_type") ?: "",
            size = longOrNull("size") ?: 0L,
        )
    }

    private fun JsonObject.toMinifluxFeed(): MinifluxFeed {
        return MinifluxFeed(
            id = longOrNull("id") ?: 0L,
            title = stringOrNull("title") ?: "",
            feedUrl = stringOrNull("feed_url") ?: "",
            siteUrl = stringOrNull("site_url") ?: "",
            categoryId = objectOrNull("category")?.longOrNull("id"),
        )
    }

    private fun JsonObject.toMinifluxCategory(): MinifluxCategory {
        return MinifluxCategory(
            id = longOrNull("id") ?: 0L,
            title = stringOrNull("title") ?: "",
        )
    }

    private fun MinifluxFeed.toVestiFeed(): Pair<FeedTable.Feed, List<LinkTable.Link>> {
        val feedId = id.toString()

        val selfLink = LinkTable.Link(
            id = null,
            feedId = feedId,
            entryId = null,
            href = feedUrl,
            rel = AtomLinkRel.Self,
            type = null,
            hreflang = null,
            title = null,
            length = null,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        )
        val alternateLink = LinkTable.Link(
            id = null,
            feedId = feedId,
            entryId = null,
            href = siteUrl,
            rel = AtomLinkRel.Alternate,
            type = "text/html",
            hreflang = null,
            title = null,
            length = null,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        )
        val feed = FeedTable.Feed(
            id = feedId,
            title = title,
            extOpenEntriesInBrowser = false,
            extBlockedWords = "",
            extShowPreviewImages = null,
        )
        return Pair(feed, listOf(selfLink, alternateLink))
    }

    companion object {
        const val API_PATH = "/v1/"
        /**
         * Miniflux rejects `limit` values greater than 1000 with HTTP 400,
         * so any paginated walk of `/v1/entries` has to use this page size.
         * The user-configurable `entries_per_page` (default 100) only affects
         * the web UI and the Miniflux app, not this client.
         */
        const val MAX_PAGE_SIZE = 1000L
    }
}
