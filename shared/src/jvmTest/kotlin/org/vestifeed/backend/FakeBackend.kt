package org.vestifeed.backend

import io.ktor.http.Url
import org.vestifeed.db.Database
import org.vestifeed.db.table.FeedTable

/**
 * A backend whose sync body is supplied by the test.
 *
 * It implements [Backend] rather than inheriting [Miniflux]: a sync schedule
 * test has no interest in the Miniflux HTTP surface, and this way no request can
 * leave the process by accident.
 */
internal class FakeBackend(
    db: Database,
    private val onSync: suspend () -> Unit,
) : Backend(db) {

    override suspend fun addFeed(url: Url, categoryId: Long?): AddFeedResult =
        throw UnsupportedOperationException("addFeed is not used by this test")

    override suspend fun getFeeds(): List<FeedTable.Feed> = emptyList()

    override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun deleteFeed(feedId: String): Result<Unit> = Result.success(Unit)

    override suspend fun sync(initial: Boolean) = onSync()
}
