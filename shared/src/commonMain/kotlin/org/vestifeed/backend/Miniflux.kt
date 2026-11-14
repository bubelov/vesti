package org.vestifeed.backend

import io.ktor.http.Url
import org.vestifeed.db.Database
import org.vestifeed.db.table.FeedTable

/**
 * The Miniflux backend: local state plus a [MinifluxApi].
 *
 * It is deliberately thin. The HTTP side lives behind [MinifluxApi] (see
 * [MinifluxHttpApi]), the row mapping in [MinifluxMapping], and the sync
 * algorithm in [MinifluxSync]; this class only implements the [Backend] contract
 * by composing them, which keeps the parts independently testable.
 *
 * Read state is server-authoritative here: the `*_synced` flags mean "the
 * server has been told", and a pull overwrites local values that were already
 * pushed.
 */
class Miniflux(
    val api: MinifluxApi,
    db: Database,
) : Backend(db) {

    override suspend fun addFeed(url: Url, categoryId: Long?): AddFeedResult {
        val feed = api.addFeed(url.toString(), categoryId)
        return AddFeedResult(
            feed = MinifluxMapping.toFeed(feed),
            feedLinks = MinifluxMapping.toFeedLinks(feed),
            entries = emptyList(),
        )
    }

    override suspend fun getFeeds(): List<FeedTable.Feed> =
        api.getFeeds().map(MinifluxMapping::toFeed)

    override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> =
        runCatching { api.updateFeedTitle(feedId, newTitle) }

    override suspend fun deleteFeed(feedId: String): Result<Unit> =
        runCatching { api.deleteFeed(feedId) }

    override suspend fun sync(initial: Boolean) {
        MinifluxSync(api, db).sync(initial)
    }

    override suspend fun pushPendingChanges() {
        MinifluxSync(api, db).pushPendingChanges()
    }

    /**
     * Reconciles the local entry cache with the server's current ID list.
     *
     * Two scans are unioned: offset pagination can shift when the server prunes
     * or adds an entry between requests, and a union only ever keeps more IDs,
     * so a live entry missed by one scan is still protected by the other. A
     * server without the `/v1/entries/ids` endpoint (Miniflux < v2.3.2) simply
     * reports a supported attempt without pruning.
     */
    override suspend fun pruneDeletedEntries(): Boolean {
        val first = api.getEntryIds() ?: return true
        val second = api.getEntryIds() ?: return true
        db.pruneEntriesNotIn(first + second)
        return true
    }
}
