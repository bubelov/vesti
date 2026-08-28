package org.vestifeed.entries

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
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

class UnreadTabWithTwentyEntriesTest {

    private val entryIds = mutableListOf<String>()

    @Test
    fun launchesOnUnreadTabWithTwentyUnreadEntries() {
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

        val feedId = "unread-tab-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Unread Tab Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val baseTime = OffsetDateTime.now()
        val entries = (1..ENTRY_COUNT).map { i ->
            val id = UUID.randomUUID().toString()
            entryIds.add(id)
            newEntry(feedId, id, "Entry $i", baseTime.minusMinutes(i.toLong()))
        }
        db.entry.insertOrReplace(entries)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, ENTRY_COUNT, LOAD_TIMEOUT_MILLIS)

            val secondEntryId = entryIds[1]
            val secondEntryTitle = "Entry 2"

            onView(withId(R.id.list)).perform(swipeItemLeft(position = 1))

            waitForListItemCount(scenario, ENTRY_COUNT - 1, EMPTY_TIMEOUT_MILLIS)
            assertEquals(
                "Expected the swiped entry to be marked as read",
                true,
                db.entry.selectById(secondEntryId)?.extRead,
            )

            scrollListToBottomFast(scenario)

            val positionBeforeUndo = readFirstVisiblePosition(scenario)
            assertTrue(
                "Expected the list to be scrolled far from position 1 before undo, " +
                    "but the first visible position was $positionBeforeUndo",
                positionBeforeUndo >= MIN_SCROLLED_POSITION,
            )

            onView(allOf(withText(R.string.undo), isDisplayed())).perform(click())

            waitForListItemCount(scenario, ENTRY_COUNT, RESTORE_TIMEOUT_MILLIS)
            assertEquals(
                "Expected the swiped entry to be marked as unread again after undo",
                false,
                db.entry.selectById(secondEntryId)?.extRead,
            )

            val restoredPosition = waitForSecondEntryVisible(scenario, secondEntryTitle)
            assertEquals(
                "Expected the list to scroll so the re-appeared second entry is the " +
                    "first visible item, but the first visible position was $restoredPosition",
                1,
                restoredPosition,
            )
        }
    }

    private fun waitForSecondEntryVisible(
        scenario: ActivityScenario<Activity>,
        title: String,
    ): Int {
        val deadline = SystemClock.uptimeMillis() + RESTORE_TIMEOUT_MILLIS
        var lastPosition = RecyclerView.NO_POSITION
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                val layoutManager = list.layoutManager as LinearLayoutManager
                val first = layoutManager.findFirstVisibleItemPosition()
                lastPosition = first
                if (first == RecyclerView.NO_POSITION) return@onActivity
                val holder = list.findViewHolderForAdapterPosition(first) ?: return@onActivity
                val titleView = holder.itemView.findViewById<TextView>(R.id.primaryText)
                if (first == 1 && titleView?.text?.toString() == title) {
                    lastPosition = first
                    return@onActivity
                }
            }
            if (lastPosition == 1) return lastPosition
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return lastPosition
    }

    private fun scrollListToBottomFast(scenario: ActivityScenario<Activity>) {
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                list.adapter!!.itemCount - 1,
                0,
            )
        }
        SystemClock.sleep(SCROLL_SETTLE_MILLIS)
    }

    private fun readFirstVisiblePosition(scenario: ActivityScenario<Activity>): Int {
        var position = RecyclerView.NO_POSITION
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val layoutManager = list.layoutManager as LinearLayoutManager
            position = layoutManager.findFirstVisibleItemPosition()
        }
        return position
    }

    private fun newEntry(
        feedId: String,
        id: String,
        title: String,
        published: OffsetDateTime,
    ): EntryTable.Entry {
        return EntryTable.Entry(
            contentType = null,
            contentSrc = null,
            contentText = null,
            summary = "Summary for $title",
            id = id,
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

    private fun swipeItemLeft(position: Int): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                allOf(withId(R.id.list))

            override fun getDescription() = "swipe list item at $position to the left"

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val holder = recyclerView.findViewHolderForAdapterPosition(position)
                    ?: error("Expected list item at position $position to be bound")
                val itemView = holder.itemView
                val location = IntArray(2)
                recyclerView.getLocationOnScreen(location)
                val startX = (location[0] + itemView.width - EDGE_MARGIN_PX).toFloat()
                val endX = (location[0] + EDGE_MARGIN_PX).toFloat()
                val y = (location[1] + itemView.top + itemView.height / 2).toFloat()
                val distance = startX - endX

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
                    val x = startX - distance * fraction
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

    private companion object {
        const val ENTRY_COUNT = 20
        const val MIN_SCROLLED_POSITION = 10
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val EMPTY_TIMEOUT_MILLIS = 15_000L
        const val RESTORE_TIMEOUT_MILLIS = 15_000L
        const val SCROLL_SETTLE_MILLIS = 500L
        const val POLL_INTERVAL_MILLIS = 200L
        const val SWIPE_DURATION_MILLIS = 250L
        const val SWIPE_STEPS = 25
        const val MOTION_TICK_MILLIS = 12L
        const val EDGE_MARGIN_PX = 80
    }
}
