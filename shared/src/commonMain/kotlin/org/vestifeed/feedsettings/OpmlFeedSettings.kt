package org.vestifeed.feedsettings

import org.vestifeed.db.table.FeedTable
import org.vestifeed.opml.OpmlOutline

/**
 * Applies the per-feed overrides carried by [outline] to a feed row returned by
 * the backend.
 *
 * Vesti's OPML exporter writes the three per-feed settings as `news:*`
 * attributes ([exportFeedSettings] writes the same values to JSON); importing
 * an OPML file must therefore restore them instead of dropping them and leaving
 * every feed on the global defaults.
 *
 * The backend's title wins when it has one: it is the feed's own current title,
 * while the OPML `text` attribute may be an old folder label. [outline]'s text
 * is used only as a fallback when the fetched feed had no title at all.
 */
fun FeedTable.Feed.withOpmlOverrides(outline: OpmlOutline): FeedTable.Feed = copy(
    title = title.ifBlank { outline.text },
    extOpenEntriesInBrowser = outline.extOpenEntriesInBrowser,
    extShowPreviewImages = outline.extShowPreviewImages,
    extBlockedWords = outline.extBlockedWords.orEmpty(),
)
