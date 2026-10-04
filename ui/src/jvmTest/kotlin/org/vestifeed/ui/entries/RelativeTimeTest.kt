package org.vestifeed.ui.entries

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RelativeTimeTest {

    @Test
    fun formatCalendarDate_isHumanReadable() {
        assertEquals("Aug 12, 2026", formatCalendarDate(Instant.parse("2026-08-12T09:30:00Z")))
    }

    @Test
    fun formatCalendarDate_doesNotPadTheDay() {
        assertEquals("Jan 5, 2026", formatCalendarDate(Instant.parse("2026-01-05T23:59:00Z")))
    }

    @Test
    fun formatRelativeTime_olderThanDayUsesCalendarDate() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val published = Instant.parse("2026-08-12T09:30:00Z")
        assertEquals("Aug 12, 2026", formatRelativeTime(now, published))
    }

    @Test
    fun formatRelativeTime_recentEntriesStayRelative() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        assertEquals("2 h ago", formatRelativeTime(now, Instant.parse("2026-08-12T10:00:00Z")))
    }
}
