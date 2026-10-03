package org.vestifeed.backend

import io.ktor.http.Url
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.util.toUrl

sealed class Backend(protected val db: Database) {
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
}

suspend fun backend(db: Database): Backend {
    val conf = db.conf.select()
    return when (conf.backend) {
        ConfTable.Backend.Miniflux -> {
            if (conf.minifluxUrl == null || conf.minifluxToken == null) {
                Embedded(db)
            } else {
                Miniflux(
                    client = minifluxHttpClient(token = conf.minifluxToken),
                    baseUrl = "${conf.minifluxUrl}/v1/".toUrl(),
                    db = db,
                )
            }
        }

        ConfTable.Backend.Embedded -> Embedded(db)
        null -> Embedded(db)
    }
}
