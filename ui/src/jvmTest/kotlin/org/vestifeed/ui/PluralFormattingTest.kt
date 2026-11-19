package org.vestifeed.ui

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.tag_feed_count
import org.vestifeed.ui.resources.time_hours_ago
import org.vestifeed.ui.resources.time_minutes_ago
import org.vestifeed.ui.resources.unread_entry_count

/**
 * Compose Resources only substitutes *positional* specifiers; a plural written
 * with a bare `%d` is rendered literally, which is how the feeds screen came to
 * say "%d unread entries" instead of a number. The assertions here are
 * locale-independent on purpose: the quantity forms differ per language, but
 * every one of them must contain the number and no leftover specifier.
 */
class PluralFormattingTest {

    @Test
    fun unreadEntryCountSubstitutesTheNumber() = runBlocking {
        listOf(0, 1, 5, 11, 1234).forEach { count ->
            val text = getPluralString(Res.plurals.unread_entry_count, count, count)

            assertTrue("expected $count in \"$text\"", text.contains(count.toString()))
            assertFalse("unsubstituted specifier in \"$text\"", text.contains("%"))
        }
    }

    @Test
    fun tagFeedCountSubstitutesTheNumber() = runBlocking {
        listOf(0, 1, 5, 11).forEach { count ->
            val text = getPluralString(Res.plurals.tag_feed_count, count, count)

            assertTrue("expected $count in \"$text\"", text.contains(count.toString()))
            assertFalse("unsubstituted specifier in \"$text\"", text.contains("%"))
        }
    }

    @Test
    fun relativeTimePluralsSubstituteTheNumber() = runBlocking {
        listOf(0, 1, 2, 5, 11, 22).forEach { count ->
            listOf(
                Res.plurals.time_minutes_ago,
                Res.plurals.time_hours_ago,
            ).forEach { plural ->
                val text = getPluralString(plural, count, count)

                assertTrue("expected $count in \"$text\"", text.contains(count.toString()))
                assertFalse("unsubstituted specifier in \"$text\"", text.contains("%"))
            }
        }
    }
}
