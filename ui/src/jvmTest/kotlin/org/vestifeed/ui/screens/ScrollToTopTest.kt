package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
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
import kotlin.test.Test
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
}
