package org.vestifeed.ui

/** Every destination the Compose UI can show. */
sealed interface Screen {
    data object Loading : Screen

    /** Miniflux/embedded backend selection and login. */
    data object Auth : Screen

    data class Entries(val list: EntriesList) : Screen

    data class EntryDetail(val entryId: String) : Screen

    data object Feeds : Screen

    /** Browsing the embedded Awesome RSS Feeds collection (the list of collections). */
    data object CuratedFeeds : Screen

    /** The feeds inside one curated collection. */
    data class CuratedCollection(val collectionId: String) : Screen

    data class FeedSettings(val feedId: String) : Screen

    data object Search : Screen

    /** The Tags tab (shown when `conf.showTagsTab`). */
    data object Tags : Screen

    /** The Podcasts tab (shown when `conf.showPodcastsTab`). */
    data object Podcasts : Screen

    data object Settings : Screen
}

/** The entries-tab variants, mirroring the old `EntriesFilter`. */
sealed interface EntriesList {
    data object Unread : EntriesList
    data object Bookmarked : EntriesList
    data class BelongToFeed(val feedId: String) : EntriesList
    data class BelongToTag(val tagId: String) : EntriesList
}
