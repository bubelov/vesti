package org.vestifeed.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class PruneScheduleTest {

    private val now = Instant.parse("2026-11-12T12:00:00Z")

    @Test
    fun neverPruned_isDue() {
        assertTrue(isPruneDue(lastPruneAt = null, now = now))
    }

    @Test
    fun lessThanADayAgo_isNotDue() {
        assertFalse(isPruneDue((now - 23.hours).toString(), now))
    }

    @Test
    fun aDayOrMoreAgo_isDue() {
        assertTrue(isPruneDue((now - 24.hours).toString(), now))
        assertTrue(isPruneDue((now - 25.hours).toString(), now))
    }

    @Test
    fun futureTimestamp_isNotDue() {
        assertFalse(isPruneDue((now + 1.hours).toString(), now))
    }

    @Test
    fun malformedTimestamp_isDue() {
        assertTrue(isPruneDue("not a timestamp", now))
    }
}
