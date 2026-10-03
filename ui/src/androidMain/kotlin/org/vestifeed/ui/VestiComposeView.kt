package org.vestifeed.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import org.vestifeed.db.Database

/**
 * Hosts [VestiApp] inside a plain Android View hierarchy. The Android app
 * module deliberately has no Compose compiler: it inflates this view, sets the
 * database and platform services, and the `:ui` module owns all composables.
 */
class VestiComposeView(context: Context) : AbstractComposeView(context) {

    var database: Database? = null
    var platform: VestiPlatform? = null
    var userAgent: String = ""

    @Composable
    override fun Content() {
        val db = database ?: return
        val platform = platform ?: return
        VestiApp(platform = platform, database = db, userAgent = userAgent)
    }
}
