package org.vestifeed.backend

import kotlin.time.Instant

/**
 * The Miniflux HTTP API, as a port.
 *
 * This is the wire half of [Miniflux]: endpoints, pagination, JSON and status
 * handling, and nothing else. Every result is a DTO ([MinifluxFeed],
 * [MinifluxEntry]) rather than a `FeedTable`/`EntryTable` row, so
 *
 * - the transport can be exercised, or faked, without a `Database`, and
 * - the row mapping in [MinifluxMapping] can be tested without HTTP.
 *
 * Endpoints, parameters and version notes follow the official API
 * documentation: <https://miniflux.app/docs/api.html>
 */
interface MinifluxApi {

    // ---- Feeds -------------------------------------------------------------
    // https://miniflux.app/docs/api.html#endpoint-get-feeds

    /**
     * Every feed the user is subscribed to, with the category it belongs to.
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-feeds>
     */
    suspend fun getFeeds(): List<MinifluxFeed>

    /**
     * Subscribes to [feedUrl] in [categoryId] (or the default category when
     * null) and returns the created feed.
     *
     * The create endpoint answers with `{"feed_id": ...}` only, so the feed
     * itself is read back with a second request rather than assembled from the
     * request.
     *
     * <https://miniflux.app/docs/api.html#endpoint-create-feed>
     */
    suspend fun addFeed(feedUrl: String, categoryId: Long?): MinifluxFeed

    /**
     * Renames a feed. Throws when the server rejects the request.
     *
     * <https://miniflux.app/docs/api.html#endpoint-update-feed>
     */
    suspend fun updateFeedTitle(feedId: String, newTitle: String)

    /**
     * Unsubscribes from a feed, removing it and its entries on the server.
     * Throws when the server rejects the request.
     *
     * <https://miniflux.app/docs/api.html#endpoint-remove-feed>
     */
    suspend fun deleteFeed(feedId: String)

    // ---- Entries -----------------------------------------------------------

    /**
     * Every unread entry, in full pages of [MAX_PAGE_SIZE].
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-entries>
     */
    suspend fun getUnreadEntries(): List<MinifluxEntry>

    /**
     * Every starred entry, in full pages of [MAX_PAGE_SIZE].
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-entries>
     */
    suspend fun getStarredEntries(): List<MinifluxEntry>

    /**
     * Entries whose `changed_at` is after [changedAfter], oldest change first,
     * at most [limit] of them. This is the incremental sync's cursor: the
     * server returns every status, including `removed`, so callers must map the
     * status rather than assume the rows are unread.
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-entries>
     */
    suspend fun getEntriesChangedAfter(changedAfter: Instant, limit: Long): List<MinifluxEntry>

    /**
     * Every entry ID the server currently has, or null when the server predates
     * the endpoint (Miniflux < v2.3.2), which is how a reconcile tells
     * "unsupported" from "nothing to delete".
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-entry-ids>
     */
    suspend fun getEntryIds(): Set<Long>?

    /**
     * Sets the read state of [entryIds] to [read].
     *
     * <https://miniflux.app/docs/api.html#endpoint-update-entries>
     */
    suspend fun markEntriesAsRead(entryIds: List<String>, read: Boolean)

    /**
     * Sets the starred state of [entryIds] to [bookmarked].
     *
     * Prefers the bulk update with an absolute `starred` value (Miniflux >=
     * v2.3.2), which is idempotent: a retry or a duplicate push cannot flip the
     * state back. Older servers reject that field with HTTP 400 and are handled
     * by reading each entry's current state and toggling only the ones that
     * differ.
     *
     * <https://miniflux.app/docs/api.html#endpoint-update-entries>
     *
     * <https://miniflux.app/docs/api.html#endpoint-toggle-bookmark>
     */
    suspend fun markEntriesAsBookmarked(entryIds: List<String>, bookmarked: Boolean)

    // ---- Categories --------------------------------------------------------

    /**
     * The user's categories, which the app mirrors into local tags.
     *
     * <https://miniflux.app/docs/api.html#endpoint-get-categories>
     */
    suspend fun getCategories(): List<MinifluxCategory>

    companion object {
        /**
         * The largest page `/v1/entries` accepts; it answers HTTP 400 above
         * this. The user-configurable `entries_per_page` setting only affects
         * Miniflux's own UI.
         *
         * <https://miniflux.app/docs/api.html#endpoint-get-entries>
         */
        const val MAX_PAGE_SIZE = 1000L

        /**
         * The largest page `/v1/entries/ids` accepts, and how many IDs
         * consecutive pages share so a page shift caused by a concurrent
         * deletion cannot skip a live entry.
         *
         * <https://miniflux.app/docs/api.html#endpoint-get-entry-ids>
         */
        const val ID_PAGE_SIZE = 10_000L
        const val ID_PAGE_OVERLAP = 1_000L
    }
}

/**
 * A feed as the API describes it.
 *
 * [feedUrl] is the XML/RSS/Atom document, [siteUrl] the feed's home page; the
 * local `link` rows are built from both.
 */
data class MinifluxFeed(
    val id: Long,
    val title: String,
    val feedUrl: String,
    val siteUrl: String,
    val categoryId: Long?,
)

/** One media attachment of an entry, e.g. a podcast episode. */
data class MinifluxEnclosure(
    val id: Long,
    val url: String,
    val mimeType: String,
    val size: Long,
)

/** An entry as the API describes it. */
data class MinifluxEntry(
    val id: Long,
    val feedId: Long,
    val status: MinifluxEntryStatus,
    val title: String,
    /** The article's own address, empty when the feed carried none. */
    val url: String,
    val commentsUrl: String,
    val publishedAt: Instant,
    /** When the entry last changed on the server; the incremental sync cursor. */
    val changedAt: Instant,
    val content: String,
    val author: String,
    val starred: Boolean,
    val enclosures: List<MinifluxEnclosure>,
)

/**
 * Miniflux's entry status.
 *
 * `removed` marks an entry the feed no longer carries. The unfiltered
 * `/v1/entries` listing returns those rows too, and they must not reappear in
 * the Unread tab, so anything but [UNREAD] is treated as read locally.
 */
enum class MinifluxEntryStatus {
    UNREAD,
    READ,
    REMOVED,

    /** A status this client does not know; treated like [READ]. */
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String): MinifluxEntryStatus = when (value) {
            "unread" -> UNREAD
            "read" -> READ
            "removed" -> REMOVED
            else -> UNKNOWN
        }
    }
}

/**
 * A category as the API describes it. Local [org.vestifeed.db.table.TagTable]
 * rows mirror these, keyed by `ext_miniflux_id`.
 */
data class MinifluxCategory(
    val id: Long,
    val title: String,
)
