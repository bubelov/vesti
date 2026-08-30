package org.vestifeed.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.vestifeed.backend.Backend
import org.vestifeed.backend.backend
import org.vestifeed.db.Database
import org.vestifeed.og.AndroidConnectivityMonitor
import org.vestifeed.og.ConnectivityMonitor
import org.vestifeed.og.OpenGraphImageFetcher
import org.vestifeed.sync.Sync
import java.io.File

class App : Application() {
    internal val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    internal val db by lazy {
        Database(
            driver = AndroidSQLiteDriver(),
            path = databaseFile.absolutePath,
        )
    }

    internal val sync by lazy { Sync(scope, db) }

    internal val connectivityMonitor: ConnectivityMonitor by lazy {
        AndroidConnectivityMonitor(this)
    }

    /**
     * `true` while any of this app's activities are in the foreground.
     * Updated by [ProcessLifecycleOwner] on `onStart`/`onStop`. The OG
     * fetcher reads this to skip work while the process is in the
     * background — Android may throttle background app network access,
     * which was responsible for a stream of `UnknownHostException` log
     * entries on past releases.
     */
    internal val foreground = MutableStateFlow(false)

    internal val ogFetcher by lazy {
        OpenGraphImageFetcher(
            db = db,
            imageContext = this,
            connectivityMonitor = connectivityMonitor,
            isForeground = { foreground.value },
        )
    }

    internal val api by lazy { backend(db) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground.value = false
                }
            }
        )
        scope.launch {
            try {
                ogFetcher.fetchAndWatch()
            } catch (e: Throwable) {
                Log.e("App", "ogFetcher failed", e)
            }
        }
    }

    internal val databaseFile: File
        get() = getDatabasePath(Database.NAME)
}

fun Fragment.sync() = requireContext().sync()

fun Context.sync(): Sync = (applicationContext as App).sync

fun Fragment.api() = requireContext().api()

fun Context.api(): Backend = (applicationContext as App).api

fun Fragment.db() = requireContext().db()

fun Context.db(): Database = (applicationContext as App).db
