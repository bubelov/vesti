package org.vestifeed.backend

import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomLinkRel

/**
 * Turns [MinifluxApi] DTOs into the local rows [Miniflux] stores.
 *
 * Pure functions, so the mapping decisions that are easy to get subtly wrong —
 * which statuses count as read, which URL becomes the `alternate` link, what
 * happens to an entry with enclosures — are testable without a server or an
 * HTTP client.
 */
internal object MinifluxMapping {

    /**
     * The feed's local row. The per-feed display overrides (open in browser,
     * blocked words, preview images) are the user's, not the server's, so they
     * are left at their defaults here; [MinifluxSync] copies the cached values
     * back over them when the feed already exists.
     */
    fun toFeed(feed: MinifluxFeed): FeedTable.Feed = FeedTable.Feed(
        id = feed.id.toString(),
        title = feed.title,
        extOpenEntriesInBrowser = false,
        extBlockedWords = "",
        extShowPreviewImages = null,
    )

    /**
     * The feed's two links: `self` for the XML/RSS/Atom document it subscribes
     * to, and `alternate` for the site the feed belongs to, which is what
     * "open the feed's site" and OPML export use.
     */
    fun toFeedLinks(feed: MinifluxFeed): List<LinkTable.Link> = listOf(
        LinkTable.Link(
            id = null,
            feedId = feed.id.toString(),
            entryId = null,
            href = feed.feedUrl,
            rel = AtomLinkRel.Self,
            type = null,
            hreflang = null,
            title = null,
            length = null,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        ),
        LinkTable.Link(
            id = null,
            feedId = feed.id.toString(),
            entryId = null,
            href = feed.siteUrl,
            rel = AtomLinkRel.Alternate,
            type = "text/html",
            hreflang = null,
            title = null,
            length = null,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        ),
    )

    /**
     * The entry's local row and its links.
     *
     * The entry itself is stored as HTML content with no summary: Miniflux
     * resolves the feed's content into one HTML body, and splitting a summary
     * out of it would guess at markup the server already normalised.
     *
     * Links are the article's own address (when it has one) plus one per
     * enclosure, which is how the podcast tab finds audio.
     *
     * `ext_read` follows [MinifluxEntryStatus]: only `unread` is unread. A
     * `removed` entry is not an article waiting to be read, and letting it
     * count as unread would put it in the Unread tab.
     */
    fun toEntry(entry: MinifluxEntry): Pair<EntryTable.Entry, List<LinkTable.Link>> {
        val links = buildList {
            if (entry.url.isNotBlank()) {
                add(
                    LinkTable.Link(
                        id = null,
                        feedId = null,
                        entryId = entry.id.toString(),
                        href = entry.url,
                        rel = AtomLinkRel.Alternate,
                        type = "text/html",
                        hreflang = null,
                        title = null,
                        length = null,
                        extEnclosureDownloadProgress = null,
                        extCacheUri = null,
                    ),
                )
            }
            entry.enclosures.forEach { enclosure ->
                add(
                    LinkTable.Link(
                        id = null,
                        feedId = null,
                        entryId = entry.id.toString(),
                        href = enclosure.url,
                        rel = AtomLinkRel.Enclosure,
                        type = enclosure.mimeType,
                        hreflang = null,
                        title = null,
                        length = enclosure.size,
                        extEnclosureDownloadProgress = null,
                        extCacheUri = null,
                    ),
                )
            }
        }

        val row = EntryTable.Entry(
            contentType = "html",
            contentSrc = "",
            contentText = entry.content,
            summary = null,
            id = entry.id.toString(),
            feedId = entry.feedId.toString(),
            title = entry.title,
            published = entry.publishedAt,
            updated = entry.changedAt,
            authorName = entry.author,
            extRead = entry.status != MinifluxEntryStatus.UNREAD,
            extReadSynced = true,
            extBookmarked = entry.starred,
            extBookmarkedSynced = true,
            extCommentsUrl = entry.commentsUrl,
            extOpenGraphImageChecked = false,
            extOpenGraphImageUrl = "",
            extOpenGraphImageFetchedAt = null,
            extOpenGraphImageLog = "[]",
        )

        return row to links
    }
}
