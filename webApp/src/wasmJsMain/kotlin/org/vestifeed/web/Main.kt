package org.vestifeed.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.vestifeed.db.Database
import org.vestifeed.og.OgImageFetcher
import org.vestifeed.ui.VestiApp
import org.w3c.dom.Worker

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val database = Database(
        driver = WebWorkerSQLiteDriver(createSqliteWorker()),
        path = "vesti.db",
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        runCatching { OgImageFetcher(database).fetchAndWatch() }
    }

    ComposeViewport {
        VestiApp(
            platform = WebVestiPlatform(),
            database = database,
            userAgent = "Vesti/0.4.3 (Web)",
        )
    }
}

/**
 * The AndroidX sqlite-web worker, vendored into the app's resources and bundled
 * by webpack. It runs SQLite WASM off the main thread and persists to OPFS when
 * the page is cross-origin isolated, falling back to memory otherwise.
 */
private fun createSqliteWorker(): Worker =
    js("new Worker(new URL('./sqlite-worker.js', import.meta.url), { type: 'module' })")
