package org.vestifeed.ui.entries

import kotlin.time.Clock
import kotlin.time.Instant
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.entries.EntryRowMappable
import org.vestifeed.ui.EntriesList

/** The rows backing an entries list. */
suspend fun EntriesList.load(db: Database): List<EntryRowMappable> = when (this) {
    EntriesList.Unread -> db.entry.selectUnread()
    EntriesList.Bookmarked -> db.entry.selectBookmarked()
    is EntriesList.BelongToFeed -> db.entry.selectByFeedId(feedId).filterNot { it.extRead }
    is EntriesList.BelongToTag -> {
        val feedIds = db.feedTag.selectFeedIdsByTagId(tagId)
        db.entry.selectUnreadByFeedIds(feedIds)
    }
}

/** The empty-list message for an entries list, given the current feed count. */
fun EntriesList.emptyMessage(feedCount: Int): String = when (this) {
    EntriesList.Unread -> if (feedCount == 0) "You have no feeds yet" else "The news list is empty"
    EntriesList.Bookmarked -> "You have no bookmarks"
    is EntriesList.BelongToFeed -> "The news list is empty"
    is EntriesList.BelongToTag -> "This tag has no unread entries"
}

/**
 * A fully-resolved row the Compose list renders. The mapping (three-state
 * per-feed preview resolution, relative timestamps, author inclusion) lives
 * here so the composables stay dumb.
 */
data class EntryRow(
    val id: String,
    val showImage: Boolean,
    val cropImage: Boolean,
    val imageUrl: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val title: String,
    val subtitle: String,
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

fun EntryRowMappable.toRow(
    conf: ConfTable.Conf,
    now: Instant = Clock.System.now(),
): EntryRow {
    val includeAuthor = conf.showAuthorName && authorName.isNotBlank()
    val timestamp = formatRelativeTime(now, published)
    val subtitle = if (includeAuthor) {
        "$feedTitle · $authorName · $timestamp"
    } else {
        "$feedTitle · $timestamp"
    }

    return EntryRow(
        id = id,
        showImage = resolveShowImage(extShowPreviewImages, conf.showPreviewImages),
        cropImage = conf.cropPreviewImages,
        imageUrl = extOpenGraphImageUrl,
        imageWidth = extOpenGraphImageWidth,
        imageHeight = extOpenGraphImageHeight,
        title = title,
        subtitle = subtitle,
        summary = summary ?: "",
        showPreviewText = conf.showPreviewText,
        read = extRead,
        openInBrowser = extOpenEntriesInBrowser,
        useBuiltInBrowser = conf.useBuiltInBrowser,
    )
}
