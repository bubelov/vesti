package org.vestifeed.entries

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.R
import org.vestifeed.app.db
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.entry.UnreadPagerFragment
import org.vestifeed.navigation.Activity
import java.time.OffsetDateTime
import java.util.UUID

class UnreadPagerOpensClickedEntryTest {

    @Test
    fun clickingLatestUnreadOpensTheSameEntryNotTheNextOne() {
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

        val feedId = "pager-bug-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Pager Bug Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val now = OffsetDateTime.now()
        val newest = newEntry(feedId, "Newest", now)
        val second = newEntry(feedId, "Second", now.minusMinutes(1))
        val third = newEntry(feedId, "Third", now.minusMinutes(2))
        db.entry.insertOrReplace(listOf(newest, second, third))

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, 3, LOAD_TIMEOUT_MILLIS)
            clickListItem(scenario, 0)
            waitForPager(scenario, PAGER_TIMEOUT_MILLIS)

            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertTrue(
                    "Expected UnreadPagerFragment to be on top after clicking an unread entry",
                    activity.supportFragmentManager.findFragmentById(R.id.fragmentContainerView)
                        is UnreadPagerFragment,
                )

                val pager = activity.findViewById<ViewPager2>(R.id.pager)
                assertNotNull("Expected the unread pager to be present", pager)
                assertEquals(
                    "Expected the pager to start on the clicked entry (index 0)",
                    0,
                    pager.currentItem,
                )

                val titleText = findCurrentPageTitle(pager)
                assertEquals(
                    "Expected the visible page to be the clicked (newest) entry, " +
                        "not the second entry. shift-by-1 bug?",
                    "Newest",
                    titleText,
                )
            }
        }
    }

    @Test
    fun clickingSecondEntryOpensItNotTheNeighbour() {
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

        val feedId = "pager-bug-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Pager Bug Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val now = OffsetDateTime.now()
        val newest = newEntry(feedId, "Newest", now)
        val second = newEntry(feedId, "Second", now.minusMinutes(1))
        val third = newEntry(feedId, "Third", now.minusMinutes(2))
        db.entry.insertOrReplace(listOf(newest, second, third))

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, 3, LOAD_TIMEOUT_MILLIS)
            clickListItem(scenario, 1)
            waitForPager(scenario, PAGER_TIMEOUT_MILLIS)

            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager2>(R.id.pager)
                assertNotNull("Expected the unread pager to be present", pager)
                assertEquals(
                    "Expected the pager to start on the second entry (index 1)",
                    1,
                    pager.currentItem,
                )
                val titleText = findCurrentPageTitle(pager)
                assertEquals(
                    "Expected the visible page to be the clicked (second) entry, " +
                        "not the newest or the third",
                    "Second",
                    titleText,
                )
            }
        }
    }

    private fun findCurrentPageTitle(pager: ViewPager2): String {
        for (i in 0 until pager.childCount) {
            val page = pager.getChildAt(i)
            val title = page.findViewById<TextView>(R.id.title)
            if (title != null) return title.text.toString()
        }
        throw AssertionError("Could not find a page with a title TextView in the pager")
    }

    private fun clickListItem(scenario: ActivityScenario<Activity>, position: Int) {
        val viewAction = object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                ViewMatchers.isAssignableFrom(RecyclerView::class.java)

            override fun getDescription() = "click list item at position $position"

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val holder = recyclerView.findViewHolderForAdapterPosition(position)
                    ?: error("Expected a bound view holder at position $position")
                holder.itemView.performClick()
                uiController.loopMainThreadUntilIdle()
            }
        }
        androidx.test.espresso.Espresso.onView(ViewMatchers.withId(R.id.list))
            .perform(viewAction)
    }

    private fun newEntry(
        feedId: String,
        title: String,
        published: OffsetDateTime,
    ): EntryTable.Entry {
        return EntryTable.Entry(
            contentType = null,
            contentSrc = null,
            contentText = "<p>$title body</p>",
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

    private fun waitForPager(
        scenario: ActivityScenario<Activity>,
        timeoutMillis: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var isPager = false
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                isPager = activity.supportFragmentManager.findFragmentById(
                    R.id.fragmentContainerView,
                ) is UnreadPagerFragment
            }
            if (isPager) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("UnreadPagerFragment did not appear within $timeoutMillis ms")
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val PAGER_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
