package org.vestifeed.backend

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.http.executeAsync
import org.vestifeed.parser.AtomLinkRel
import java.io.IOException
import java.time.OffsetDateTime

open class Miniflux(
    val client: OkHttpClient,
    val baseUrl: HttpUrl,
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

    private suspend fun getFeed(id: Long): MinifluxFeed {
        // https://miniflux.app/docs/api.html#endpoint-get-feed
        val req = Request.Builder().url(
            baseUrl.newBuilder().addPathSegment("feeds").addPathSegment(id.toString()).build()
        ).get().build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 200) {
            JsonParser.parseString(res.body.string()).asJsonObject.toMinifluxFeed()
        } else {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    override suspend fun addFeed(url: HttpUrl, categoryId: Long?): AddFeedResult {
        // https://miniflux.app/docs/api.html#endpoint-create-feed
        val args = JsonObject().apply {
            add("feed_url", JsonPrimitive(url.toString()))
            if (categoryId != null) {
                add("category_id", JsonPrimitive(categoryId))
            }
        }
        val req = Request.Builder().url(baseUrl.newBuilder().addPathSegment("feeds").build())
            .post(args.toString().toRequestBody(JSON)).build()
        val res = client.newCall(req).executeAsync()
        if (res.code == 201) {
            val body = JsonParser.parseString(res.body.string()).asJsonObject
            val feedId = body["feed_id"].asLong
            val (feed, links) = getFeed(feedId).toVestiFeed()
            return AddFeedResult(
                feed = feed,
                feedLinks = links,
                entries = emptyList(),
            )
        } else {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    final override suspend fun getFeeds(): List<FeedTable.Feed> {
        return getFeedsWithLinks().map { it.feed }
    }

    open suspend fun getFeedsWithLinks(): List<FreshFeed> {
        // https://miniflux.app/docs/api.html#endpoint-get-feeds
        val req = Request.Builder().url(baseUrl.newBuilder().addPathSegment("feeds").build()).get()
            .build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 200) {
            JsonParser.parseString(res.body.string()).asJsonArray.map { it.asJsonObject }
                .map { it.toMinifluxFeed() }
                .map { fresh ->
                    val (feed, links) = fresh.toVestiFeed()
                    FreshFeed(feed = feed, links = links, categoryId = fresh.categoryId)
                }
        } else {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> {
        // https://miniflux.app/docs/api.html#endpoint-update-feed
        val args = JsonObject().apply { add("title", JsonPrimitive(newTitle)) }
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("feeds").addPathSegment(feedId).build())
            .put(args.toString().toRequestBody(JSON)).build()
        val res = client.newCall(req).executeAsync()
        return if (res.isSuccessful) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("unexpected response code ${res.code}"))
        }
    }

    override suspend fun deleteFeed(feedId: String): Result<Unit> {
        // https://miniflux.app/docs/api.html#endpoint-remove-feed
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("feeds").addPathSegment(feedId).build())
            .delete().build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 204) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("unexpected response code ${res.code}"))
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
            baseUrl.newBuilder().addPathSegment("entries").apply {
                addQueryParameter("status", "unread")
                addQueryParameter("offset", offset.toString())
                addQueryParameter("limit", limit.toString())
            }
        }
    }

    open suspend fun getStarredEntries(): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        return fetchEntriesByFilter { offset, limit ->
            baseUrl.newBuilder().addPathSegment("entries").apply {
                addQueryParameter("starred", "1")
                addQueryParameter("offset", offset.toString())
                addQueryParameter("limit", limit.toString())
            }
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
        urlBuilder: (offset: Long, limit: Long) -> HttpUrl.Builder,
    ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        val collected = mutableListOf<Pair<EntryTable.Entry, List<LinkTable.Link>>>()
        var offset = 0L
        while (true) {
            val req = Request.Builder().url(urlBuilder(offset, MAX_PAGE_SIZE).build()).get().build()
            val res = client.newCall(req).executeAsync()
            if (!res.isSuccessful) {
                throw IOException("http request failed with response code ${res.code}")
            }
            val payload = JsonParser.parseString(res.body.string()).asJsonObject.toEntriesPayload()
            val page = payload.entries.map { it.toEntry() }
            collected += page
            if (page.size < MAX_PAGE_SIZE.toInt()) break
            if (payload.total > 0 && collected.size >= payload.total) break
            offset += MAX_PAGE_SIZE
        }
        return collected
    }

    open suspend fun getEntriesChangedAfter(
        changedAfter: OffsetDateTime,
        limit: Long,
    ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        // https://miniflux.app/docs/api.html#endpoint-get-entries
        val urlBuilder = baseUrl.newBuilder().addPathSegment("entries")
        urlBuilder.addQueryParameter("changed_after", changedAfter.toEpochSecond().toString())
        urlBuilder.addQueryParameter("limit", limit.toString())
        // The Miniflux API defaults to the user's preferred sort order
        // (e.g. `published_at desc`), which means `changed_after` pagination
        // skips entries whose `published_at` is older even though their
        // `changed_at` is newer than the cursor. Pin the order to
        // `changed_at asc` so the cursor walks the change log chronologically.
        urlBuilder.addQueryParameter("order", "changed_at")
        urlBuilder.addQueryParameter("direction", "asc")
        val req = Request.Builder().url(urlBuilder.build()).get().build()
        val res = client.newCall(req).executeAsync()
        return if (res.isSuccessful) {
            val body = res.body.string()
            val payload = JsonParser.parseString(body).asJsonObject.toEntriesPayload()
            payload.entries.map { it.toEntry() }
        } else {
            throw IOException("http request failed with response code ${res.code}")
        }
    }

    open suspend fun markEntriesAsRead(entriesIds: List<String>, read: Boolean) {
        // https://miniflux.app/docs/api.html#endpoint-update-entries
        val args = JsonObject().apply {
            add("entry_ids", JsonArray().apply { entriesIds.forEach { add(it.toLong()) } })
            add("status", JsonPrimitive(if (read) "read" else "unread"))
        }
        val req = Request.Builder().url(baseUrl.newBuilder().addPathSegment("entries").build())
            .put(args.toString().toRequestBody(JSON)).build()
        val res = client.newCall(req).executeAsync()
        if (!res.isSuccessful || res.code != 204) {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    open suspend fun markEntriesAsBookmarked(
        entries: List<EntryTable.EntryWithoutContent>,
        bookmarked: Boolean,
    ) {
        // https://miniflux.app/docs/api.html#endpoint-update-entry
        entries.forEach { entry ->
            val req = Request.Builder().url(
                baseUrl.newBuilder().addPathSegment("entries").addPathSegment(entry.id)
                    .addPathSegment("bookmark").build()
            ).put(ByteArray(0).toRequestBody(null, 0, 0)).build()
            val rawRes = client.newCall(req).executeAsync()
            if (!rawRes.isSuccessful) {
                throw IOException("http request failed with response code ${rawRes.code}")
            }
        }
    }

    open suspend fun getCategories(): List<MinifluxCategory> {
        // https://miniflux.app/docs/api.html#endpoint-get-categories
        val req = Request.Builder().url(baseUrl.newBuilder().addPathSegment("categories").build())
            .get().build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 200) {
            JsonParser.parseString(res.body.string()).asJsonArray
                .map { it.asJsonObject.toMinifluxCategory() }
        } else {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    open suspend fun createCategory(title: String): MinifluxCategory {
        // https://miniflux.app/docs/api.html#endpoint-create-category
        val args = JsonObject().apply { add("title", JsonPrimitive(title)) }
        val req = Request.Builder().url(baseUrl.newBuilder().addPathSegment("categories").build())
            .post(args.toString().toRequestBody(JSON)).build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 201) {
            JsonParser.parseString(res.body.string()).asJsonObject.toMinifluxCategory()
        } else {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    open suspend fun findOrCreateCategory(title: String): MinifluxCategory {
        val existing = getCategories().firstOrNull { it.title == title }
        if (existing != null) return existing
        return createCategory(title)
    }

    open suspend fun updateCategory(id: Long, title: String): MinifluxCategory {
        // https://miniflux.app/docs/api.html#endpoint-update-category
        val args = JsonObject().apply {
            add("id", JsonPrimitive(id))
            add("title", JsonPrimitive(title))
        }
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("categories").addPathSegment(id.toString()).build())
            .put(args.toString().toRequestBody(JSON)).build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 201) {
            JsonParser.parseString(res.body.string()).asJsonObject.toMinifluxCategory()
        } else {
            throw IOException("unexpected response code ${res.code}")
        }
    }

    open suspend fun deleteCategory(id: Long): Result<Unit> {
        // https://miniflux.app/docs/api.html#endpoint-delete-category
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("categories").addPathSegment(id.toString()).build())
            .delete().build()
        val res = client.newCall(req).executeAsync()
        return if (res.code == 204) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("unexpected response code ${res.code}"))
        }
    }

    open suspend fun moveFeedToCategory(feedId: String, categoryId: Long) {
        // https://miniflux.app/docs/api.html#endpoint-update-feed
        val args = JsonObject().apply { add("category_id", JsonPrimitive(categoryId)) }
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("feeds").addPathSegment(feedId).build())
            .put(args.toString().toRequestBody(JSON)).build()
        val res = client.newCall(req).executeAsync()
        if (!res.isSuccessful) {
            throw IOException("unexpected response code ${res.code}")
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
                published = OffsetDateTime.parse(published_at),
                updated = OffsetDateTime.parse(changed_at),
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
            ), links
        )
    }

    private fun JsonObject.toEntriesPayload(): EntriesPayload {
        val total = if (has("total") && !this["total"].isJsonNull) this["total"].asLong else 0
        val entriesArray = getAsJsonArray("entries") ?: JsonArray()
        val entries = entriesArray.map { it.asJsonObject.toEntryJson() }
        return EntriesPayload(
            total = total,
            entries = entries,
        )
    }

    private fun JsonObject.toEntryJson(): EntryJson {
        return EntryJson(
            id = this["id"].asLong,
            feed_id = this["feed_id"].asLong,
            status = this["status"].asString,
            title = this["title"].asString,
            url = this["url"].asString,
            comments_url = if (has("comments_url") && !this["comments_url"].isJsonNull) this["comments_url"].asString else "",
            published_at = if (has("published_at") && !this["published_at"].isJsonNull) this["published_at"].asString else "",
            created_at = if (has("created_at") && !this["created_at"].isJsonNull) this["created_at"].asString else "",
            changed_at = if (has("changed_at") && !this["changed_at"].isJsonNull) this["changed_at"].asString else "",
            content = if (has("content") && !this["content"].isJsonNull) this["content"].asString else "",
            author = if (has("author") && !this["author"].isJsonNull) this["author"].asString else "",
            starred = if (has("starred") && !this["starred"].isJsonNull) this["starred"].asBoolean else false,
            enclosures = if (has("enclosures") && !this["enclosures"].isJsonNull) {
                this["enclosures"].asJsonArray.map { it.asJsonObject.toEntryEnclosureJson() }
            } else null,
        )
    }

    private fun JsonObject.toEntryEnclosureJson(): EntryEnclosureJson {
        return EntryEnclosureJson(
            id = this["id"].asLong,
            user_id = this["user_id"].asLong,
            entry_id = this["entry_id"].asLong,
            url = this["url"].asString,
            mime_type = this["mime_type"].asString,
            size = this["size"].asLong,
        )
    }

    private fun JsonObject.toMinifluxFeed(): MinifluxFeed {
        return MinifluxFeed(
            id = this["id"].asLong,
            title = if (has("title") && !this["title"].isJsonNull) this["title"].asString else "",
            feedUrl = if (has("feed_url") && !this["feed_url"].isJsonNull) this["feed_url"].asString else "",
            siteUrl = if (has("site_url") && !this["site_url"].isJsonNull) this["site_url"].asString else "",
            categoryId = if (has("category") && !this["category"].isJsonNull) {
                val category = this["category"].asJsonObject
                if (category.has("id") && !category["id"].isJsonNull) {
                    category["id"].asLong
                } else {
                    null
                }
            } else null,
        )
    }

    private fun JsonObject.toMinifluxCategory(): MinifluxCategory {
        return MinifluxCategory(
            id = this["id"].asLong,
            title = if (has("title") && !this["title"].isJsonNull) this["title"].asString else "",
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
        val JSON = "application/json".toMediaType()
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
