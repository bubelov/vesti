package org.vestifeed.backend

import io.ktor.http.Url
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.util.toUrl

abstract class Backend(protected val db: Database) {
    data class AddFeedResult(
        val feed: FeedTable.Feed,
        val feedLinks: List<LinkTable.Link>,
        val entries: List<Pair<EntryTable.Entry, List<LinkTable.Link>>>,
    )

    abstract suspend fun addFeed(url: Url, categoryId: Long?): AddFeedResult

    abstract suspend fun getFeeds(): List<FeedTable.Feed>

    abstract suspend fun updateFeedTitle(
        feedId: String,
        newTitle: String,
    ): Result<Unit>

    abstract suspend fun deleteFeed(feedId: String): Result<Unit>

    abstract suspend fun sync(initial: Boolean)

    /**
     * Pushes the local read/bookmark changes that have not reached the server
     * yet, without pulling anything back. This is the cheap path a single entry
     * action uses; backends that keep their state locally (Embedded) make it a
     * no-op.
     */
    open suspend fun pushPendingChanges() {}

    /**
     * Removes local entries that no longer exist on the server. Returns true
     * when the backend owns the entry catalog and the attempt was applicable
     * (even if the server is too old to expose the ID list), so the caller can
     * record the attempt and not retry on every sync. Backends that keep their
     * state locally return false.
     */
    open suspend fun pruneDeletedEntries(): Boolean = false
}

suspend fun backend(db: Database, userAgent: String = ""): Backend {
    val conf = db.conf.select()
    return when (conf.backend) {
        ConfTable.Backend.Miniflux -> {
            if (conf.minifluxUrl == null || conf.minifluxToken == null) {
                Embedded(db, userAgent)
            } else {
                Miniflux(
                    api = MinifluxHttpApi(
                        client = minifluxHttpClient(token = conf.minifluxToken, userAgent = userAgent),
                        // The API root; every endpoint path below it.
                        baseUrl = "${conf.minifluxUrl}/v1/".toUrl(),
                    ),
                    db = db,
                )
            }
        }

        ConfTable.Backend.Embedded -> Embedded(db, userAgent)
        null -> Embedded(db, userAgent)
    }
}
