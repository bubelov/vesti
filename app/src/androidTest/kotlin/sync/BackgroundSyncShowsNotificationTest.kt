package org.vestifeed.sync

import android.Manifest
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vestifeed.app.App
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundSyncShowsNotificationTest {

    private lateinit var app: App

    @Before
    fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        app = context.applicationContext as App

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        app.db.conf.update {
            it.copy(
                backend = ConfTable.Backend.Embedded,
                syncOnStartup = false,
                minifluxIncrementalSyncTimestamp = "2020-01-01T00:00:00Z",
            )
        }

        app.db.transaction {
            app.db.link.deleteAll()
            app.db.entry.deleteAll()
            app.db.feed.deleteAll()
        }

        app.sync.unreadScreenVisible = false
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    @After
    fun tearDown() {
        app.sync.unreadScreenVisible = false
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    @Test
    fun backgroundSyncShowsNotificationFor20NewEntries() {
        val feedId = "notification-test-feed-${UUID.randomUUID()}"
        app.db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Notification Test Feed",
                extOpenEntriesInBrowser = null,
                extBlockedWords = "",
                extShowPreviewImages = null,
            ),
        )

        val baseTime = OffsetDateTime.now()
        val entries = (1..ENTRY_COUNT).map { i ->
            // Entry `i` is the i-th newest (published DESC => smallest offset first)
            EntryTable.Entry(
                contentType = "",
                contentSrc = "",
                contentText = "",
                summary = "",
                id = "notification-test-entry-$i-${UUID.randomUUID()}",
                feedId = feedId,
                title = "News $i",
                published = baseTime.minusMinutes(i.toLong()),
                updated = baseTime.minusMinutes(i.toLong()),
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
        app.db.entry.insertOrReplace(entries)

        val worker = TestWorkerBuilder.from(app, SyncWorker::class.java).build()
        val result = worker.doWork()

        assertTrue(
            "SyncWorker should succeed but was $result",
            result is ListenableWorker.Result.Success,
        )
        assertEquals(ENTRY_COUNT, app.db.entry.selectUnreadCount())

        val active = NotificationManagerCompat.from(app).activeNotifications
        val posted = active.firstOrNull { it.id == NOTIFICATION_ID }
        assertNotNull("Expected unread news notification to be posted", posted)

        val extras = posted!!.notification.extras
        val expectedTitle = app.resources.getQuantityString(
            org.vestifeed.R.plurals.you_have_d_unread_news,
            ENTRY_COUNT,
            ENTRY_COUNT,
        )
        assertEquals(
            "Expected notification header to be the plural unread-count line",
            expectedTitle,
            extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "Expected big content title to mirror the header",
            expectedTitle,
            extras.getString(NotificationCompat.EXTRA_TITLE_BIG),
        )

        val lines = extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
            ?.map { it.toString() }
            ?: emptyList()
        assertEquals(
            "Expected exactly $MAX_LINES lines (the $MAX_LINES most recent titles)",
            MAX_LINES,
            lines.size,
        )
        (1..MAX_LINES).forEach { i ->
            assertEquals(
                "Line ${i - 1} should be the $i-th most-recent title",
                "News $i",
                lines[i - 1],
            )
        }
        ((MAX_LINES + 1)..ENTRY_COUNT).forEach { i ->
            assertTrue(
                "Did not expect 'News $i' in body but lines were $lines",
                lines.none { it == "News $i" },
            )
        }
    }

    @Test
    fun backgroundSyncSkipsNotificationWhenUnreadScreenVisible() {
        val feedId = "notification-suppression-feed-${UUID.randomUUID()}"
        app.db.feed.insertOrReplace(
            FeedTable.Feed(
                id = feedId,
                title = "Suppression Test Feed",
                extOpenEntriesInBrowser = null,
                extBlockedWords = "",
                extShowPreviewImages = null,
            ),
        )

        val now = OffsetDateTime.now()
        val entry = EntryTable.Entry(
            contentType = "",
            contentSrc = "",
            contentText = "",
            summary = "",
            id = "notification-suppression-entry-${UUID.randomUUID()}",
            feedId = feedId,
            title = "News 1",
            published = now.minusMinutes(1),
            updated = now.minusMinutes(1),
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
        app.db.entry.insertOrReplace(listOf(entry))

        app.sync.unreadScreenVisible = true

        val worker = TestWorkerBuilder.from(app, SyncWorker::class.java).build()
        val result = worker.doWork()

        assertTrue(
            "SyncWorker should succeed but was $result",
            result is ListenableWorker.Result.Success,
        )
        assertEquals(1, app.db.entry.selectUnreadCount())

        val active = NotificationManagerCompat.from(app).activeNotifications
        val posted = active.firstOrNull { it.id == NOTIFICATION_ID }
        assertNull(
            "Expected no unread news notification while the unread screen is foregrounded",
            posted,
        )
    }

    private companion object {
        const val ENTRY_COUNT = 20
        const val MAX_LINES = 10
        const val NOTIFICATION_ID = 1
    }
}
