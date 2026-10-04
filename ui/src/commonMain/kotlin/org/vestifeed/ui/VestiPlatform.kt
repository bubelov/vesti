package org.vestifeed.ui

/**
 * The platform services the shared Compose UI needs from its host. Android
 * implements these with a Custom Tab / share intent; the browser target opens
 * a new tab / uses the Web Share API.
 */
interface VestiPlatform {
    /**
     * Whether the host supports the touch pull-to-refresh gesture on the entry
     * lists. True on Android and touch browsers; the desktop window has no touch
     * pull, so it turns the gesture off and refreshes from the app bar or the
     * entry swipe actions instead.
     */
    val supportsPullToRefresh: Boolean get() = true

    /**
     * Opens [url] in a browser. When [useBuiltInBrowser] is true the host uses
     * its in-app browser (an Android Custom Tab); otherwise it hands the link to
     * the system browser. Hosts without an in-app browser ignore the flag.
     */
    fun openUrl(url: String, useBuiltInBrowser: Boolean)

    /** Shares [text] through the platform share sheet. */
    fun shareText(text: String)

    /**
     * Makes the audio enclosure at [url] available for playback, downloading it
     * first if needed and reporting [onProgress] in `0f..1f` (or null when the
     * size is unknown). Returns a URI for [playAudio], or null on failure.
     * Hosts without a filesystem may simply return [url] for streaming.
     */
    suspend fun cacheAudio(url: String, onProgress: (Double?) -> Unit): String?

    /**
     * Plays [uri] (from [cacheAudio]) inside the app, or hands it to the system
     * player on hosts that have no built-in one.
     */
    fun playAudio(uri: String)

    /** Stops any in-app playback started by [playAudio]. */
    fun stopAudio()

    /** Current in-app playback position in ms, or null when nothing is playing. */
    fun audioPositionMs(): Long?

    /** In-app playback duration in ms, or null while it is still unknown. */
    fun audioDurationMs(): Long?

    /** Seeks the in-app player to [positionMs]. No-op where playback is external. */
    fun seekAudio(positionMs: Long)
}
