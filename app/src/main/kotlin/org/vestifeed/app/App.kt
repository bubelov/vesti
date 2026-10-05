package org.vestifeed.app

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
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
     * than in the activity) so the background OpenGraph fetcher, which starts
     * before any activity exists, sends the same string as the UI's requests.
     */
    internal val userAgent = "Vesti/${BuildConfig.VERSION_NAME} (Android)"

    internal val db by lazy {
        Database(
            driver = AndroidSQLiteDriver(),
            path = databaseFile.absolutePath,
        )
    }

    internal val sync by lazy { Sync(scope, db, userAgent) }

    private val ogFetcher by lazy { OgImageFetcher(db, userAgent) }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { ogFetcher.fetchAndWatch() }
        }
    }

    private val databaseFile: File
        get() = getDatabasePath(Database.NAME)
}
