package org.vestifeed.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
        // Runs once the first composition has been applied — the closest thing
        // to "a window is on screen" this side of the surface can see. It is
        // what `./devtools run` waits for before it returns control (see
        // cmd_run_desktop), so an early failure does not look like a launch.
        LaunchedEffect(Unit) { println("vesti: first composition committed") }

        // Compose Desktop has no native Wayland backend on Linux: it runs on
        // XWayland, whose AWT toolkit only reports whole-number scales. A 275%
        // session therefore arrives as 200%, so recover the compositor's
        // fractional scale and use it as the density of this window's content.
        // Outside a Wayland session, and on the other platforms, the helper
        // returns the toolkit's value unchanged.
        val toolkitDensity = LocalDensity.current
        val uiScale = remember(toolkitDensity.density) {
            waylandUiScale(toolkitDensity.density)
        }
        val uiDensity = remember(uiScale, toolkitDensity.fontScale) {
            Density(uiScale, toolkitDensity.fontScale)
        }

        VestiApp(
            platform = remember { DesktopVestiPlatform() },
            database = database,
            userAgent = USER_AGENT,
            density = uiDensity,
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
