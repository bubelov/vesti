package org.vestifeed.ui.entries

import androidx.compose.runtime.Composable
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.entries.EntryRowMappable
import org.vestifeed.ui.EntriesList
import org.vestifeed.ui.i18n.formatLocalizedDate
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.news_list_is_empty
import org.vestifeed.ui.resources.tag_has_no_unread_entries
import org.vestifeed.ui.resources.time_hours_ago
import org.vestifeed.ui.resources.time_just_now
import org.vestifeed.ui.resources.time_minutes_ago
import org.vestifeed.ui.resources.you_have_no_bookmarks
import org.vestifeed.ui.resources.you_have_no_feeds

/** The rows backing an entries list. */
suspend fun EntriesList.load(db: Database): List<EntryRowMappable> = when (this) {
    EntriesList.Unread -> db.entry.selectUnread()
    EntriesList.Bookmarked -> db.entry.selectBookmarked()
    is EntriesList.BelongToFeed -> db.entry.selectUnreadByFeedId(feedId)
    is EntriesList.BelongToTag -> {
        val feedIds = db.feedTag.selectFeedIdsByTagId(tagId)
        db.entry.selectUnreadByFeedIds(feedIds)
    }
}

/** The empty-list message for an entries list, given the current feed count. */
fun EntriesList.emptyMessage(feedCount: Int): StringResource = when (this) {
    EntriesList.Unread ->
        if (feedCount == 0) Res.string.you_have_no_feeds else Res.string.news_list_is_empty

    EntriesList.Bookmarked -> Res.string.you_have_no_bookmarks
    is EntriesList.BelongToFeed -> Res.string.news_list_is_empty
    is EntriesList.BelongToTag -> Res.string.tag_has_no_unread_entries
}

/**
 * Whether this list should show a "syncing" placeholder instead of its empty
 * message. The first sync runs against an empty local database, so the Unread
 * list has neither rows nor feeds while the backend pulls them in. Showing
 * "You have no feeds yet" during that window is wrong: the account has feeds
 * on the server, they just have not landed yet.
 *
 * [syncPending] is true while a sync is running *and* until the reload that
 * follows it has finished, so the empty state cannot flash in the gap.
 */
fun EntriesList.isAwaitingSync(
    syncPending: Boolean,
    rowCount: Int,
    feedCount: Int,
): Boolean = this is EntriesList.Unread && syncPending && rowCount == 0 && feedCount == 0

/**
 * A fully-resolved row the Compose list renders. The mapping (three-state
 * per-feed preview resolution, author inclusion) lives here so the composables
 * stay dumb; the timestamp is rendered by [entrySubtitle], where localized
 * resources and the platform date formatter are available.
 */
data class EntryRow(
    val id: String,
    val showImage: Boolean,
    val cropImage: Boolean,
    val imageUrl: String,
    val title: String,
    val feedTitle: String,
    val authorName: String,
    val published: Instant,
    val summary: String,
    val showPreviewText: Boolean,
    val read: Boolean,
    val openInBrowser: Boolean,
    val useBuiltInBrowser: Boolean,
)

/**
 * Resolves the per-feed three-state "show preview images" setting against the
 * global setting. An explicit per-feed value always wins; `null` follows the
 * global value.
 */
fun resolveShowImage(perFeed: Boolean?, global: Boolean): Boolean = when (perFeed) {
    true -> true
    false -> false
    null -> global
}

fun EntryRowMappable.toRow(conf: ConfTable.Conf): EntryRow {
    return EntryRow(
        id = id,
        showImage = resolveShowImage(extShowPreviewImages, conf.showPreviewImages),
        cropImage = conf.cropPreviewImages,
        imageUrl = extOpenGraphImageUrl,
        title = title,
        feedTitle = feedTitle,
        authorName = authorName,
        published = published,
        summary = summary ?: "",
        showPreviewText = conf.showPreviewText,
        read = extRead,
        openInBrowser = extOpenEntriesInBrowser,
        useBuiltInBrowser = conf.useBuiltInBrowser,
    )
}

/**
 * Builds the card subtitle: feed, optional author, and the published time.
 *
 * Relative phrases come from Compose Resources (so they follow the language
 * chosen in Settings); older entries fall back to a date formatted by the host
 * platform. Kept as a composable so both honor the same language.
 */
@Composable
fun entrySubtitle(row: EntryRow, showAuthor: Boolean, language: String?): String {
    val relative = RelativeTimeCalculator.compute(Clock.System.now(), row.published)
    val time = when (relative) {
        RelativeTime.JustNow -> stringResource(Res.string.time_just_now)
        is RelativeTime.MinutesAgo ->
            pluralStringResource(Res.plurals.time_minutes_ago, relative.count, relative.count)

        is RelativeTime.HoursAgo ->
            pluralStringResource(Res.plurals.time_hours_ago, relative.count, relative.count)

        RelativeTime.OlderThanDay -> formatLocalizedDate(row.published, language)
    }

    return listOfNotNull(
        row.feedTitle.takeIf { it.isNotBlank() },
        row.authorName.takeIf { showAuthor && it.isNotBlank() },
        time,
    ).joinToString(" · ")
}
