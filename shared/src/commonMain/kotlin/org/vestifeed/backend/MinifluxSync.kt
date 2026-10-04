package org.vestifeed.backend

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.vestifeed.db.Database
import org.vestifeed.db.table.TagTable
import org.vestifeed.util.toInstant

@OptIn(ExperimentalUuidApi::class)
internal class MinifluxSync(private val api: Miniflux, private val db: Database) {

    suspend fun syncFeeds() {
        val freshFeedPairs = api.getFeedsWithLinks()
        val cachedFeeds = db.feed.selectAll()
        val freshFeedIds = freshFeedPairs.map { it.feed.id.toLong() }
        val cachedFeedIds = cachedFeeds.map { it.id.toLong() }
        val deletedOnServerIds = cachedFeedIds.filterNot { freshFeedIds.contains(it) }
        deletedOnServerIds.forEach {
            db.link.deleteByFeedId(it.toString())
            db.feedTag.deleteByFeedId(it.toString())
            db.feed.deleteById(it.toString())
        }
        for (fresh in freshFeedPairs) {
            val feed = fresh.feed
            val freshLinks = fresh.links
            val cached = cachedFeedIds.contains(feed.id.toLong())
            if (cached) {
                val cachedFeed = cachedFeeds.find { it.id == feed.id }!!
                db.feed.insertOrReplace(
                    feed.copy(
                        extOpenEntriesInBrowser = cachedFeed.extOpenEntriesInBrowser,
                        extBlockedWords = cachedFeed.extBlockedWords,
                        extShowPreviewImages = cachedFeed.extShowPreviewImages,
                    )
                )
                val cachedLinks = db.link.selectByFeedId(cachedFeed.id)
                val linksDeletedOnServer =
                    cachedLinks.filter { c -> freshLinks.none { f -> c.href == f.href && c.type == f.type } }
                linksDeletedOnServer.forEach { db.link.deleteById(it.id!!) }
                for (freshLink in freshLinks) {
                    val cachedLink =
                        cachedLinks.find { it.href == freshLink.href && it.type == freshLink.type }
                    if (cachedLink == null) {
                        db.link.insertForFeed(feed.id, listOf(freshLink))
                    } else {
                        db.link.insertForFeed(
                            feed.id, listOf(
                                freshLink.copy(
                                    extEnclosureDownloadProgress = cachedLink.extEnclosureDownloadProgress,
                                    extCacheUri = cachedLink.extCacheUri,
                                )
                            )
                        )
                    }
                }
            } else {
                db.feed.insertOrReplace(feed)
                db.link.insertForFeed(feed.id, freshLinks)
            }
        }
    }

    /**
     * Reconcile remote Miniflux categories into the local [TagTable] /
     * [org.vestifeed.db.table.FeedTagTable]. Runs after [syncFeeds] so we
     * already know which feeds exist locally. Remote categories are stored
     * with `ext_source = 'miniflux'` and `ext_miniflux_id` set; locally-created
     * tags from an Embedded session are kept around untouched (their source is
     * `embedded`) because the Miniflux API has no concept of "extra" tags
     * per feed.
     *
     * If a category disappears upstream, the local [TagTable] row is removed
     * and the corresponding [org.vestifeed.db.table.FeedTagTable] rows go with
     * it (CASCADE).
     */
    suspend fun syncCategories() {
        val freshCategories = api.getCategories()
        val freshCategoryIds = freshCategories.map { it.id }
        val cachedTags = db.tag.selectAll()
        val remoteTags = cachedTags.filter { it.extSource == TagTable.Source.Miniflux }
        val cachedTagIds = remoteTags.mapNotNull { it.extMinifluxId }
        val deletedOnServerCategoryIds = cachedTagIds.filterNot { freshCategoryIds.contains(it) }

        db.transaction {
            deletedOnServerCategoryIds.forEach { minifluxId ->
                remoteTags.find { it.extMinifluxId == minifluxId }?.let { tag ->
                    db.feedTag.deleteByTagId(tag.id)
                    db.tag.deleteById(tag.id)
                }
            }

            val byMinifluxId = remoteTags.associateBy { it.extMinifluxId }
            for (fresh in freshCategories) {
                val existing = byMinifluxId[fresh.id]
                val tag = if (existing == null) {
                    TagTable.Tag(
                        id = Uuid.random().toString(),
                        name = fresh.title,
                        extSource = TagTable.Source.Miniflux,
                        extMinifluxId = fresh.id,
                    )
                } else {
                    existing.copy(name = fresh.title)
                }
                db.tag.insertOrReplace(tag)
            }
        }

        val freshFeedPairs = api.getFeedsWithLinks()
        val allTags = db.tag.selectAll()
        val byMinifluxId = allTags
            .filter { it.extSource == TagTable.Source.Miniflux }
            .associateBy { it.extMinifluxId }
        val freshFeedIds = freshFeedPairs.map { it.feed.id }

        db.transaction {
            for (feedId in freshFeedIds) {
                db.feedTag.deleteByFeedId(feedId)
            }
            for (fresh in freshFeedPairs) {
                val categoryId = fresh.categoryId ?: continue
                val tag = byMinifluxId[categoryId] ?: continue
                db.feedTag.insert(feedId = fresh.feed.id, tagId = tag.id)
            }
        }
    }

