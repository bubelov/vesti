package org.vestifeed.navigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import org.vestifeed.app.App
import org.vestifeed.ui.AndroidVestiPlatform
import org.vestifeed.ui.VestiComposeView

class Activity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as App

        setContentView(
            VestiComposeView(this).apply {
                database = app.db
                platform = AndroidVestiPlatform(this@Activity)
                userAgent = app.userAgent
            }
        )
    }
}
