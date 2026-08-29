package org.vestifeed.entries

import android.content.res.Resources
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable
import java.time.OffsetDateTime

/**
 * Anything that can be turned into an [EntriesAdapter.Item]. Both
 * [EntryTable.EntriesAdapterRow] (used by the entries screen) and
 * [EntryTable.SelectByQuery] (used by the search screen) implement this so
 * that the display logic lives in one place.
 */
interface EntryRowMappable {
    val id: String
    val extShowPreviewImages: Boolean?
    val extOpenGraphImageUrl: String
    val extOpenGraphImageWidth: Int
    val extOpenGraphImageHeight: Int
    val title: String
    val feedTitle: String
    val published: OffsetDateTime
    val authorName: String
    val summary: String?
    val extRead: Boolean
    val extOpenEntriesInBrowser: Boolean
}

object EntryRowMapper {

    /**
     * Resolves the per-feed three-state "show preview images" setting against
     * the global setting. An explicit per-feed value always wins over the
     * global one; `null` means "follow settings" and falls back to the global
     * value. Without this the OR-collapse in [toItem] would let the global
     * "show" setting silently override a per-feed "hide" choice on the unread
     * and bookmarks screens.
     */
    internal fun resolveShowImage(
        perFeed: Boolean?,
        global: Boolean,
    ): Boolean = when (perFeed) {
        true -> true
        false -> false
        null -> global
    }

    /**
     * @param now the reference point used by [EntryTimeFormatter] to decide
     *   between "just now", relative and strict formatting. Callers should
     *   capture a single value per list build so all rows in one render see
     *   the same clock reading.
     */
    fun toItem(
        row: EntryRowMappable,
        conf: ConfTable.Conf,
        now: OffsetDateTime,
        resources: Resources,
    ): EntriesAdapter.Item {
        return EntriesAdapter.Item(
            id = row.id,
            showImage = resolveShowImage(row.extShowPreviewImages, conf.showPreviewImages),
            cropImage = conf.cropPreviewImages,
            imageUrl = row.extOpenGraphImageUrl,
            imageWidth = row.extOpenGraphImageWidth,
            imageHeight = row.extOpenGraphImageHeight,
            title = row.title,
            subtitle = buildSubtitle(
                feedTitle = row.feedTitle,
                authorName = row.authorName,
                showAuthorName = conf.showAuthorName,
                published = row.published,
                now = now,
                resources = resources,
            ),
            summary = row.summary ?: "",
            read = row.extRead,
            openInBrowser = row.extOpenEntriesInBrowser,
            useBuiltInBrowser = conf.useBuiltInBrowser,
        )
    }

    /**
     * Joins feed title, author and post date on the secondary line of the entry
     * card. The author segment is omitted when the underlying feed did not
     * provide one, so authorless items stay on a single "feed · date" line.
     */
    internal fun buildSubtitle(
        feedTitle: String,
        authorName: String,
        showAuthorName: Boolean,
        published: OffsetDateTime,
        now: OffsetDateTime,
        resources: Resources,
    ): String {
        return joinSubtitle(
            feedTitle = feedTitle,
            authorName = authorName,
            showAuthorName = showAuthorName,
            timestamp = EntryTimeFormatter.format(now, published, resources),
        )
    }

    internal fun joinSubtitle(
        feedTitle: String,
        authorName: String,
        showAuthorName: Boolean,
        timestamp: String,
    ): String {
        val includeAuthor = showAuthorName && authorName.isNotBlank()
        return if (includeAuthor) {
            "$feedTitle · $authorName · $timestamp"
        } else {
            "$feedTitle · $timestamp"
        }
    }
}