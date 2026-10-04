package org.vestifeed.og

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.db.testDb
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.util.toInstant

class OgImagePlanningTest {

    private lateinit var db: Database

    @Before
    fun before() = runBlocking {
        db = testDb()
        db.conf.insert(ConfTable.defaultConf())
    }

    // ---------------------------------------------------------------------
    // Pure decision function — mirrors the cases in EntryRowMapperTest so
    // the gating logic and the render logic can never disagree.
    // ---------------------------------------------------------------------

    @Test
    fun shouldFetchOgImage_explicitShowOverridesGlobalHide() {
        assertTrue(OgImagePlanning.shouldFetchOgImage(perFeed = true, global = false))
    }

    @Test
    fun shouldFetchOgImage_explicitHideOverridesGlobalShow() {
        assertFalse(OgImagePlanning.shouldFetchOgImage(perFeed = false, global = true))
    }

    @Test
    fun shouldFetchOgImage_followSettingsFollowsGlobalOn() {
        assertTrue(OgImagePlanning.shouldFetchOgImage(perFeed = null, global = true))
    }

    @Test
    fun shouldFetchOgImage_followSettingsFollowsGlobalOff() {
        assertFalse(OgImagePlanning.shouldFetchOgImage(perFeed = null, global = false))
    }

    // ---------------------------------------------------------------------
    // runOnce short-circuit — gates the entire iteration behind (online,
    // foreground) so the fetcher doesn't issue network requests when either
    // is missing.
    // ---------------------------------------------------------------------

    @Test
    fun ogRunSkip_offlineShortCircuitsRegardlessOfForeground() {
        assertEquals(
            OgRunSkip.Offline,
            OgImagePlanning.ogRunSkip(isOnline = false, isForeground = true),
        )
        assertEquals(
            OgRunSkip.Offline,
            OgImagePlanning.ogRunSkip(isOnline = false, isForeground = false),
        )
    }

    @Test
    fun ogRunSkip_foregroundMissingWhenOnlineShortCircuits() {
        assertEquals(
            OgRunSkip.NotForeground,
            OgImagePlanning.ogRunSkip(isOnline = true, isForeground = false),
        )
    }

    @Test
    fun ogRunSkip_onlineAndForegroundProceeds() {
        assertEquals(
            OgRunSkip.No,
            OgImagePlanning.ogRunSkip(isOnline = true, isForeground = true),
        )
    }

    // ---------------------------------------------------------------------
    // End-to-end gating plan — combines the global setting and the batch
    // shape into the next action the fetcher should take.
    // ---------------------------------------------------------------------

    @Test
    fun plan_globalOff_returnsGlobalOffRegardlessOfCandidates() = runBlocking {
        db.conf.update { it.copy(showPreviewImages = false) }
        val follow = candidate()
        val show = candidate()

        assertEquals(
            OgFetchPlan.GlobalOff,
            OgImagePlanning.planOgImageFetch(
                conf = db.conf.select(),
                candidates = listOf(follow, show),
            ),
        )
    }

    @Test
    fun plan_globalOnNoCandidates_returnsEmpty() = runBlocking {
        assertEquals(
            OgFetchPlan.Empty,
            OgImagePlanning.planOgImageFetch(
                conf = db.conf.select(),
                candidates = emptyList(),
            ),
        )
    }

    @Test
    fun plan_globalOnWithCandidates_returnsFetch() = runBlocking {
        val a = candidate()
        val b = candidate()

        val plan = OgImagePlanning.planOgImageFetch(
            conf = db.conf.select(),
            candidates = listOf(a, b),
        )

        assertEquals(OgFetchPlan.Fetch(candidates = listOf(a, b)), plan)
    }

    @Test
    fun plan_globalOffEvenWithCandidates_returnsGlobalOff() = runBlocking {
        // The global gate wins over the batch composition: if the user has
        // globally disabled preview images, no per-feed entry should ever
        // reach the fetcher.
        db.conf.update { it.copy(showPreviewImages = false) }
        val a = candidate()
        val b = candidate()

        assertEquals(
            OgFetchPlan.GlobalOff,
            OgImagePlanning.planOgImageFetch(
                conf = db.conf.select(),
                candidates = listOf(a, b),
            ),
        )
    }

    // ---------------------------------------------------------------------
    // SQL query — confirms the WHERE clause drops already-checked rows and
    // skips rows whose feed has previews explicitly disabled.
    // ---------------------------------------------------------------------

    @Test
    fun selectPendingOgImageEntries_returnsOnlyUncheckedAndEligibleCandidates() = runBlocking {
        val showFeed = insertFeed(extShowPreviewImages = true)
        val hideFeed = insertFeed(extShowPreviewImages = false)
        val followFeed = insertFeed(extShowPreviewImages = null)

        val showEntry = insertEntry(feedId = showFeed.id, checked = false)
        insertEntry(feedId = showFeed.id, checked = true) // already checked → dropped
        insertEntry(feedId = hideFeed.id, checked = false) // per-feed hidden → dropped
        val followEntry = insertEntry(feedId = followFeed.id, checked = false) // null → eligible

        val result = db.entry.selectPendingOgImageEntries(limit = 50)

        assertEquals(
            setOf(showEntry.id, followEntry.id),
            result.map { it.id }.toSet(),
        )
    }

