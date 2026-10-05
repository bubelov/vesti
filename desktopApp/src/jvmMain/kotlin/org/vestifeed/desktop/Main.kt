package org.vestifeed.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vestifeed.db.Database
import org.vestifeed.og.OgImageFetcher
import org.vestifeed.ui.VestiApp

private const val USER_AGENT = "Vesti/0.4.3 (Desktop)"

fun main() = application {
    val database = remember { Database(BundledSQLiteDriver(), databaseFile().absolutePath) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    LaunchedEffect(Unit) {
        scope.launch { runCatching { OgImageFetcher(database, USER_AGENT).fetchAndWatch() } }
    }
    DisposableEffect(Unit) {
        onDispose {
            scope.cancel()
            database.close()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Vesti",
        state = rememberWindowState(size = DpSize(1100.dp, 800.dp)),
    ) {
        VestiApp(
            platform = remember { DesktopVestiPlatform() },
            database = database,
            userAgent = USER_AGENT,
        )
    }
}

/**
 * The per-user database location, following each platform's convention:
 * `%APPDATA%\Vesti` on Windows, `~/Library/Application Support/Vesti` on macOS
 * and the XDG data directory elsewhere.
 */
private fun databaseFile(): File {
    val directory = dataDirectory().apply { mkdirs() }
    return File(directory, Database.NAME)
}

private fun dataDirectory(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: home, "Vesti")
        os.contains("mac") -> File(home, "Library/Application Support/Vesti")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "Vesti")
    }
}
