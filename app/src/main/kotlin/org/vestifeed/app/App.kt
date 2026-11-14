package org.vestifeed.app

import android.app.Application
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.vestifeed.BuildConfig
import org.vestifeed.db.Database
import org.vestifeed.og.OgImageFetcher
import org.vestifeed.sync.Sync

class App : Application() {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The User-Agent every request introduces Vesti with. Kept here (rather
     * than in the activity) so the preview fetcher and the background sync
     * worker send the same string as the UI's requests.
     */
    internal val userAgent = "Vesti/${BuildConfig.VERSION_NAME} (Android)"

    /**
     * The update manifest this build checks, or "" when it does not
     * self-update. Only the nightly is published on the website; the release
     * reaches users through its store listing.
     */
    internal val updateManifestUrl: String =
        if (BuildConfig.APPLICATION_ID.endsWith(".nightly")) {
            "https://vestifeed.org/releases/vesti-nightly.json"
        } else {
            ""
        }

    internal val db by lazy {
        Database(
            driver = BundledSQLiteDriver(),
            path = databaseFile.absolutePath,
        )
    }

    internal val sync by lazy { Sync(scope, db, userAgent) }

    /**
     * Whether the app is on screen, and the trigger that starts the preview
     * fetcher the first time it is. A process that WorkManager starts for a
     * background sync has no activity, so this keeps that process from
     * downloading preview images in the background.
     */
    private val foreground = AppForeground(onFirstForeground = ::startOgImageFetcher)

    private val ogFetcher by lazy {
        OgImageFetcher(db, userAgent, isForeground = { foreground.isForeground })
    }

    private var ogFetcherStarted = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(foreground)
    }

    /**
     * Starts the preview fetcher once the UI first comes to the foreground.
     * Idempotent: a rotation or a second activity calls it again.
     */
    private fun startOgImageFetcher() {
        if (ogFetcherStarted) return
        ogFetcherStarted = true
        scope.launch {
            runCatching { ogFetcher.fetchAndWatch() }
        }
    }

    private val databaseFile: File
        get() = getDatabasePath(Database.NAME)
}