    @Test
    fun selectPendingOgImageEntries_waitsForAlternateLink() = runBlocking {
        // Sync inserts an entry and its links separately; a candidate that
        // appears before its alternate link must not be selected yet, or the
        // fetcher would terminally mark it checked and lose the image.
        val feed = insertFeed(extShowPreviewImages = true)
        val linked = insertEntry(feedId = feed.id, checked = false)
        val linkless = insertEntry(feedId = feed.id, checked = false, withAlternateLink = false)

        assertEquals(
            listOf(linked.id),
            db.entry.selectPendingOgImageEntries(limit = 50).map { it.id },
        )

        db.link.insertForEntry(entryId = linkless.id, links = listOf(alternateLink(linkless.id)))

        assertEquals(
            setOf(linked.id, linkless.id),
            db.entry.selectPendingOgImageEntries(limit = 50).map { it.id }.toSet(),
        )
    }

    @Test
    fun selectPendingOgImageEntries_includesRowOnceUserReEnablesFeed() = runBlocking {
        // The user toggled the feed "hide previews" off after entries were
        // added under it; the per-feed-hidden filter must release them so the
        // fetcher picks them up on the next iteration.
        val feed = insertFeed(extShowPreviewImages = false)
        val entry = insertEntry(feedId = feed.id, checked = false)
        assertEquals(emptyList<String>(), db.entry.selectPendingOgImageEntries(limit = 50).map { it.id })

        db.feed.insertOrReplace(feed.copy(extShowPreviewImages = true))

        assertEquals(listOf(entry.id), db.entry.selectPendingOgImageEntries(limit = 50).map { it.id })
    }

    @Test
    fun selectPendingOgImageEntries_excludesOrphanEntries() = runBlocking {
        val feed = insertFeed(extShowPreviewImages = true)
        val attached = insertEntry(feedId = feed.id, checked = false)
        // Entry whose feed was deleted — must not appear in the result because
        // the JOIN can't resolve it and there's no useful work the fetcher
        // could do for it anyway.
        insertEntry(feedId = "ghost-feed-id", checked = false)

        val result = db.entry.selectPendingOgImageEntries(limit = 50)

        assertEquals(listOf(attached.id), result.map { it.id })
    }

    @Test
    fun selectPendingOgImageEntries_orderedByPublishedDesc() = runBlocking {
        val feed = insertFeed(extShowPreviewImages = true)
        val older = insertEntry(
            feedId = feed.id,
            checked = false,
            published = "2024-01-01T00:00:00Z".toInstant(),
        )
        val newer = insertEntry(
            feedId = feed.id,
            checked = false,
            published = "2024-06-01T00:00:00Z".toInstant(),
        )

        val result = db.entry.selectPendingOgImageEntries(limit = 50)

        assertEquals(listOf(newer.id, older.id), result.map { it.id })
    }

    @Test
    fun selectPendingOgImageEntries_respectsLimit() = runBlocking {
        val feed = insertFeed(extShowPreviewImages = true)
        repeat(5) { insertEntry(feedId = feed.id, checked = false) }

        val result = db.entry.selectPendingOgImageEntries(limit = 3)

        assertEquals(3, result.size)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun candidate(): EntryTable.OgImageCandidate =
        EntryTable.OgImageCandidate(
            id = UUID.randomUUID().toString(),
            title = "Some Title",
            extOpenGraphImageLog = "[]",
        )

    private suspend fun insertFeed(extShowPreviewImages: Boolean?): FeedTable.Feed =
        FeedTable.Feed(
            id = UUID.randomUUID().toString(),
            title = "Feed",
            extOpenEntriesInBrowser = null,
            extBlockedWords = "",
            extShowPreviewImages = extShowPreviewImages,
        ).also { db.feed.insertOrReplace(it) }

    private suspend fun insertEntry(
        feedId: String,
        checked: Boolean,
        published: Instant = Clock.System.now(),
        withAlternateLink: Boolean = true,
    ): EntryTable.Entry {
        val entry = EntryTable.Entry(
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
        )
        db.entry.insertOrReplace(listOf(entry))
        if (withAlternateLink) {
            db.link.insertForEntry(entryId = entry.id, links = listOf(alternateLink(entry.id)))
        }
        return entry
    }

    private fun alternateLink(entryId: String): LinkTable.Link =
        LinkTable.Link(
            id = null,
            feedId = null,
            entryId = entryId,
            href = "https://example.com/$entryId",
            rel = AtomLinkRel.Alternate,
            type = "text/html",
            hreflang = null,
            title = null,
            length = null,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        )
}
