package org.vestifeed.ui.entries

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RelativeTimeTest {

    private val now = Instant.parse("2026-08-12T12:00:00Z")

    @Test
    fun futureAndSubMinuteDatesAreJustNow() {
        assertEquals(
            RelativeTime.JustNow,
            RelativeTimeCalculator.compute(now, Instant.parse("2026-08-12T11:59:30Z")),
        )
        assertEquals(
            RelativeTime.JustNow,
            RelativeTimeCalculator.compute(now, Instant.parse("2026-08-12T12:30:00Z")),
        )
    }

    @Test
    fun underAnHourIsMinutes() {
        assertEquals(
            RelativeTime.MinutesAgo(2),
            RelativeTimeCalculator.compute(now, Instant.parse("2026-08-12T11:58:00Z")),
        )
        assertEquals(
            RelativeTime.MinutesAgo(59),
            RelativeTimeCalculator.compute(now, Instant.parse("2026-08-12T11:01:00Z")),
        )
    }

    @Test
    fun underADayIsHours() {
        assertEquals(
            RelativeTime.HoursAgo(3),
            RelativeTimeCalculator.compute(now, Instant.parse("2026-08-12T09:00:00Z")),
        )
    }

    @Test
    fun olderThanDayFallsBackToTheCalendarDate() {
        assertEquals(
            RelativeTime.OlderThanDay,
            RelativeTimeCalculator.compute(now, Instant.parse("2026-08-11T11:59:00Z")),
        )
    }
}
