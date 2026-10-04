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

    // The browser streams audio natively, so there is nothing to download; the
    // (proxied) URL is played directly.
    override suspend fun cacheAudio(url: String, onProgress: (Double?) -> Unit): String? {
        onProgress(null)
        return url
    }

    override fun playAudio(uri: String) {
        playAudioJs(uri)
    }

    override fun stopAudio() {
        stopAudioJs()
    }

    override fun audioPositionMs(): Long? =
        audioCurrentTimeJs().takeIf { it.isFinite() }?.let { (it * 1000).toLong() }

    override fun audioDurationMs(): Long? =
        audioDurationJs().takeIf { it.isFinite() }?.let { (it * 1000).toLong() }

    override fun seekAudio(positionMs: Long) {
        seekAudioJs(positionMs / 1000.0)
    }
}

// Kotlin/Wasm only allows `js(...)` in top-level functions or property
// initializers, so the interop lives outside the class.
private fun openUrlJs(url: String): Unit = js("window.open(url, '_blank')")

private fun shareTextJs(text: String): Unit =
    js("navigator.share ? navigator.share({ text: text }) : navigator.clipboard && navigator.clipboard.writeText(text)")

private fun playAudioJs(url: String): Unit =
    js("(window.__vestiAudio = window.__vestiAudio || new Audio()).src = url; window.__vestiAudio.play()")

private fun stopAudioJs(): Unit =
    js("if (window.__vestiAudio) { window.__vestiAudio.pause(); window.__vestiAudio.currentTime = 0 }")

private fun audioCurrentTimeJs(): Double =
    js("(window.__vestiAudio ? window.__vestiAudio.currentTime : NaN)")

private fun audioDurationJs(): Double =
    js("(window.__vestiAudio ? window.__vestiAudio.duration : NaN)")

private fun seekAudioJs(seconds: Double): Unit =
    js("if (window.__vestiAudio) window.__vestiAudio.currentTime = seconds")
