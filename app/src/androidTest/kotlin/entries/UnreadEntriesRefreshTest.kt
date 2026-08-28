package org.vestifeed.entries

import android.os.SystemClock
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

class UnreadEntriesRefreshTest {

    @Test
    fun pullToRefreshFetchesNewEntriesAndScrollsToTop() {
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

        val feedId = "refresh-test-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Refresh Test Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = false,
            ),
        )

        val baseTime = OffsetDateTime.now()
        val initialEntries = (1..ENTRY_COUNT).map { i ->
            newEntry(feedId, "Initial $i", baseTime.minusMinutes(i.toLong()))
        }
        db.entry.insertOrReplace(initialEntries)

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForListItemCount(scenario, ENTRY_COUNT, LOAD_TIMEOUT_MILLIS)

            val newEntries = (1..ENTRY_COUNT).map { i ->
                newEntry(feedId, "New $i", baseTime.plusMinutes(i.toLong()))
            }
            scenario.onActivity { db.entry.insertOrReplace(newEntries) }

            onView(withId(R.id.swipeRefresh)).perform(swipeDown())

            waitForListItemCount(
                scenario = scenario,
                expected = ENTRY_COUNT * 2,
                timeoutMillis = REFRESH_TIMEOUT_MILLIS,
            )

            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                val layoutManager = list.layoutManager as LinearLayoutManager
                assertEquals(
                    "Expected the list to be scrolled to the top after refresh",
                    0,
                    layoutManager.findFirstVisibleItemPosition(),
                )
                val firstHolder = list.findViewHolderForLayoutPosition(0)
                assertNotNull("Expected a view holder bound at position 0", firstHolder)
                val titleView = firstHolder!!.itemView.findViewById<TextView>(R.id.primaryText)
                val titleText = titleView.text.toString()
                assertTrue(
                    "Expected the top entry to be one of the freshly fetched ones but was '$titleText'",
                    titleText.startsWith("New "),
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

    private fun swipeDown(): GeneralSwipeAction = GeneralSwipeAction(
        Swipe.SLOW,
        GeneralLocation.TOP_CENTER,
        GeneralLocation.BOTTOM_CENTER,
        Press.FINGER,
    )

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
            if (lastCount >= expected) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Expected the list to have at least $expected items but had $lastCount")
    }

    private companion object {
        const val ENTRY_COUNT = 10
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val REFRESH_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
