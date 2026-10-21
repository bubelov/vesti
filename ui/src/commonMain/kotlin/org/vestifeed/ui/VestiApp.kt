@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package org.vestifeed.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.vestifeed.db.Database
import org.vestifeed.ui.i18n.ProvideAppLanguage
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.add_feed
import org.vestifeed.ui.resources.app_name
import org.vestifeed.ui.resources.back
import org.vestifeed.ui.resources.bookmark
import org.vestifeed.ui.resources.bookmarks
import org.vestifeed.ui.resources.browse_curated_feeds
import org.vestifeed.ui.resources.curated_feeds
import org.vestifeed.ui.resources.entry
import org.vestifeed.ui.resources.export_opml
import org.vestifeed.ui.resources.feed
import org.vestifeed.ui.resources.feed_settings
import org.vestifeed.ui.resources.feeds
import org.vestifeed.ui.resources.import_opml
import org.vestifeed.ui.resources.mark_as_read
import org.vestifeed.ui.resources.mark_as_unread
import org.vestifeed.ui.resources.more_options
import org.vestifeed.ui.resources.open_in_browser
import org.vestifeed.ui.resources.podcasts
import org.vestifeed.ui.resources.remove_bookmark
import org.vestifeed.ui.resources.saved
import org.vestifeed.ui.resources.search
import org.vestifeed.ui.resources.search_entry
import org.vestifeed.ui.resources.settings
import org.vestifeed.ui.resources.share
import org.vestifeed.ui.resources.sync
import org.vestifeed.ui.resources.tag
import org.vestifeed.ui.resources.tag_feed_count
import org.vestifeed.ui.resources.tags
import org.vestifeed.ui.resources.unread
import org.vestifeed.ui.resources.unread_n
import org.vestifeed.ui.screens.AuthScreen
import org.vestifeed.ui.screens.CuratedCollectionScreen
import org.vestifeed.ui.screens.CuratedCollectionsScreen
import org.vestifeed.ui.screens.EntriesScreen
import org.vestifeed.ui.screens.EntryScreen
import org.vestifeed.ui.screens.FeedSettingsScreen
import org.vestifeed.ui.screens.FeedsScreen
import org.vestifeed.ui.screens.PodcastsScreen
import org.vestifeed.ui.screens.SearchScreen
import org.vestifeed.ui.screens.SettingsScreen
import org.vestifeed.ui.screens.TagsScreen
import org.vestifeed.ui.screens.UpdateAvailableDialog
import org.vestifeed.ui.theme.VestiTheme
import org.vestifeed.ui.theme.vestiNavItemColors

/**
 * The shared root composable. Every host (Android, wasm, desktop) calls this
 * with its own [Database] and [VestiPlatform]. The database is connected lazily
 * on first composition.
 */
@Composable
fun VestiApp(
    platform: VestiPlatform,
    database: Database,
    userAgent: String,
    /**
     * Overrides the display density, used by the desktop host to apply the
     * Wayland session's fractional scale; see [VestiTheme].
     */
    density: Density? = null,
) {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(platform, database, scope, userAgent) }

    VestiImageLoader(userAgent)
    LaunchedEffect(Unit) { state.connect() }

    // Wraps the whole tree so every stringResource() resolves against the
    // user's language choice (null = system default); see ProvideAppLanguage.
    ProvideAppLanguage(state.conf.language) {
        VestiTheme(density = density) {
            if (!state.connected) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@VestiTheme
            }

            // The system/browser back gesture walks the app's own back stack, so
            // opening an entry and going back returns to the list instead of
            // closing the app. At the root (nothing to pop) it is left to the
            // platform, which exits as usual. Dialogs sit above this and still
            // consume back themselves.
            BackHandler(enabled = state.canGoBack()) { state.pop() }

            when (val screen = state.screen) {
                Screen.Auth -> AuthScreen(state)
                Screen.Loading -> Unit
                else -> MainScaffold(state, screen)
            }

            UpdateAvailableDialog(state)
        }
    }
}

