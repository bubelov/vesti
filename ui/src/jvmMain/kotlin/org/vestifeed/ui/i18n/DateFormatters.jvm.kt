package org.vestifeed.ui.i18n

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

internal actual fun formatLocalizedDate(instant: Instant, language: String?): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(localeFor(language))
        .format(
            java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
                .atZone(ZoneOffset.UTC)
                .toLocalDate(),
        )

internal actual fun formatLocalizedDateTime(
    instant: Instant,
    timeZone: TimeZone,
    language: String?,
): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(localeFor(language))
        .withZone(ZoneId.of(timeZone.id))
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

private fun localeFor(language: String?): Locale =
    if (language.isNullOrBlank()) Locale.getDefault() else Locale.forLanguageTag(language)
