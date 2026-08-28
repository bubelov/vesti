package org.vestifeed.entries

import android.os.SystemClock
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.R
import org.vestifeed.app.db
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.navigation.Activity
import java.time.OffsetDateTime
import java.util.UUID

class BottomNavReselectScrollsToTopTest {

    @Test
    fun reselectingUnreadTabScrollsListToTop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = context.db()
        db.conf.update {
            it.copy(backend = ConfTable.Backend.Embedded, syncOnStartup = false)
        }
        db.transaction {
            db.link.deleteAll()
            db.entry.deleteAll()
            db.feed.deleteAll()
        }

        val feedId = "unread-reselect-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Unread Reselect Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val baseTime = OffsetDateTime.now()
        val entries = (1..ENTRY_COUNT).map { i ->
            newEntry(feedId, "Entry $i", baseTime.minusMinutes(i.toLong()))
        }
        db.entry.insertOrReplace(entries)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, ENTRY_COUNT, LOAD_TIMEOUT_MILLIS)

            scrollListTo(scenario, SCROLL_TARGET_POSITION)
            val (positionBefore, topEntryBefore) = readFirstVisiblePosition(scenario)
            assertEquals(
                "Expected the list to be scrolled away from the top before the reselect",
                SCROLL_TARGET_POSITION,
                positionBefore,
            )
            assertNotEquals(
                "Expected the top entry before reselect to be 'Entry 1' (not at position 0)",
                "Entry 1",
                topEntryBefore,
            )

            onView(withId(R.id.newsFragment)).perform(click())

            val (positionAfter, topEntryAfter) = waitForFirstVisibleEntry(
                scenario,
                expectedTitle = "Entry 1",
                timeoutMillis = SCROLL_SETTLE_MILLIS,
            )
            assertEquals(
                "Expected reselecting the Unread tab to scroll the list back to position 0",
                0,
                positionAfter,
            )
            assertEquals(
                "Expected the top entry after reselect to be 'Entry 1'",
                "Entry 1",
                topEntryAfter,
            )
        }
    }

    @Test
    fun reselectingBookmarksTabScrollsListToTop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = context.db()
        db.conf.update {
            it.copy(backend = ConfTable.Backend.Embedded, syncOnStartup = false)
        }
        db.transaction {
            db.link.deleteAll()
            db.entry.deleteAll()
            db.feed.deleteAll()
        }

        val feedId = "bookmarks-reselect-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Bookmarks Reselect Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val baseTime = OffsetDateTime.now()
        val entries = (1..ENTRY_COUNT).map { i ->
            newEntry(feedId, "Bookmark $i", baseTime.minusMinutes(i.toLong()))
                .copy(extBookmarked = true)
        }
        db.entry.insertOrReplace(entries)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            onView(withId(R.id.bookmarksFragment)).perform(click())
            waitForListItemCount(scenario, ENTRY_COUNT, LOAD_TIMEOUT_MILLIS)

            scrollListTo(scenario, SCROLL_TARGET_POSITION)
            val (positionBefore, topEntryBefore) = readFirstVisiblePosition(scenario)
            assertEquals(
                "Expected the list to be scrolled away from the top before the reselect",
                SCROLL_TARGET_POSITION,
                positionBefore,
            )
            assertNotEquals(
                "Expected the top entry before reselect to be 'Bookmark 1' (not at position 0)",
                "Bookmark 1",
                topEntryBefore,
            )

            onView(withId(R.id.bookmarksFragment)).perform(click())

            val (positionAfter, topEntryAfter) = waitForFirstVisibleEntry(
                scenario,
                expectedTitle = "Bookmark 1",
                timeoutMillis = SCROLL_SETTLE_MILLIS,
            )
            assertEquals(
                "Expected reselecting the Bookmarks tab to scroll the list back to position 0",
                0,
                positionAfter,
            )
            assertEquals(
                "Expected the top entry after reselect to be 'Bookmark 1'",
                "Bookmark 1",
                topEntryAfter,
            )
        }
    }

    private fun scrollListTo(scenario: ActivityScenario<Activity>, position: Int) {
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
        val deadline = SystemClock.uptimeMillis() + SCROLL_SETTLE_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val firstVisible = readFirstVisiblePosition(scenario).first
            if (firstVisible == position) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "Expected the list to settle at position $position but was at ${readFirstVisiblePosition(scenario).first}",
        )
    }

    /**
     * Scroll the list down by a fixed pixel distance. More reliable than
     * `scrollToPosition` for the Feeds list, whose items are tall enough
     * that a small target position may already be visible on first render
     * (so position 0 stays at the top). We just need the list to be off
     * the top, not at a specific position.
     */
    private fun scrollListDown(scenario: ActivityScenario<Activity>) {
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            list.scrollBy(0, FEEDS_SCROLL_DISTANCE_PX)
        }
        val deadline = SystemClock.uptimeMillis() + SCROLL_SETTLE_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val firstVisible = readFirstVisiblePosition(scenario).first
            if (firstVisible > 0) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "Expected the list to scroll past position 0 but was at ${readFirstVisiblePosition(scenario).first}",
        )
    }

    @Test
    fun reselectingFeedsTabScrollsListToTop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = context.db()
        db.conf.update {
            it.copy(backend = ConfTable.Backend.Embedded, syncOnStartup = false)
        }
        db.transaction {
            db.link.deleteAll()
            db.entry.deleteAll()
            db.feed.deleteAll()
        }

        val feeds = (1..FEEDS_COUNT).map { i ->
            FeedTable.Feed(
                id = "feeds-reselect-feed-$i-${UUID.randomUUID()}",
                title = "Feed $i",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            )
        }
        db.feed.insertOrReplace(feeds)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            onView(withId(R.id.feedsFragment)).perform(click())
            waitForListItemCount(scenario, FEEDS_COUNT, LOAD_TIMEOUT_MILLIS)

            scrollListDown(scenario)
            val (positionBefore, topEntryBefore) = readFirstVisiblePosition(scenario)
            assertTrue(
                "Expected the feeds list to be scrolled away from the top before the reselect, " +
                    "but the first visible position was $positionBefore",
                positionBefore > 0,
            )
            assertNotEquals(
                "Expected the top entry before reselect to be 'Feed 1' (not at position 0)",
                "Feed 1",
                topEntryBefore,
            )

            onView(withId(R.id.feedsFragment)).perform(click())

            val (positionAfter, topEntryAfter) = waitForFirstVisibleEntry(
                scenario,
                expectedTitle = "Feed 1",
                timeoutMillis = SCROLL_SETTLE_MILLIS,
            )
            assertEquals(
                "Expected reselecting the Feeds tab to scroll the list back to position 0",
                0,
                positionAfter,
            )
            assertEquals(
                "Expected the top entry after reselect to be 'Feed 1'",
                "Feed 1",
                topEntryAfter,
            )
        }
    }

    @Test
    fun reselectingUnreadTabCancelsInProgressFling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = context.db()
        db.conf.update {
            it.copy(backend = ConfTable.Backend.Embedded, syncOnStartup = false)
        }
        db.transaction {
            db.link.deleteAll()
            db.entry.deleteAll()
            db.feed.deleteAll()
        }

        val feedId = "fling-reselect-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Fling Reselect Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val baseTime = OffsetDateTime.now()
        val entries = (1..ENTRY_COUNT).map { i ->
            newEntry(feedId, "Entry $i", baseTime.minusMinutes(i.toLong()))
        }
        db.entry.insertOrReplace(entries)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, ENTRY_COUNT, LOAD_TIMEOUT_MILLIS)

            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                list.fling(0, FLING_VELOCITY)
            }

            onView(withId(R.id.newsFragment)).perform(click())

            val (positionAfter, topEntryAfter) = waitForFirstVisibleEntry(
                scenario,
                expectedTitle = "Entry 1",
                timeoutMillis = SCROLL_SETTLE_MILLIS,
            )
            assertEquals(
                "Expected reselecting the Unread tab while a fling is in progress to stop the " +
                    "fling and snap the list to position 0, but the first visible position was " +
                    "$positionAfter",
                0,
                positionAfter,
            )
            assertEquals(
                "Expected the top entry after reselect to be 'Entry 1'",
                "Entry 1",
                topEntryAfter,
            )
        }
    }

    private fun readFirstVisiblePosition(scenario: ActivityScenario<Activity>): Pair<Int, String> {
        var position = RecyclerView.NO_POSITION
        var title = ""
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val layoutManager = list.layoutManager as LinearLayoutManager
            val first = layoutManager.findFirstVisibleItemPosition()
            position = first
            if (first != RecyclerView.NO_POSITION) {
                val holder = list.findViewHolderForAdapterPosition(first) ?: return@onActivity
                title = holder.itemView.findViewById<TextView>(R.id.primaryText)?.text?.toString() ?: ""
            }
        }
        return position to title
    }

    private fun waitForFirstVisibleEntry(
        scenario: ActivityScenario<Activity>,
        expectedTitle: String,
        timeoutMillis: Long,
    ): Pair<Int, String> {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var last = RecyclerView.NO_POSITION to ""
        while (SystemClock.uptimeMillis() < deadline) {
            val current = readFirstVisiblePosition(scenario)
            last = current
            if (current.first == 0 && current.second == expectedTitle) return current
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return last
    }

    private fun waitForListItemCount(
        scenario: ActivityScenario<Activity>,
        expected: Int,
        timeoutMillis: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastCount = -1
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                lastCount = activity.findViewById<RecyclerView>(R.id.list).adapter?.itemCount ?: 0
            }
            if (lastCount == expected) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Expected the list to have $expected items but had $lastCount")
    }

    private fun newEntry(
        feedId: String,
        title: String,
        published: OffsetDateTime,
    ): EntryTable.Entry {
        return EntryTable.Entry(
            contentType = null,
            contentSrc = null,
            contentText = null,
            summary = "Summary for $title",
            id = UUID.randomUUID().toString(),
            feedId = feedId,
            title = title,
            published = published,
            updated = published,
            authorName = "Author",
            extRead = false,
            extReadSynced = true,
            extBookmarked = false,
            extBookmarkedSynced = true,
            extCommentsUrl = "",
            extOpenGraphImageChecked = true,
            extOpenGraphImageUrl = "",
            extOpenGraphImageWidth = 0,
            extOpenGraphImageHeight = 0,
            extOpenGraphImageFetchedAt = null,
            extOpenGraphImageLog = "[]",
        )
    }

    private companion object {
        const val ENTRY_COUNT = 20
        const val FEEDS_COUNT = 20
        const val SCROLL_TARGET_POSITION = 8
        const val FEEDS_SCROLL_DISTANCE_PX = 2_000
        const val FLING_VELOCITY = 15_000
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val SCROLL_SETTLE_MILLIS = 3_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
