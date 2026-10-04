package org.vestifeed.web

import org.vestifeed.ui.VestiPlatform

class WebVestiPlatform : VestiPlatform {

    // The browser host has no separate in-app browser; always open a new tab.
    override fun openUrl(url: String, useBuiltInBrowser: Boolean) {
        openUrlJs(url)
    }

    override fun shareText(text: String) {
        shareTextJs(text)
    }
}

// Kotlin/Wasm only allows `js(...)` in top-level functions or property
// initializers, so the interop lives outside the class.
private fun openUrlJs(url: String): Unit = js("window.open(url, '_blank')")

private fun shareTextJs(text: String): Unit =
    js("navigator.share ? navigator.share({ text: text }) : navigator.clipboard && navigator.clipboard.writeText(text)")