    /**
     * Sends the local read/bookmark state that has not reached the server yet.
     * A single entry action only needs this — one request for the read entries
     * (plus one per bookmarked entry) — instead of re-fetching every feed,
     * category and changed entry like [syncEntries] does.
     */
    suspend fun pushPendingChanges() {
        val unsyncedEntries = db.entry.selectByReadSynced(false)
        val unsyncedReadEntries = unsyncedEntries.filter { it.extRead }
        val unsyncedUnreadEntries = unsyncedEntries.filter { !it.extRead }

        if (unsyncedReadEntries.isNotEmpty()) {
            api.markEntriesAsRead(
                entriesIds = unsyncedReadEntries.map { it.id },
                read = true,
            )

            db.transaction {
                unsyncedReadEntries.forEach {
                    db.entry.updateReadSynced(true, it.id)
                }
            }
        }

        if (unsyncedUnreadEntries.isNotEmpty()) {
            api.markEntriesAsRead(
                entriesIds = unsyncedUnreadEntries.map { it.id },
                read = false,
            )

            db.transaction {
                unsyncedUnreadEntries.forEach {
                    db.entry.updateReadSynced(true, it.id)
                }
            }
        }

        val notSyncedEntries = db.entry.selectByBookmarkedSynced(false)
        val notSyncedBookmarkedEntries =
            notSyncedEntries.filter { it.extBookmarked }
        val notSyncedNotBookmarkedEntries =
            notSyncedEntries.filterNot { it.extBookmarked }

        if (notSyncedBookmarkedEntries.isNotEmpty()) {
            api.markEntriesAsBookmarked(
                entries = notSyncedBookmarkedEntries,
                bookmarked = true,
            )

            db.transaction {
                notSyncedBookmarkedEntries.forEach {
                    db.entry.updateBookmarkedSynced(true, it.id)
                }
            }
        }

        if (notSyncedNotBookmarkedEntries.isNotEmpty()) {
            api.markEntriesAsBookmarked(
                entries = notSyncedNotBookmarkedEntries,
                bookmarked = false,
            )

            db.transaction {
                notSyncedNotBookmarkedEntries.forEach {
                    db.entry.updateBookmarkedSynced(true, it.id)
                }
            }
        }
    }

    suspend fun syncEntries(initial: Boolean) {
        if (initial) {
            val startedAt = Clock.System.now().toString()
            syncStarredEntries()
            syncUnreadEntries()
            db.conf.update { it.copy(minifluxIncrementalSyncTimestamp = startedAt) }
        } else {
            pushPendingChanges()

            var changedAfter = db.conf.select().minifluxIncrementalSyncTimestamp?.toInstant()
                ?: Clock.System.now()
            val baseBatchSize = 100L
            var batchSize = baseBatchSize
            while (true) {
                val currentBatch = api.getEntriesChangedAfter(changedAfter, batchSize)
                if (currentBatch.isEmpty()) {
                    break
                }
                val newChangedAfter = currentBatch.maxOf { it.first.updated }
                if (newChangedAfter.epochSeconds == changedAfter.epochSeconds) {
                    // Every returned entry's `changed_at` falls inside the same
                    // second as the cursor. Miniflux's `changed_after` is
                    // second-precision, so epochSeconds can't distinguish
                    // them and we'd loop forever. Grow the batch so a single
                    // request pulls past the end of that second; the cursor
                    // advances on the next pass when `newChangedAfter` lands in
                    // a later second.
                    if (currentBatch.size < batchSize) {
                        // Server has nothing more at or after the cursor.
                        break
                    }
                    batchSize += baseBatchSize
                    continue
                }
                db.transaction {
                    currentBatch.forEach {
                        db.entry.insertOrReplace(listOf(it.first))
                        db.link.insertForEntry(it.first.id, it.second)
                    }
                    db.conf.update {
                        it.copy(
                            minifluxIncrementalSyncTimestamp = newChangedAfter.toString(),
                        )
                    }
                    changedAfter = newChangedAfter
                }
                if (currentBatch.size < batchSize) {
                    break
                }
                batchSize = baseBatchSize
            }
        }
    }

    suspend fun syncUnreadEntries() {
        val freshEntries = api.getUnreadEntries()
        db.transaction {
            freshEntries.forEach {
                db.entry.insertOrReplace(listOf(it.first))
                db.link.insertForEntry(it.first.id, it.second)
            }
        }
    }

    suspend fun syncStarredEntries() {
        val freshEntries = api.getStarredEntries()
        db.transaction {
            freshEntries.forEach {
                db.entry.insertOrReplace(listOf(it.first))
                db.link.insertForEntry(it.first.id, it.second)
            }
        }
    }
}
