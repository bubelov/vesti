package org.vestifeed.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who renders a running sync's progress: the entry list (its pull-to-refresh
 * indicator) or the app bar. Showing both is two indicators for one sync.
 */
class EntryListSyncProgressTest {

    @Test
    fun runningEntryListWithPullToRefresh_drawsTheProgressItself() {
        assertTrue(
            entryListShowsSyncProgress(
                running = true,
                onEntriesScreen = true,
                supportsPullToRefresh = true,
            ),
        )
    }

    @Test
    fun runningEntryListWithoutTheGesture_keepsTheAppBarIndicator() {
        assertFalse(
            entryListShowsSyncProgress(
                running = true,
                onEntriesScreen = true,
                supportsPullToRefresh = false,
            ),
        )
    }

    @Test
    fun runningOnAnotherScreen_keepsTheAppBarIndicator() {
        assertFalse(
            entryListShowsSyncProgress(
                running = true,
                onEntriesScreen = false,
                supportsPullToRefresh = true,
            ),
        )
    }

    @Test
    fun idleSync_drawsNoListIndicator() {
        assertFalse(
            entryListShowsSyncProgress(
                running = false,
                onEntriesScreen = true,
                supportsPullToRefresh = true,
            ),
        )
    }
}
