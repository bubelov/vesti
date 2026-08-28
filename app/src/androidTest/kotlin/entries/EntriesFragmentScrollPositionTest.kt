package org.vestifeed.entries

import android.os.SystemClock
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.vestifeed.R
import org.vestifeed.app.db
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.navigation.Activity
import java.time.OffsetDateTime
import java.util.UUID

class EntriesFragmentScrollPositionTest {

    @Test
    fun scrollPositionIsRestoredAfterConfigurationChange() {
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

        val feedId = "scroll-test-feed-${UUID.randomUUID()}"
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Scroll Test Feed",
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

            val targetPosition = SCROLL_TARGET_POSITION
            scrollListTo(scenario, targetPosition)

            val (originalPosition, originalOffset) = readScrollPosition(scenario)
            assertEquals(
                "Expected the list to be scrolled to position $targetPosition before rotation",
                targetPosition,
                originalPosition,
            )
            assertNotEquals(
                "Expected the list to have a non-zero scroll offset before rotation",
                0,
                originalOffset,
            )

            scenario.recreate()

            waitForListItemCount(scenario, ENTRY_COUNT, LOAD_TIMEOUT_MILLIS)

            val (restoredPosition, restoredOffset) = readScrollPosition(scenario)
            assertEquals(
                "Expected the scroll position to survive the configuration change",
                originalPosition,
                restoredPosition,
            )
            assertEquals(
                "Expected the scroll offset to survive the configuration change",
                originalOffset,
                restoredOffset,
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
            val firstVisible = readScrollPosition(scenario).first
            if (firstVisible == position) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "Expected the list to settle at position $position but was at ${readScrollPosition(scenario).first}",
        )
    }

    private fun readScrollPosition(scenario: ActivityScenario<Activity>): Pair<Int, Int> {
        var position = RecyclerView.NO_POSITION
        var offset = 0
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val layoutManager = list.layoutManager as LinearLayoutManager
            position = layoutManager.findFirstVisibleItemPosition()
            if (position != RecyclerView.NO_POSITION) {
                offset = layoutManager.findViewByPosition(position)?.top ?: 0
            }
        }
        return position to offset
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
        const val ENTRY_COUNT = 30
        const val SCROLL_TARGET_POSITION = 8
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val SCROLL_SETTLE_MILLIS = 3_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
