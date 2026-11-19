@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.vestifeed.ui.i18n

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

internal actual fun formatLocalizedDate(instant: Instant, language: String?): String =
    formatDateJs(language, instant.toEpochMilliseconds())

internal actual fun formatLocalizedDateTime(
    instant: Instant,
    timeZone: TimeZone,
    language: String?,
): String = formatDateTimeJs(language, instant.toEpochMilliseconds(), timeZone.id)

private fun formatDateJs(language: String?, epochMillis: Long): String =
    js("new Intl.DateTimeFormat(language || undefined, { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(epochMillis))")

private fun formatDateTimeJs(language: String?, epochMillis: Long, timeZone: String): String =
    js("new Intl.DateTimeFormat(language || undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: timeZone }).format(new Date(epochMillis))")
