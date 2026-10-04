package org.vestifeed.ui.entries

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Discrete bucket that [formatRelativeTime] renders. The bucket itself is
 * locale-agnostic; translation happens at the formatter layer.
 */
sealed class RelativeTime {
    data object JustNow : RelativeTime()
    data class MinutesAgo(val count: Int) : RelativeTime()
    data class HoursAgo(val count: Int) : RelativeTime()
    data object OlderThanDay : RelativeTime()
}

/**
 * Pure bucketing rules, kept separate so the time arithmetic is unit-testable.
 *
 * Bucket boundaries:
 * - diff in `(-∞, 1m)`  -> [RelativeTime.JustNow] (also any future-dated entry)
 * - diff in `[1m, 1h)`  -> [RelativeTime.MinutesAgo]
 * - diff in `[1h, 24h)` -> [RelativeTime.HoursAgo]
 * - diff in `[24h, +∞)` -> [RelativeTime.OlderThanDay]
 */
object RelativeTimeCalculator {
    fun compute(now: Instant, published: Instant): RelativeTime {
        val duration = now - published
        return when {
            duration < ONE_MINUTE -> RelativeTime.JustNow
            duration < ONE_HOUR -> RelativeTime.MinutesAgo(duration.inWholeMinutes.toInt())
            duration < ONE_DAY -> RelativeTime.HoursAgo(duration.inWholeHours.toInt())
            else -> RelativeTime.OlderThanDay
        }
    }

    private val ONE_MINUTE: Duration = 1.minutes
    private val ONE_HOUR: Duration = 1.hours
    private val ONE_DAY: Duration = 24.hours
}

/**
 * Formats the published timestamp of an entry for the entries list subtitle.
 * Anything within the last 24 hours becomes a friendly relative phrase; older
 * entries fall back to their calendar date.
 */
fun formatRelativeTime(now: Instant, published: Instant): String {
    return when (val relative = RelativeTimeCalculator.compute(now, published)) {
        RelativeTime.JustNow -> "Just now"
        is RelativeTime.MinutesAgo -> "${relative.count} min ago"
        is RelativeTime.HoursAgo -> "${relative.count} h ago"
        RelativeTime.OlderThanDay -> formatCalendarDate(published)
    }
}

/**
 * Formats the calendar date of [instant] (in UTC) as a human-readable,
 * English date, e.g. `Aug 12, 2026`. Used wherever the raw ISO date used to be
 * shown.
 */
fun formatCalendarDate(instant: Instant): String {
    val date = instant.toLocalDateTime(TimeZone.UTC).date
    return "${MONTH_NAMES[date.month.ordinal]} ${date.day}, ${date.year}"
}

private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
