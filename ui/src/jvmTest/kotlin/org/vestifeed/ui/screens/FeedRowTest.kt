package org.vestifeed.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.no_unread_entries
import org.vestifeed.ui.resources.unread_entry_count

/**
 * The unread line under a feed's title.
 *
 * Expectations are resolved from the same resources rather than hard-coded, so
 * the test holds in any language: the assertions are about *which* wording is
 * chosen, not about English text.
 */
class FeedRowTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fullyReadFeedSaysNoUnreadEntriesInsteadOfZero() = runComposeUiTest {
        val expected = runBlocking { getString(Res.string.no_unread_entries) }

        setContent {
            FeedRow(
                title = "Example feed",
                unread = 0,
                onOpen = {},
                onSettings = {},
                onRemove = {},
            )
        }

        onNodeWithText(expected).assertTextContains(expected)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun feedWithUnreadEntriesShowsTheNumber() = runComposeUiTest {
        val expected = runBlocking { getPluralString(Res.plurals.unread_entry_count, 3, 3) }

        setContent {
            FeedRow(
                title = "Example feed",
                unread = 3,
                onOpen = {},
                onSettings = {},
                onRemove = {},
            )
        }

        onNodeWithText(expected).assertTextContains(expected)
        // Guards the regression this test was written for: an unformatted
        // specifier leaking into the line instead of the number.
        onNodeWithText("%", substring = true).assertDoesNotExist()
    }
}
