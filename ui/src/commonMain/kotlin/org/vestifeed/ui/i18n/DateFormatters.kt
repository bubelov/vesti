package org.vestifeed.ui.i18n

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Formats [instant] as a date in [language] (a `values-<code>` qualifier, or
 * null for the system locale) using the platform's locale data.
 *
 * The shared UI cannot ship CLDR month tables for 29 languages, so the few
 * places that need an absolute date ask the host instead: `java.time` on
 * Android/desktop and `Intl.DateTimeFormat` in the browser.
 */
internal expect fun formatLocalizedDate(instant: Instant, language: String?): String

/**
 * Formats [instant] as a date and time in [timeZone], localized the same way as
 * [formatLocalizedDate]. Used by the diagnostics screen for timestamps.
 */
internal expect fun formatLocalizedDateTime(
    instant: Instant,
    timeZone: TimeZone,
    language: String?,
): String
