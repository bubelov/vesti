@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.vestifeed.db.Database
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols
import org.vestifeed.ui.screens.AuthScreen
import org.vestifeed.ui.screens.EntriesScreen
import org.vestifeed.ui.screens.EntryScreen
import org.vestifeed.ui.screens.FeedSettingsScreen
import org.vestifeed.ui.screens.FeedsScreen
import org.vestifeed.ui.screens.SearchScreen
import org.vestifeed.ui.screens.SettingsScreen
import org.vestifeed.ui.theme.VestiTheme
import org.vestifeed.ui.theme.vestiNavItemColors

/**
 * The shared root composable. Both hosts (Android and wasm) call this with
 * their own [Database] and [VestiPlatform]. The database is connected lazily
 * on first composition.
 */
@Composable
fun VestiApp(
    platform: VestiPlatform,
    database: Database,
    userAgent: String,
) {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(platform, database, scope, userAgent) }

    VestiImageLoader()
    LaunchedEffect(Unit) { state.connect() }

    VestiTheme {
        if (!state.connected) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@VestiTheme
        }

        when (val screen = state.screen) {
            Screen.Auth -> AuthScreen(state)
            Screen.Loading -> Unit
            else -> MainScaffold(state, screen)
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
                    MaterialSymbol(MaterialSymbols.Add, contentDescription = "Add feed")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                is Screen.Entries -> EntriesScreen(state, screen)
                is Screen.EntryDetail -> EntryScreen(state, screen.entryId)
                Screen.Feeds -> FeedsScreen(state)
                is Screen.FeedSettings -> FeedSettingsScreen(state, screen.feedId)
                Screen.Search -> SearchScreen(state)
                Screen.Settings -> SettingsScreen(state)
                else -> Unit
            }
        }
    }
}

@Composable
private fun AppTopBar(state: AppState, screen: Screen) {
    val running by state.sync.running.collectAsState()

    val title = when (screen) {
        is Screen.Entries -> when (val list = screen.list) {
            EntriesList.Unread -> "Unread (${state.unreadCount})"
            EntriesList.Bookmarked -> "Bookmarks"
            is EntriesList.BelongToFeed -> "Feed"
            is EntriesList.BelongToTag -> "Tag"
        }
        is Screen.EntryDetail -> "Entry"
        Screen.Feeds -> "Feeds"
        is Screen.FeedSettings -> "Feed settings"
        Screen.Search -> "Search"
        Screen.Settings -> "Settings"
        else -> "Vesti"
    }

    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (state.canGoBack()) {
                IconButton(onClick = { state.pop() }) {
                    MaterialSymbol(MaterialSymbols.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            // Refreshing only makes sense on the entry lists.
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
                        MaterialSymbol(MaterialSymbols.Refresh, contentDescription = "Refresh")
                    }
                }
            }
            IconButton(onClick = { state.navigate(Screen.Search) }) {
                MaterialSymbol(MaterialSymbols.Search, contentDescription = "Search")
            }
            if (screen is Screen.Feeds) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        MaterialSymbol(MaterialSymbols.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import OPML") },
                            onClick = {
                                menuExpanded = false
                                state.opmlImportDialogVisible = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export OPML") },
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
    val selected = when (screen) {
        is Screen.Entries -> when (screen.list) {
            EntriesList.Bookmarked -> 1
            else -> 0
        }
        Screen.Feeds -> 2
        Screen.Settings -> 3
        else -> -1
    }

    NavigationBar {
        NavigationBarItem(
            selected = selected == 0,
            onClick = { state.navigateRoot(Screen.Entries(EntriesList.Unread)) },
            icon = { MaterialSymbol(MaterialSymbols.Newspaper, contentDescription = null) },
            label = { Text("Unread") },
            colors = vestiNavItemColors,
        )
        NavigationBarItem(
            selected = selected == 1,
            onClick = { state.navigateRoot(Screen.Entries(EntriesList.Bookmarked)) },
            icon = { MaterialSymbol(MaterialSymbols.Bookmark, contentDescription = null) },
            label = { Text("Saved") },
            colors = vestiNavItemColors,
        )
        NavigationBarItem(
            selected = selected == 2,
            onClick = { state.navigateRoot(Screen.Feeds) },
            icon = { MaterialSymbol(MaterialSymbols.RssFeed, contentDescription = null) },
            label = { Text("Feeds") },
            colors = vestiNavItemColors,
        )
        NavigationBarItem(
            selected = selected == 3,
            onClick = { state.navigateRoot(Screen.Settings) },
            icon = { MaterialSymbol(MaterialSymbols.Settings, contentDescription = null) },
            label = { Text("Settings") },
            colors = vestiNavItemColors,
        )
    }
}
