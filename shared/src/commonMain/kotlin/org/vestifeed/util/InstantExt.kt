package org.vestifeed.util

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/**
 * Parses [this] as an instant, throwing when it is malformed.
 *
 * Feed timestamps arrive in one of three shapes, all handled here:
 * - ISO-8601 with an offset (`2026-03-10T12:04:09Z`, `…+05:00`)
 * - ISO-8601 without a zone (`2026-07-09 11:19:01`), assumed UTC
 * - RFC 822, the RSS 2.0 standard (`Sun, 02 Aug 2026 13:16:00 +0000`)
 */
fun String.toInstant(): Instant =
    toInstantOrNull() ?: throw IllegalArgumentException("Unparseable instant: $this")

fun String.toInstantOrNull(): Instant? {
    val raw = trim()
    if (raw.isEmpty()) return null

    runCatching { Instant.parse(raw) }.getOrNull()?.let { return it }

    NAIVE_DATE_TIME.matchEntire(raw)?.let { match ->
        val (year, month, day, hour, minute, second) = match.destructured
        return runCatching {
            LocalDateTime(
                year = year.toInt(),
                month = Month(month.toInt()),
                day = day.toInt(),
                hour = hour.toInt(),
                minute = minute.toInt(),
                second = second.toInt(),
            ).toInstant(UtcOffset.ZERO)
        }.getOrNull()
    }

    return parseRfc822(raw)
}

private val NAIVE_DATE_TIME = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?$""",
)

private val RFC822 = Regex(
    """^(?:[A-Za-z]{3},\s*)?(\d{1,2})\s+([A-Za-z]{3})\s+(\d{2,4})\s+(\d{2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[+-]\d{2}:\d{2}|Z|UT|GMT)?$""",
)

private val MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)

private fun parseRfc822(raw: String): Instant? {
    val match = RFC822.matchEntire(raw) ?: return null
    val (dayRaw, monthRaw, yearRaw, hourRaw, minuteRaw, secondRaw, zoneRaw) = match.destructured

    val month = MONTHS[monthRaw.lowercase()] ?: return null
    val day = dayRaw.toIntOrNull() ?: return null
    val year = (yearRaw.toIntOrNull() ?: return null).let {
        if (it < 100) (if (it >= 70) 1900 + it else 2000 + it) else it
    }
    val hour = hourRaw.toIntOrNull() ?: return null
    val minute = minuteRaw.toIntOrNull() ?: return null
    val second = secondRaw.toIntOrNull() ?: 0
    val offset = parseOffset(zoneRaw) ?: UtcOffset.ZERO

    return runCatching {
        LocalDateTime(year, Month(month), day, hour, minute, second).toInstant(offset)
    }.getOrNull()
}

private fun parseOffset(raw: String): UtcOffset? {
    if (raw.isEmpty()) return null
    if (raw == "Z" || raw.equals("UT", true) || raw.equals("GMT", true)) return UtcOffset.ZERO

    val negative = raw.startsWith("-")
    val digits = raw.removePrefix("+").removePrefix("-").replace(":", "")
    val hours = digits.take(2).toIntOrNull() ?: return null
    val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
    val sign = if (negative) -1 else 1
    return UtcOffset(sign * hours, sign * minutes)
}
