package org.vestifeed.entries

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.vestifeed.R
import org.vestifeed.app.db
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.navigation.Activity
import java.time.OffsetDateTime
import java.util.UUID

class UnreadPagerMarksAllAsReadOnSwipeTest {

    @Test
    fun swipingThroughAllUnreadEntriesLeavesTheTabEmpty() {
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

        val feedId = "pager-readthrough-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Read-through Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val now = OffsetDateTime.now()
        val entries = listOf(
            newEntry(feedId, "First", now),
            newEntry(feedId, "Second", now.minusMinutes(1)),
            newEntry(feedId, "Third", now.minusMinutes(2)),
        )
        db.entry.insertOrReplace(entries)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, 3, LOAD_TIMEOUT_MILLIS)

            clickListItem(scenario, 0)
            waitForPager(scenario, PAGER_TIMEOUT_MILLIS)

            waitForCurrentPage(scenario, 0, TITLE_TEXT_TIMEOUT_MILLIS)
            assertPageTitle(scenario, "First")

            onView(withId(R.id.pager)).perform(swipeLeftOnPager())
            waitForCurrentPage(scenario, 1, TITLE_TEXT_TIMEOUT_MILLIS)
            assertPageTitle(scenario, "Second")

            onView(withId(R.id.pager)).perform(swipeLeftOnPager())
            waitForCurrentPage(scenario, 2, TITLE_TEXT_TIMEOUT_MILLIS)
            assertPageTitle(scenario, "Third")

            pressBack()

            waitForListItemCount(scenario, 0, EMPTY_TIMEOUT_MILLIS)
        }
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
                isPager = activity.findViewById<ViewPager2>(R.id.pager) != null
            }
            if (isPager) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("UnreadPagerFragment did not appear within $timeoutMillis ms")
    }

    private fun waitForCurrentPage(
        scenario: ActivityScenario<Activity>,
        expected: Int,
        timeoutMillis: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastItem = -1
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                lastItem = activity.findViewById<ViewPager2>(R.id.pager)?.currentItem ?: -1
            }
            if (lastItem == expected) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Expected the pager to be on item $expected but was $lastItem")
    }

    private fun assertPageTitle(
        scenario: ActivityScenario<Activity>,
        expected: String,
    ) {
        scenario.onActivity { activity ->
            val pager = activity.findViewById<ViewPager2>(R.id.pager)
            var actual: String? = null
            for (i in 0 until pager.childCount) {
                val page = pager.getChildAt(i)
                val title = page.findViewById<android.widget.TextView>(R.id.title)
                if (title != null) {
                    actual = title.text.toString()
                    break
                }
            }
            assertEquals(
                "Expected the visible page to be titled '$expected' but was '$actual'",
                expected,
                actual,
            )
        }
    }

    private fun clickListItem(scenario: ActivityScenario<Activity>, position: Int) {
        val viewAction = object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                allOf(withId(R.id.list))

            override fun getDescription() = "click list item at position $position"

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val holder = recyclerView.findViewHolderForAdapterPosition(position)
                    ?: error("Expected a bound view holder at position $position")
                holder.itemView.performClick()
                uiController.loopMainThreadUntilIdle()
            }
        }
        onView(withId(R.id.list)).perform(viewAction)
    }

    private fun swipeLeftOnPager(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                allOf(withId(R.id.pager))

            override fun getDescription() = "swipe the pager one page to the left"

            override fun perform(uiController: UiController, view: View) {
                val pager = view as ViewPager2
                val width = pager.width.takeIf { it > 0 } ?: pager.measuredWidth
                check(width > 0) { "Pager has no width yet" }
                val distance = (width * 1.2f)

                val location = IntArray(2)
                pager.getLocationOnScreen(location)
                val startX = (location[0] + width - EDGE_MARGIN_PX).toFloat()
                val endX = (location[0] + EDGE_MARGIN_PX).toFloat()
                val y = (location[1] + pager.height / 2).toFloat()

                val startTime = SystemClock.uptimeMillis()
                val interpolator = LinearInterpolator()

                dispatchEvent(
                    uiController,
                    MotionEvent.obtain(
                        startTime,
                        startTime,
                        MotionEvent.ACTION_DOWN,
                        startX,
                        y,
                        0,
                    ),
                )

                val steps = SWIPE_STEPS
                for (i in 1..steps) {
                    val fraction = interpolator.getInterpolation(i.toFloat() / steps)
                    val x = startX + (endX - startX) * fraction
                    val eventTime = startTime + (SWIPE_DURATION_MILLIS * i / steps)
                    dispatchEvent(
                        uiController,
                        MotionEvent.obtain(
                            startTime,
                            eventTime,
                            MotionEvent.ACTION_MOVE,
                            x,
                            y,
                            0,
                        ),
                    )
                    uiController.loopMainThreadForAtLeast(MOTION_TICK_MILLIS)
                }

                dispatchEvent(
                    uiController,
                    MotionEvent.obtain(
                        startTime,
                        startTime + SWIPE_DURATION_MILLIS,
                        MotionEvent.ACTION_UP,
                        endX,
                        y,
                        0,
                    ),
                )
                uiController.loopMainThreadUntilIdle()
            }

            private fun dispatchEvent(uiController: UiController, event: MotionEvent) {
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    if (!uiController.injectMotionEvent(event)) {
                        throw AssertionError("Failed to inject motion event")
                    }
                } finally {
                    event.recycle()
                }
            }
        }
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

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val PAGER_TIMEOUT_MILLIS = 15_000L
        const val TITLE_TEXT_TIMEOUT_MILLIS = 5_000L
        const val EMPTY_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 200L
        const val SWIPE_DURATION_MILLIS = 250L
        const val SWIPE_STEPS = 25
        const val MOTION_TICK_MILLIS = 12L
        const val EDGE_MARGIN_PX = 80
    }
}
