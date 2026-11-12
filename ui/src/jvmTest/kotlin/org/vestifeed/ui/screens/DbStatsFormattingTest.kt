package org.vestifeed.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone

class DbStatsFormattingTest {

    @Test
    fun formatCount_groupsThousands() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,000", formatCount(1_000))
        assertEquals("12,345", formatCount(12_345))
        assertEquals("1,234,567", formatCount(1_234_567))
        assertEquals("-1,234", formatCount(-1_234))
    }

    @Test
    fun formatBytes_usesTheLargestFittingUnit() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1_024))
        assertEquals("1.5 KB", formatBytes(1_536))
        assertEquals("48.2 MB", formatBytes(50_540_000))
    }

    @Test
    fun databaseFileName_dropsTheDirectories() {
        assertEquals("vesti.db", databaseFileName("/data/user/0/org.vestifeed/databases/vesti.db"))
        assertEquals("vesti.db", databaseFileName("C:\\Users\\igor\\vesti.db"))
        assertEquals("vesti.db", databaseFileName("vesti.db"))
    }

    @Test
    fun formatTimestamp_rendersInTheGivenZone() {
        assertEquals(
            "Oct 31, 2026 09:14",
            formatTimestamp("2026-10-31T09:14:00Z", TimeZone.UTC),
        )
        assertEquals(
            "Oct 31, 2026 12:14",
            formatTimestamp("2026-10-31T09:14:00Z", TimeZone.of("Europe/Moscow")),
        )
    }

    @Test
    fun formatTimestamp_returnsNullForAnUnparseableValue() {
        assertNull(formatTimestamp("not a timestamp", TimeZone.UTC))
    }

    @Test
    fun formatSyncDuration_usesTheRightUnit() {
        assertEquals("850 ms", formatSyncDuration(850.milliseconds))
        assertEquals("1.0 s", formatSyncDuration(1.seconds))
        assertEquals("1.6 s", formatSyncDuration(1_620.milliseconds))
        assertEquals("1m 5s", formatSyncDuration(65.seconds))
    }
}