@Composable
private fun MainScaffold(state: AppState, screen: Screen) {
    Scaffold(
        topBar = { AppTopBar(state, screen) },
        bottomBar = { BottomBar(state, screen) },
        floatingActionButton = {
            // Following a new feed is the Feeds screen's primary action.
            if (screen is Screen.Feeds) {
                FloatingActionButton(onClick = { state.addFeedDialogVisible = true }) {
                    MaterialSymbol(MaterialSymbols.Add, contentDescription = stringResource(Res.string.add_feed))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                is Screen.Entries -> EntriesScreen(state, screen)
                is Screen.EntryDetail -> EntryScreen(state, screen.entryId)
                Screen.Feeds -> FeedsScreen(state)
                Screen.CuratedFeeds -> CuratedCollectionsScreen(state)
                is Screen.CuratedCollection -> CuratedCollectionScreen(state, screen.collectionId)
                is Screen.FeedSettings -> FeedSettingsScreen(state, screen.feedId)
                Screen.Search -> SearchScreen(state)
                Screen.Tags -> TagsScreen(state)
                Screen.Podcasts -> PodcastsScreen(state)
                Screen.Settings -> SettingsScreen(state)
                else -> Unit
            }
        }
    }
}

@Composable
private fun AppTopBar(state: AppState, screen: Screen) {
    val running by state.sync.running.collectAsState()

    val curatedCollection = (screen as? Screen.CuratedCollection)
        ?.let { state.curatedCatalog?.collection(it.collectionId) }

    val title = when (screen) {
        is Screen.Entries -> when (val list = screen.list) {
            EntriesList.Unread -> stringResource(Res.string.unread_n, state.unreadCount)
            EntriesList.Bookmarked -> stringResource(Res.string.bookmarks)
            is EntriesList.BelongToFeed -> stringResource(Res.string.feed)
            is EntriesList.BelongToTag -> stringResource(Res.string.tag)
        }
        is Screen.EntryDetail -> stringResource(Res.string.entry)
        Screen.Feeds -> stringResource(Res.string.feeds)
        Screen.CuratedFeeds -> stringResource(Res.string.curated_feeds)
        is Screen.CuratedCollection -> curatedCollection?.name
            ?: stringResource(Res.string.curated_feeds)
        is Screen.FeedSettings -> stringResource(Res.string.feed_settings)
        Screen.Search -> stringResource(Res.string.search)
        Screen.Tags -> stringResource(Res.string.tags)
        Screen.Podcasts -> stringResource(Res.string.podcasts)
        Screen.Settings -> stringResource(Res.string.settings)
        else -> stringResource(Res.string.app_name)
    }

    val subtitle = curatedCollection?.let { collection ->
        pluralStringResource(Res.plurals.tag_feed_count, collection.feeds.size, collection.feeds.size)
    }

    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (state.canGoBack()) {
                IconButton(onClick = { state.pop() }) {
                    MaterialSymbol(
                        MaterialSymbols.ArrowBack,
                        contentDescription = stringResource(Res.string.back),
                    )
                }
            }
        },
        actions = {
            // Syncing only makes sense on the entry lists.
            if (screen is Screen.Entries) {
                IconButton(
                    onClick = {
                        state.sync.clearError()
                        state.sync.runInBackground()
                    },
                    enabled = !running,
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        MaterialSymbol(MaterialSymbols.Sync, contentDescription = stringResource(Res.string.sync))
                    }
                }
            }
            if (screen is Screen.EntryDetail) {
                IconButton(onClick = { state.toggleEntryBookmark(screen.entryId) }) {
                    MaterialSymbol(
                        glyph = if (state.entryBookmarked) {
                            MaterialSymbols.BookmarkAdded
                        } else {
                            MaterialSymbols.BookmarkAdd
                        },
                        contentDescription = if (state.entryBookmarked) {
                            stringResource(Res.string.remove_bookmark)
                        } else {
                            stringResource(Res.string.bookmark)
                        },
                    )
                }
            }
            if (screen is Screen.EntryDetail) {
                // On the reader, search finds within the entry text, not the
                // global entry list. Once the find bar is open it carries its
                // own close button, so hide this one to avoid duplicating it.
                if (!state.entrySearchVisible) {
                    IconButton(onClick = { state.entrySearchVisible = true }) {
                        MaterialSymbol(
                            MaterialSymbols.Search,
                            contentDescription = stringResource(Res.string.search_entry),
                        )
                    }
                }
            } else {
                IconButton(onClick = { state.navigate(Screen.Search) }) {
                    MaterialSymbol(MaterialSymbols.Search, contentDescription = stringResource(Res.string.search))
                }
            }
            if (screen is Screen.EntryDetail) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        MaterialSymbol(
                            MaterialSymbols.MoreVert,
                            contentDescription = stringResource(Res.string.more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.open_in_browser)) },
                            enabled = state.entryHref != null,
                            onClick = {
                                menuExpanded = false
                                state.openEntryInBrowser(screen.entryId)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.share)) },
                            onClick = {
                                menuExpanded = false
                                state.shareEntry()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.entryRead) {
                                        stringResource(Res.string.mark_as_unread)
                                    } else {
                                        stringResource(Res.string.mark_as_read)
                                    },
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                state.toggleEntryRead(screen.entryId)
                            },
                        )
                    }
                }
            }
            if (screen is Screen.Feeds) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        MaterialSymbol(
                            MaterialSymbols.MoreVert,
                            contentDescription = stringResource(Res.string.more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.browse_curated_feeds)) },
                            onClick = {
                                menuExpanded = false
                                state.navigate(Screen.CuratedFeeds)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.import_opml)) },
                            onClick = {
                                menuExpanded = false
                                state.opmlImportDialogVisible = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.export_opml)) },
                            onClick = {
                                menuExpanded = false
                                state.opmlExportRequested = true
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun BottomBar(state: AppState, screen: Screen) {
    NavigationBar {
        NavigationBarItem(
            selected = screen is Screen.Entries && screen.list !is EntriesList.Bookmarked,
            onClick = { state.navigateRoot(Screen.Entries(EntriesList.Unread)) },
            icon = { MaterialSymbol(MaterialSymbols.Newspaper, contentDescription = null) },
            label = { Text(stringResource(Res.string.unread)) },
            colors = vestiNavItemColors,
        )
        NavigationBarItem(
            selected = screen is Screen.Entries && screen.list is EntriesList.Bookmarked,
            onClick = { state.navigateRoot(Screen.Entries(EntriesList.Bookmarked)) },
            icon = { MaterialSymbol(MaterialSymbols.Bookmark, contentDescription = null) },
            label = { Text(stringResource(Res.string.saved)) },
            colors = vestiNavItemColors,
        )
        NavigationBarItem(
            selected = screen is Screen.Feeds ||
                screen is Screen.CuratedFeeds ||
                screen is Screen.CuratedCollection,
            onClick = { state.navigateRoot(Screen.Feeds) },
            icon = { MaterialSymbol(MaterialSymbols.RssFeed, contentDescription = null) },
            label = { Text(stringResource(Res.string.feeds)) },
            colors = vestiNavItemColors,
        )
        // The Tags and Podcasts tabs are opt-in, so they appear as soon as their
        // setting flips (the bar reads conf, which is Compose state).
        if (state.conf.showTagsTab) {
            NavigationBarItem(
                selected = screen is Screen.Tags,
                onClick = { state.navigateRoot(Screen.Tags) },
                icon = { MaterialSymbol(MaterialSymbols.Label, contentDescription = null) },
                label = { Text(stringResource(Res.string.tags)) },
                colors = vestiNavItemColors,
            )
        }
        // The Podcasts tab is on by default but only shown when a feed actually
        // has audio enclosures, so readers without podcast feeds don't get it.
        if (state.conf.showPodcastsTab && state.hasPodcasts) {
            NavigationBarItem(
                selected = screen is Screen.Podcasts,
                onClick = { state.navigateRoot(Screen.Podcasts) },
                icon = { MaterialSymbol(MaterialSymbols.Podcasts, contentDescription = null) },
                label = { Text(stringResource(Res.string.podcasts)) },
                colors = vestiNavItemColors,
            )
        }
        NavigationBarItem(
            selected = screen is Screen.Settings,
            onClick = { state.navigateRoot(Screen.Settings) },
            icon = { MaterialSymbol(MaterialSymbols.Settings, contentDescription = null) },
            label = { Text(stringResource(Res.string.settings)) },
            colors = vestiNavItemColors,
        )
    }
}
