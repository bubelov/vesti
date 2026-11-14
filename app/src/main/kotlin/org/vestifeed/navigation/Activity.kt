package org.vestifeed.navigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import org.vestifeed.BuildConfig
import org.vestifeed.app.App
import org.vestifeed.app.BackgroundSync
import org.vestifeed.ui.AndroidVestiPlatform
import org.vestifeed.ui.VestiComposeView

class Activity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as App

        // The host owns the scheduler, so the platform only forwards the
        // decision the shared UI makes from the settings.
        val platform = AndroidVestiPlatform(
            context = this,
            updateManifestUrl = app.updateManifestUrl,
            buildTime = BuildConfig.BUILD_TIME,
            userAgent = app.userAgent,
        ).apply {
            backgroundSyncScheduler = { enabled, intervalMillis ->
                BackgroundSync.apply(applicationContext, enabled, intervalMillis)
            }
        }

        setContentView(
            VestiComposeView(this).apply {
                database = app.db
                this.platform = platform
                userAgent = app.userAgent
            }
        )
    }
}
