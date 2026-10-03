package org.vestifeed.app

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.vestifeed.db.Database
import org.vestifeed.og.OgImageFetcher
import org.vestifeed.sync.Sync

class App : Application() {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val db by lazy {
        Database(
            driver = AndroidSQLiteDriver(),
            path = databaseFile.absolutePath,
        )
    }

    internal val sync by lazy { Sync(scope, db) }

    private val ogFetcher by lazy { OgImageFetcher(db) }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { ogFetcher.fetchAndWatch() }
        }
    }

    private val databaseFile: File
        get() = getDatabasePath(Database.NAME)
}
