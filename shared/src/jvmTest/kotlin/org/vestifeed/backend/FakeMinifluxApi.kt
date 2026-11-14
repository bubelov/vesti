package org.vestifeed.backend

import kotlin.time.Instant

/**
 * A [MinifluxApi] whose endpoints fail unless a test stubs them, so each test
 * declares exactly the traffic it expects instead of inheriting a pile of
 * unrelated empty responses.
 */
internal open class FakeMinifluxApi : MinifluxApi {

    override suspend fun getFeeds(): List<MinifluxFeed> = notStubbed("getFeeds")

    override suspend fun addFeed(feedUrl: String, categoryId: Long?): MinifluxFeed =
        notStubbed("addFeed")

    override suspend fun updateFeedTitle(feedId: String, newTitle: String): Unit =
        notStubbed("updateFeedTitle")

    override suspend fun deleteFeed(feedId: String): Unit = notStubbed("deleteFeed")

    override suspend fun getUnreadEntries(): List<MinifluxEntry> = notStubbed("getUnreadEntries")

    override suspend fun getStarredEntries(): List<MinifluxEntry> = notStubbed("getStarredEntries")

    override suspend fun getEntriesChangedAfter(
        changedAfter: Instant,
        limit: Long,
    ): List<MinifluxEntry> = notStubbed("getEntriesChangedAfter")

    override suspend fun getEntryIds(): Set<Long>? = notStubbed("getEntryIds")

    override suspend fun markEntriesAsRead(entryIds: List<String>, read: Boolean): Unit =
        notStubbed("markEntriesAsRead")

    override suspend fun markEntriesAsBookmarked(
        entryIds: List<String>,
        bookmarked: Boolean,
    ): Unit = notStubbed("markEntriesAsBookmarked")

    override suspend fun getCategories(): List<MinifluxCategory> = notStubbed("getCategories")

    private fun notStubbed(name: String): Nothing =
        throw UnsupportedOperationException("$name is not stubbed for this test")
}
