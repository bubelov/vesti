package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.vestifeed.sync.SyncReason

class ScrollToTopTest {

    @Test
    fun userSyncAlwaysReturnsToTheTop() {
        assertTrue(shouldScrollToTopFor(SyncReason.User, newEntries = 0))
        assertTrue(shouldScrollToTopFor(SyncReason.User, newEntries = 12))
    }

    @Test
    fun startupSyncReturnsToTheTopOnlyWhenItFetchedEntries() {
        assertFalse(shouldScrollToTopFor(SyncReason.Startup, newEntries = 0))
        assertTrue(shouldScrollToTopFor(SyncReason.Startup, newEntries = 1))
    }

    @Test
    fun backgroundSyncNeverStealsThePosition() {
        assertFalse(shouldScrollToTopFor(SyncReason.Background, newEntries = 5))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pendingScrollMovesTheListBackToTheFirstItem() = runComposeUiTest {
        var pending by mutableStateOf(false)
        lateinit var listState: LazyListState

        setContent {
            listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("list"),
            ) {
                items(100) { index ->
                    Text("Item $index", modifier = Modifier.testTag("item-$index"))
                }
            }
            ScrollToTopEffect(
                pending = pending,
                onHandled = { pending = false },
                scroll = { listState.animateScrollToItem(0) },
            )
        }

        onNodeWithTag("list").performScrollToIndex(50)
        onNodeWithTag("item-0").assertDoesNotExist()

        runOnIdle { pending = true }
        waitUntil(timeoutMillis = 5_000) { listState.firstVisibleItemIndex == 0 }
        onNodeWithTag("item-0").assertIsDisplayed()
    }

    @Test
    fun removingAPartlyHiddenFirstRowSnapsToTheTop() {
        assertTrue(
            shouldSnapToTopAfterRemoval(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 40,
                removedItemRow = 0,
            ),
        )
    }

    @Test
    fun removingAFirstRowItemAtTheVeryTopKeepsThePosition() {
        assertFalse(
            shouldSnapToTopAfterRemoval(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                removedItemRow = 0,
            ),
        )
    }

    @Test
    fun removingARowFurtherDownKeepsThePosition() {
        assertFalse(
            shouldSnapToTopAfterRemoval(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 40,
                removedItemRow = 1,
            ),
        )
        assertFalse(
            shouldSnapToTopAfterRemoval(
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffset = 40,
                removedItemRow = 0,
            ),
        )
        assertFalse(
            shouldSnapToTopAfterRemoval(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 40,
                removedItemRow = null,
            ),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun snappingAfterRemovingTheFirstItemRevealsTheNextItemAtTheTop() = runComposeUiTest {
        var items by mutableStateOf((0 until 100).toList())
        lateinit var listState: LazyListState

        setContent {
            listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { it }) { index ->
                    Text(
                        text = "Item $index",
                        modifier = Modifier.height(100.dp).testTag("item-$index"),
                    )
                }
            }
        }

        // Hide the top 40 dp of the first item, as a small scroll down does.
        runOnIdle { listState.requestScrollToItem(0, 40) }
        waitUntil(timeoutMillis = 5_000) {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 40
        }

        runOnIdle {
            val first = listState.layoutInfo.visibleItemsInfo.first()
            if (shouldSnapToTopAfterRemoval(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    removedItemRow = if (first.key == items.first()) 0 else null,
                )
            ) {
                listState.requestScrollToItem(0, 0)
            }
            items = items.drop(1)
        }
        waitForIdle()

        runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            assertEquals(1, listState.layoutInfo.visibleItemsInfo.first().key)
        }
        onNodeWithTag("item-1").assertIsDisplayed()
    }
}
