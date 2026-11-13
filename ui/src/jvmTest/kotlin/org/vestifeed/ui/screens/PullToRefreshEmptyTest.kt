package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PullToRefreshEmptyTest {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)
    @Test
    fun anEmptyCenteredStateStillDispatchesPullToRefresh() = runComposeUiTest {
        var refreshes = 0

        setContent {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { refreshes++ },
                modifier = Modifier.fillMaxSize().testTag("pull"),
            ) {
                EmptyPullableContainer {
                    Text("Nothing here")
                }
            }
        }

        onNodeWithTag("pull").performTouchInput { swipeDown() }
        waitUntil(timeoutMillis = 5_000) { refreshes > 0 }
        assertEquals(1, refreshes)
    }
}
