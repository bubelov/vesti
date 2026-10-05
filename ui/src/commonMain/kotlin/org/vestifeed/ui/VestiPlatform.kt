package org.vestifeed.ui

/**
 * The platform services the shared Compose UI needs from its host. Android
 * implements these with a Custom Tab / share intent; the browser target opens
 * a new tab / uses the Web Share API.
 */
interface VestiPlatform {
    /**
     * Whether the host supports the touch pull-to-refresh gesture on the entry
     * lists. True on Android; the desktop window and the browser have no touch
     * pull, so they turn the gesture off and refresh from the app bar or the
     * entry swipe actions instead.
     */
    val supportsPullToRefresh: Boolean get() = true

    /**
     * Whether the host can sync periodically on its own while the app is not in
     * the foreground. False on desktop, which has no background scheduler, so
     * the "Sync in background" setting is shown disabled there. Hosts that
     * never sync in the background simply leave the setting at its default.
     */
    val supportsBackgroundSync: Boolean get() = true

    /**
     * Whether the host favours a physical keyboard, e.g. a desktop window. When
     * true the sign-in form focuses its first field so typing can begin without
     * a click; touch hosts leave it unfocused so no soft keyboard pops up.
     */
    val supportsHardwareKeyboard: Boolean get() = false

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
     * Plays [uri] (from [cacheAudio]) with the host's built-in player.
     */
    fun playAudio(uri: String)

    /**
     * Whether the host can hand an audio enclosure to the platform's default
     * media player, rather than only a browser. True on desktop; false on
     * Android and the browser, where disabling the built-in player falls back to
     * opening the stream in the browser.
     */
    val supportsExternalAudioPlayer: Boolean get() = false

    /**
     * Opens the audio enclosure at [url] outside the app, used when the built-in
     * player is disabled. Hosts with a default media player download the file and
     * hand it over, reporting [onProgress] in `0f..1f` (or null when the size is
     * unknown); the default implementation opens [url] in the browser (the in-app
     * one when [useBuiltInBrowser] is set), streaming it. Called from a coroutine
     * only when [supportsExternalAudioPlayer] is true.
     */
    suspend fun openAudioExternally(
        url: String,
        useBuiltInBrowser: Boolean,
        onProgress: (Double?) -> Unit,
    ) {
        openUrl(url, useBuiltInBrowser)
    }

    /** Stops any in-app playback started by [playAudio]. */
    fun stopAudio()

    /** Current in-app playback position in ms, or null when nothing is playing. */
    fun audioPositionMs(): Long?

    /** In-app playback duration in ms, or null while it is still unknown. */
    fun audioDurationMs(): Long?

    /** Seeks the in-app player to [positionMs]. No-op where playback is external. */
    fun seekAudio(positionMs: Long)
}
