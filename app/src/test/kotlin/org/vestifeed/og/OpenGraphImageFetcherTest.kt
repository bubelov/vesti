package org.vestifeed.og

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable

class OpenGraphImageFetcherTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = Database(BundledSQLiteDriver(), ":memory:")
        db.conf.insert(ConfTable.defaultConf())
    }

    // ---------------------------------------------------------------------
    // Pure decision function — mirrors the cases in EntryRowMapperTest so
    // the gating logic and the render logic can never disagree.
    // ---------------------------------------------------------------------

    @Test
    fun shouldFetchOgImage_explicitShowOverridesGlobalHide() {
        assertTrue(OpenGraphImageFetcher.shouldFetchOgImage(perFeed = true, global = false))
    }

    @Test
    fun shouldFetchOgImage_explicitHideOverridesGlobalShow() {
        assertFalse(OpenGraphImageFetcher.shouldFetchOgImage(perFeed = false, global = true))
    }

    @Test
    fun shouldFetchOgImage_followSettingsFollowsGlobalOn() {
        assertTrue(OpenGraphImageFetcher.shouldFetchOgImage(perFeed = null, global = true))
    }

    @Test
    fun shouldFetchOgImage_followSettingsFollowsGlobalOff() {
        assertFalse(OpenGraphImageFetcher.shouldFetchOgImage(perFeed = null, global = false))
    }

    // ---------------------------------------------------------------------
    // runOnce short-circuit — gates the entire iteration behind (online,
    // foreground) so the fetcher doesn't issue network requests when
    // either is missing. Both branches map to the same delay in
    // production but the enum is observed distinctly so logs and tests
    // can tell the two reasons apart.
    // ---------------------------------------------------------------------

    @Test
    fun ogRunSkip_offlineShortCircuitsRegardlessOfForeground() {
        assertEquals(
            OgRunSkip.Offline,
            OpenGraphImageFetcher.ogRunSkip(isOnline = false, isForeground = true),
        )
        assertEquals(
            OgRunSkip.Offline,
            OpenGraphImageFetcher.ogRunSkip(isOnline = false, isForeground = false),
        )
    }

    @Test
    fun ogRunSkip_foregroundMissingWhenOnlineShortCircuits() {
        assertEquals(
            OgRunSkip.NotForeground,
            OpenGraphImageFetcher.ogRunSkip(isOnline = true, isForeground = false),
        )
    }

    @Test
    fun ogRunSkip_onlineAndForegroundProceeds() {
        assertEquals(
            OgRunSkip.No,
            OpenGraphImageFetcher.ogRunSkip(isOnline = true, isForeground = true),
        )
    }

    // ---------------------------------------------------------------------
    // Transient-vs-permanent failure classification. The transient branch
    // is the new behavior — those exceptions used to mark the entry as
    // "checked" forever, which caused a 12-second DNS blip to permanently
    // poison two thousand entries' OG-image slot. The permanent branch
    // still covers HTTP error codes, missing og:image, etc.
    // ---------------------------------------------------------------------

    @Test
    fun isTransientNetwork_unknownHostIsTransient() {
        assertTrue(
            OpenGraphImageFetcher.isTransientNetwork(
                java.net.UnknownHostException("Unable to resolve host \"beej.us\""),
            )
        )
    }

    @Test
    fun isTransientNetwork_socketTimeoutIsTransient() {
        assertTrue(
            OpenGraphImageFetcher.isTransientNetwork(
                java.net.SocketTimeoutException("timeout"),
            )
        )
    }

    @Test
    fun isTransientNetwork_connectExceptionIsTransient() {
        assertTrue(
            OpenGraphImageFetcher.isTransientNetwork(
                java.net.ConnectException("refused"),
            )
        )
    }

    @Test
    fun isTransientNetwork_noRouteToHostIsTransient() {
        assertTrue(
            OpenGraphImageFetcher.isTransientNetwork(
                java.net.NoRouteToHostException("no route"),
            )
        )
    }

    @Test
    fun isTransientNetwork_illegalStateIsPermanent() {
        assertFalse(
            OpenGraphImageFetcher.isTransientNetwork(
                IllegalStateException("not a network error"),
            )
        )
    }

    // ---------------------------------------------------------------------
    // End-to-end gating plan — combines the global setting, the per-feed
    // setting on each candidate, and the batch shape into the next action
    // the fetcher should take.
    // ---------------------------------------------------------------------

    @Test
    fun plan_globalOff_returnsGlobalOffRegardlessOfCandidates() {
        db.conf.update { it.copy(showPreviewImages = false) }
        val follow = candidate(feedShowPreviewImages = null)
        val show = candidate(feedShowPreviewImages = true)
        val hide = candidate(feedShowPreviewImages = false)

        assertEquals(
            OgFetchPlan.GlobalOff,
            OpenGraphImageFetcher.planOgImageFetch(
                conf = db.conf.select(),
                candidates = listOf(follow, show, hide),
            ),
        )
    }

    @Test
    fun plan_globalOnNoCandidates_returnsEmpty() {
        assertEquals(
            OgFetchPlan.Empty,
            OpenGraphImageFetcher.planOgImageFetch(
                conf = db.conf.select(),
                candidates = emptyList(),
            ),
        )
    }

    @Test
    fun plan_globalOnAndAllEligible_returnsToFetchWithNoSkipped() {
        val a = candidate(feedShowPreviewImages = true)
        val b = candidate(feedShowPreviewImages = null) // follows settings → on

        val plan = OpenGraphImageFetcher.planOgImageFetch(
            conf = db.conf.select(),
            candidates = listOf(a, b),
        )

        assertEquals(OgFetchPlan.ToFetch(toFetch = listOf(a, b), skipped = emptyList()), plan)
    }

    @Test
    fun plan_globalOnAndAllSkippedByPerFeed_returnsAllSkipped() {
        val a = candidate(feedShowPreviewImages = false)
        val b = candidate(feedShowPreviewImages = false)

        val plan = OpenGraphImageFetcher.planOgImageFetch(
            conf = db.conf.select(),
            candidates = listOf(a, b),
        )

        assertEquals(OgFetchPlan.AllSkipped(candidates = listOf(a, b)), plan)
    }

    @Test
    fun plan_mixedBatch_partitionsEligibleAndSkipped() {
        val eligibleShow = candidate(feedShowPreviewImages = true)
        val eligibleFollow = candidate(feedShowPreviewImages = null)
        val skipped = candidate(feedShowPreviewImages = false)

        val plan = OpenGraphImageFetcher.planOgImageFetch(
            conf = db.conf.select(),
            candidates = listOf(eligibleShow, skipped, eligibleFollow),
        )

        assertEquals(
            OgFetchPlan.ToFetch(
                toFetch = listOf(eligibleShow, eligibleFollow),
                skipped = listOf(skipped),
            ),
            plan,
        )
    }

    @Test
    fun plan_globalOffEvenWithAllSkippedBatch_returnsGlobalOff() {
        // The global gate wins over the batch composition: if the user
        // has globally disabled preview images, no per-feed entry should
        // ever reach the fetcher.
        db.conf.update { it.copy(showPreviewImages = false) }
        val a = candidate(feedShowPreviewImages = true)
        val b = candidate(feedShowPreviewImages = null)

        assertEquals(
            OgFetchPlan.GlobalOff,
            OpenGraphImageFetcher.planOgImageFetch(
                conf = db.conf.select(),
                candidates = listOf(a, b),
            ),
        )
    }

    // ---------------------------------------------------------------------
    // SQL query — confirms the JOIN pulls the per-feed setting onto each
    // candidate and the WHERE clause filters out already-checked entries.
    // ---------------------------------------------------------------------

    @Test
    fun selectPendingOgImageEntries_returnsOnlyUncheckedJoinedWithFeedSetting() {
        val showFeed = insertFeed(extShowPreviewImages = true)
        val hideFeed = insertFeed(extShowPreviewImages = false)
        val followFeed = insertFeed(extShowPreviewImages = null)

        val showEntry = insertEntry(feedId = showFeed.id, checked = false)
        insertEntry(feedId = showFeed.id, checked = true) // already checked
        val hideEntry = insertEntry(feedId = hideFeed.id, checked = false)
        val followEntry = insertEntry(feedId = followFeed.id, checked = false)

        val result = db.entry.selectPendingOgImageEntries(limit = 50)

        assertEquals(
            mapOf(
                showEntry.id to true,
                hideEntry.id to false,
                followEntry.id to null,
            ),
            result.associate { it.id to it.feedShowPreviewImages },
        )
    }

    @Test
    fun selectPendingOgImageEntries_excludesOrphanEntries() {
        val feed = insertFeed(extShowPreviewImages = true)
        val attached = insertEntry(feedId = feed.id, checked = false)
        // Entry whose feed was deleted — must not appear in the result
        // because the JOIN can't resolve it and there's no useful work
        // the fetcher could do for it anyway.
        insertEntry(feedId = "ghost-feed-id", checked = false)

        val result = db.entry.selectPendingOgImageEntries(limit = 50)

        assertEquals(listOf(attached.id), result.map { it.id })
    }

    @Test
    fun selectPendingOgImageEntries_orderedByPublishedDesc() {
        val feed = insertFeed(extShowPreviewImages = true)
        val older = insertEntry(
            feedId = feed.id,
            checked = false,
            published = OffsetDateTime.parse("2024-01-01T00:00:00Z"),
        )
        val newer = insertEntry(
            feedId = feed.id,
            checked = false,
            published = OffsetDateTime.parse("2024-06-01T00:00:00Z"),
        )

        val result = db.entry.selectPendingOgImageEntries(limit = 50)

        assertEquals(listOf(newer.id, older.id), result.map { it.id })
    }

    @Test
    fun selectPendingOgImageEntries_respectsLimit() {
        val feed = insertFeed(extShowPreviewImages = true)
        repeat(5) { insertEntry(feedId = feed.id, checked = false) }

        val result = db.entry.selectPendingOgImageEntries(limit = 3)

        assertEquals(3, result.size)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun candidate(feedShowPreviewImages: Boolean?): EntryTable.OgImageCandidate =
        EntryTable.OgImageCandidate(
            id = UUID.randomUUID().toString(),
            title = "Some Title",
            extOpenGraphImageLog = "[]",
            feedShowPreviewImages = feedShowPreviewImages,
        )

    private fun insertFeed(extShowPreviewImages: Boolean?): FeedTable.Feed =
        FeedTable.Feed(
            id = UUID.randomUUID().toString(),
            title = "Feed",
            extOpenEntriesInBrowser = null,
            extBlockedWords = "",
            extShowPreviewImages = extShowPreviewImages,
        ).also { db.feed.insertOrReplace(it) }

    private fun insertEntry(
        feedId: String,
        checked: Boolean,
        published: OffsetDateTime = OffsetDateTime.now(),
    ): EntryTable.Entry = EntryTable.Entry(
        contentType = "html",
        contentSrc = "",
        contentText = "",
        summary = "",
        id = UUID.randomUUID().toString(),
        feedId = feedId,
        title = "Entry",
        published = published,
        updated = published,
        authorName = "",
        extRead = false,
        extReadSynced = true,
        extBookmarked = false,
        extBookmarkedSynced = true,
        extCommentsUrl = "",
        extOpenGraphImageChecked = checked,
        extOpenGraphImageUrl = "",
        extOpenGraphImageWidth = 0,
        extOpenGraphImageHeight = 0,
        extOpenGraphImageFetchedAt = null,
        extOpenGraphImageLog = "[]",
    ).also { db.entry.insertOrReplace(listOf(it)) }
}
