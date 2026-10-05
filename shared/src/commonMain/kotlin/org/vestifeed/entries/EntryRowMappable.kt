package org.vestifeed.entries

import kotlin.time.Instant

/**
 * Anything that can be turned into an entries-list row. Both
 * [org.vestifeed.db.table.EntryTable.EntriesAdapterRow] (used by the entries
 * screen) and [org.vestifeed.db.table.EntryTable.SelectByQuery] (used by the
 * search screen) implement this so the display logic lives in one place.
 */
interface EntryRowMappable {
    val id: String
    val extBookmarked: Boolean
    val extShowPreviewImages: Boolean?
    val extOpenGraphImageUrl: String
    val title: String
    val feedTitle: String
    val published: Instant
    val authorName: String
    val summary: String?
    val extRead: Boolean
    val extOpenEntriesInBrowser: Boolean
}
