package org.vestifeed.backend

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.withLock
import org.vestifeed.db.Database
import org.vestifeed.db.table.TagTable
import org.vestifeed.util.toInstant

/**
 * The Miniflux sync algorithm: pulls feeds, categories and entries into the
 * local cache, and pushes local read/bookmark changes back.
 *
 * It works against [MinifluxApi] rather than a concrete transport, so the whole
 * algorithm can be driven by a fake in tests without a server, and the row
 * mapping it applies is [MinifluxMapping]'s.
 */
@OptIn(ExperimentalUuidApi::class)
internal class MinifluxSync(private val api: MinifluxApi, private val db: Database) {

    /**
     * A full sync, in the order the pieces depend on each other: feeds first
     * (categories need the feeds' category ids), then categories, then entries.
     *
     * The feeds are fetched once and handed to [syncCategories] instead of
     * being requested again there.
     */
    suspend fun sync(initial: Boolean) {
        val feeds = syncFeeds()
        syncCategories(feeds)
        syncEntries(initial)
    }

    /** Reconciles the local feed rows and their links. Returns the server's feeds. */
    private suspend fun syncFeeds(): List<MinifluxFeed> {
        val freshFeeds = api.getFeeds()
        val cachedFeeds = db.feed.selectAll()
        val freshFeedIds = freshFeeds.map { it.id }
        val cachedFeedIds = cachedFeeds.map { it.id.toLong() }
        val deletedOnServerIds = cachedFeedIds.filterNot { freshFeedIds.contains(it) }
        deletedOnServerIds.forEach {
            db.link.deleteByFeedId(it.toString())
            db.feedTag.deleteByFeedId(it.toString())
            db.feed.deleteById(it.toString())
        }
        for (fresh in freshFeeds) {
            val feed = MinifluxMapping.toFeed(fresh)
            val freshLinks = MinifluxMapping.toFeedLinks(fresh)
            val cachedFeed = cachedFeeds.find { it.id == feed.id }
            if (cachedFeed == null) {
                db.feed.insertOrReplace(feed)
                db.link.insertForFeed(feed.id, freshLinks)
                continue
            }
            // The per-feed display overrides are the user's; the server does
            // not know about them, so they survive a pull.
            db.feed.insertOrReplace(
                feed.copy(
                    extOpenEntriesInBrowser = cachedFeed.extOpenEntriesInBrowser,
                    extBlockedWords = cachedFeed.extBlockedWords,
                    extShowPreviewImages = cachedFeed.extShowPreviewImages,
                ),
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
                    // Carries the playback state (download progress, cached
                    // file) across a metadata refresh.
                    db.link.insertForFeed(
                        feed.id,
                        listOf(
                            freshLink.copy(
                                extEnclosureDownloadProgress = cachedLink.extEnclosureDownloadProgress,
                                extCacheUri = cachedLink.extCacheUri,
                            ),
                        ),
                    )
                }
            }
        }
        return freshFeeds
    }

    /**
     * Reconcile remote Miniflux categories into the local [TagTable] /
     * [org.vestifeed.db.table.FeedTagTable]. Runs after [syncFeeds], whose result
     * it takes so the feed list is not requested twice.
     *
     * Remote categories are stored with `ext_source = 'miniflux'` and
     * `ext_miniflux_id` set; locally-created tags from an Embedded session are
     * kept (their source is `embedded`) because the Miniflux API has no concept
     * of "extra" tags per feed.
     *
     * If a category disappears upstream, the local [TagTable] row is removed
     * and the corresponding [org.vestifeed.db.table.FeedTagTable] rows go with
     * it (CASCADE).
     */
    private suspend fun syncCategories(freshFeeds: List<MinifluxFeed>) {
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

        val allTags = db.tag.selectAll()
        val byMinifluxId = allTags
            .filter { it.extSource == TagTable.Source.Miniflux }
            .associateBy { it.extMinifluxId }
        val freshFeedIds = freshFeeds.map { it.id.toString() }

        db.transaction {
            for (feedId in freshFeedIds) {
                db.feedTag.deleteByFeedId(feedId)
            }
            for (fresh in freshFeeds) {
                val categoryId = fresh.categoryId ?: continue
                val tag = byMinifluxId[categoryId] ?: continue
                db.feedTag.insert(feedId = fresh.id.toString(), tagId = tag.id)
            }
        }
    }

    /**
     * Sends the local read/bookmark state that has not reached the server yet.
     * A single entry action only needs this — one request for the read entries
     * (plus one per bookmarked entry) — instead of re-fetching every feed,
     * category and changed entry like [syncEntries] does.
     *
     * Runs under [Database.pendingPushMutex] so a full sync's push and an entry
     * action's `Sync.pushInBackground` cannot put two requests for the same
     * entry in flight: the one that arrives last must carry the newer state.
     */
    suspend fun pushPendingChanges() {
        db.pendingPushMutex.withLock { pushPendingChangesLocked() }
    }

    private suspend fun pushPendingChangesLocked() {
        val unsyncedEntries = db.entry.selectByReadSynced(false)
        val unsyncedReadEntries = unsyncedEntries.filter { it.extRead }
        val unsyncedUnreadEntries = unsyncedEntries.filter { !it.extRead }

        if (unsyncedReadEntries.isNotEmpty()) {
            api.markEntriesAsRead(
                entryIds = unsyncedReadEntries.map { it.id },
                read = true,
            )

            db.transaction {
                unsyncedReadEntries.forEach {
                    db.entry.markReadSyncedIfUnchanged(it.id, extRead = true)
                }
            }
        }

        if (unsyncedUnreadEntries.isNotEmpty()) {
            api.markEntriesAsRead(
                entryIds = unsyncedUnreadEntries.map { it.id },
                read = false,
            )

            db.transaction {
                unsyncedUnreadEntries.forEach {
                    db.entry.markReadSyncedIfUnchanged(it.id, extRead = false)
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
                entryIds = notSyncedBookmarkedEntries.map { it.id },
                bookmarked = true,
            )

            db.transaction {
                notSyncedBookmarkedEntries.forEach {
                    db.entry.markBookmarkedSyncedIfUnchanged(it.id, extBookmarked = true)
                }
            }
        }

        if (notSyncedNotBookmarkedEntries.isNotEmpty()) {
            api.markEntriesAsBookmarked(
                entryIds = notSyncedNotBookmarkedEntries.map { it.id },
                bookmarked = false,
            )

            db.transaction {
                notSyncedNotBookmarkedEntries.forEach {
                    db.entry.markBookmarkedSyncedIfUnchanged(it.id, extBookmarked = false)
                }
            }
        }
    }

    private suspend fun syncEntries(initial: Boolean) {
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
                val rows = currentBatch.map(MinifluxMapping::toEntry)
                val newChangedAfter = rows.maxOf { it.first.updated }
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
                    rows.forEach {
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

    private suspend fun syncUnreadEntries() {
        val freshEntries = api.getUnreadEntries().map(MinifluxMapping::toEntry)
        db.transaction {
            freshEntries.forEach {
                db.entry.insertOrReplace(listOf(it.first))
                db.link.insertForEntry(it.first.id, it.second)
            }
        }
    }

    private suspend fun syncStarredEntries() {
        val freshEntries = api.getStarredEntries().map(MinifluxMapping::toEntry)
        db.transaction {
            freshEntries.forEach {
                db.entry.insertOrReplace(listOf(it.first))
                db.link.insertForEntry(it.first.id, it.second)
            }
        }
    }
}
