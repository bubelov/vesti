package org.vestifeed.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.vestifeed.db.table.ConfTable
import org.vestifeed.sync.BackgroundSyncPolicy
import org.vestifeed.sync.SyncReason

/**
 * Installs or removes the periodic sync that the "Fetch news in background"
 * setting enables.
 *
 * The work is always registered under one unique name so changing the interval
 * replaces the schedule instead of stacking a second one, and so turning the
 * setting off can cancel exactly what was installed.
 */
internal object BackgroundSync {
    private const val WORK_NAME = "vesti-background-sync"

    fun apply(context: Context, enabled: Boolean, intervalMillis: Long) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
            BackgroundSyncPolicy.intervalMillis(intervalMillis),
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(
                // A sync with no network can only fail; let WorkManager wait.
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE keeps the existing schedule's place in the queue and only
            // replaces its parameters, so an interval change does not reset the
            // next run to "interval from now".
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

/**
 * One periodic sync.
 *
 * It runs in the app's own process, so it borrows the application's database
 * and [org.vestifeed.sync.Sync] instance rather than opening a second
 * connection to the same file. The [Sync] mutex keeps it from overlapping a
 * sync the reader starts at the same moment.
 */
internal class BackgroundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        val conf = app.currentConf()

        // The setting (or the account) can change after the work was scheduled;
        // deciding here is what makes turning it off take effect immediately
        // rather than at the next app start.
        if (!BackgroundSyncPolicy.isEnabled(conf)) return Result.success()

        app.sync.runInForeground(SyncReason.Background)

        // Sync records its own error instead of throwing, so retry only when it
        // actually failed; WorkManager applies the backoff.
        return if (app.sync.lastError.value == null) Result.success() else Result.retry()
    }
}

/** The configuration the worker reads before deciding whether to sync. */
internal suspend fun App.currentConf(): ConfTable.Conf {
    db.connect()
    return db.conf.select()
}
