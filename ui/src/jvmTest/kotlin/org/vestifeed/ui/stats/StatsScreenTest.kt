package org.vestifeed.ui.stats

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class StatsScreenTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aRowWithDetailsExpandsWhenTapped() = runComposeUiTest {
        setContent {
            StatsScreen(
                sections = listOf(
                    StatsSection(
                        key = "tables",
                        title = "Tables",
                        entries = listOf(
                            StatsEntry(
                                label = "entry",
                                value = "3",
                                details = listOf(
                                    StatsEntry(label = "Rows", value = "3"),
                                    StatsEntry(label = "Unread", value = "1"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        val row = onNodeWithTag(statsEntryTag("tables", "entry"))
        row.assertTextContains("3")

        val detail = onNodeWithTag(statsDetailTag("tables", "entry", "Unread"))
        detail.assertDoesNotExist()

        row.performClick()
        detail.assertTextContains("1")
    }
}
