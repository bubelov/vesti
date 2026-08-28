package org.vestifeed.entries

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
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

class UnreadEntriesSwipeTest {

    @Test
    fun swipeRightBookmarksEntryAndEmptiesList() {
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

        val feedId = "swipe-test-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Swipe Test Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val entry = newEntry(feedId, "Solo entry", OffsetDateTime.now())
        db.entry.insertOrReplace(listOf(entry))

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, 1, LOAD_TIMEOUT_MILLIS)

            onView(withId(R.id.list)).perform(swipeItemRight())

            waitForListItemCount(scenario, 0, EMPTY_TIMEOUT_MILLIS)

            scenario.onActivity {
                val list = it.findViewById<RecyclerView>(R.id.list)
                assertEquals(
                    "Expected the list to be empty after bookmarking the only entry",
                    0,
                    list.adapter?.itemCount ?: -1,
                )
                assertEquals(
                    "Expected the bookmarked entry to be persisted in the DB",
                    true,
                    db.entry.selectById(entry.id)?.extBookmarked,
                )
                assertEquals(
                    "Expected the entry to still be unread in the DB",
                    false,
                    db.entry.selectById(entry.id)?.extRead,
                )
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

    private fun swipeItemRight(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                allOf(withId(R.id.list))

            override fun getDescription() = "swipe the first list item to the right"

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val holder = recyclerView.findViewHolderForAdapterPosition(0)
                    ?: error("Expected the first list item to be bound before swiping")
                val itemView = holder.itemView
                val location = IntArray(2)
                recyclerView.getLocationOnScreen(location)
                val startY = (location[1] + itemView.top + itemView.height / 2).toFloat()
                val startX = (location[0] + EDGE_MARGIN_PX).toFloat()
                val endX = (location[0] + itemView.width - EDGE_MARGIN_PX).toFloat()
                val distance = endX - startX

                val startTime = SystemClock.uptimeMillis()
                val interpolator = LinearInterpolator()

                dispatchEvent(
                    uiController,
                    MotionEvent.obtain(
                        startTime,
                        startTime,
                        MotionEvent.ACTION_DOWN,
                        startX,
                        startY,
                        0,
                    ),
                )

                val steps = SWIPE_STEPS
                for (i in 1..steps) {
                    val fraction = interpolator.getInterpolation(i.toFloat() / steps)
                    val x = startX + distance * fraction
                    val eventTime = startTime + (SWIPE_DURATION_MILLIS * i / steps)
                    dispatchEvent(
                        uiController,
                        MotionEvent.obtain(
                            startTime,
                            eventTime,
                            MotionEvent.ACTION_MOVE,
                            x,
                            startY,
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
                        startY,
                        0,
                    ),
                )
                uiController.loopMainThreadUntilIdle()
            }

            private fun dispatchEvent(uiController: UiController, event: MotionEvent) {
                try {
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
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
        const val SWIPE_DURATION_MILLIS = 250L
        const val SWIPE_STEPS = 25
        const val MOTION_TICK_MILLIS = 12L
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val EMPTY_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 200L
        const val EDGE_MARGIN_PX = 80
    }
}
