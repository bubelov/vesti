@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.vestifeed.db.Database
import org.vestifeed.ui.screens.AuthScreen
import org.vestifeed.ui.screens.EntriesScreen
import org.vestifeed.ui.screens.EntryScreen
import org.vestifeed.ui.screens.FeedSettingsScreen
import org.vestifeed.ui.screens.FeedsScreen
import org.vestifeed.ui.screens.SearchScreen
import org.vestifeed.ui.screens.SettingsScreen
import org.vestifeed.ui.theme.VestiTheme

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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
    val title = when (screen) {
        is Screen.Entries -> when (val list = screen.list) {
            EntriesList.Unread -> "News"
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
                IconButton(onClick = { state.pop() }) { Text("←") }
            }
        },
        actions = {
            IconButton(onClick = { state.navigate(Screen.Search) }) { Text("🔍") }
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
            icon = { Text("📰") },
            label = { Text("News") },
        )
        NavigationBarItem(
            selected = selected == 1,
            onClick = { state.navigateRoot(Screen.Entries(EntriesList.Bookmarked)) },
            icon = { Text("🔖") },
            label = { Text("Saved") },
        )
        NavigationBarItem(
            selected = selected == 2,
            onClick = { state.navigateRoot(Screen.Feeds) },
            icon = { Text("📡") },
            label = { Text("Feeds") },
        )
        NavigationBarItem(
            selected = selected == 3,
            onClick = { state.navigateRoot(Screen.Settings) },
            icon = { Text("⚙") },
            label = { Text("Settings") },
        )
    }
}
