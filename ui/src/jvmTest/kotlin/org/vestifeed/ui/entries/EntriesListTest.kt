package org.vestifeed.ui.entries

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.vestifeed.ui.EntriesList

class EntriesListTest {

    @Test
    fun unread_whileSyncingWithNothingYet_isAwaitingSync() {
        assertTrue(
            EntriesList.Unread.isAwaitingSync(
                syncPending = true,
                rowCount = 0,
                feedCount = 0,
            ),
        )
    }

    @Test
    fun unread_withSyncedFeeds_isNotAwaitingSync() {
        assertFalse(
            EntriesList.Unread.isAwaitingSync(
                syncPending = true,
                rowCount = 0,
                feedCount = 3,
            ),
        )
    }

    @Test
    fun unread_withRows_isNotAwaitingSync() {
        assertFalse(
            EntriesList.Unread.isAwaitingSync(
                syncPending = true,
                rowCount = 5,
                feedCount = 0,
            ),
        )
    }

    @Test
    fun unread_whenIdle_isNotAwaitingSync() {
        assertFalse(
            EntriesList.Unread.isAwaitingSync(
                syncPending = false,
                rowCount = 0,
                feedCount = 0,
            ),
        )
    }

    @Test
    fun otherLists_neverAwaitSync() {
        assertFalse(EntriesList.Bookmarked.isAwaitingSync(true, 0, 0))
        assertFalse(EntriesList.BelongToFeed("f").isAwaitingSync(true, 0, 0))
        assertFalse(EntriesList.BelongToTag("t").isAwaitingSync(true, 0, 0))
    }
}
