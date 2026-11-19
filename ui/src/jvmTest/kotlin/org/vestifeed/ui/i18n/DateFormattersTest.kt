package org.vestifeed.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class DateFormattersTest {

    @Test
    fun formatLocalizedDate_usesTheRequestedLanguage() {
        val instant = Instant.parse("2026-08-12T09:30:00Z")

        assertEquals("Aug 12, 2026", formatLocalizedDate(instant, "en"))
        assertEquals("12.08.2026", formatLocalizedDate(instant, "de"))
    }

    @Test
    fun formatLocalizedDateTime_usesTheRequestedZone() {
        val instant = Instant.parse("2026-10-31T09:14:00Z")

        val utc = formatLocalizedDateTime(instant, TimeZone.UTC, "en")
        assertTrue(utc.contains("2026"), "expected the year in \"$utc\"")
        assertTrue(utc.contains("9:14"), "expected 09:14 in \"$utc\"")

        val moscow = formatLocalizedDateTime(instant, TimeZone.of("Europe/Moscow"), "en")
        assertTrue(moscow.contains("12:14"), "expected 12:14 in \"$moscow\"")
    }
}
