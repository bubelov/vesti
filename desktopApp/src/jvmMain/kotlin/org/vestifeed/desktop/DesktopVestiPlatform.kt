package org.vestifeed.desktop

import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayer
import io.github.kdroidfilter.composemediaplayer.audio.isPlaying
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vestifeed.ui.VestiPlatform

/**
 * Desktop implementation of the shared UI's platform services. Opening a link
 * hands it to the system browser; sharing copies the text to the clipboard,
 * since desktop has no share sheet (the same fallback the browser target uses).
 * Audio enclosures are played in-process with rodio, so the shared player UI
 * (position, duration, scrubber) behaves as it does on Android.
 */
class DesktopVestiPlatform : VestiPlatform {

    // Desktop has no touch pull gesture.
    override val supportsPullToRefresh: Boolean = false

    // Desktop has no background scheduler, so periodic background sync is not
    // possible; the setting is shown disabled.
    override val supportsBackgroundSync: Boolean = false

    // Desktop is keyboard-first: the sign-in form focuses its first field and
    // submits on Enter.
    override val supportsHardwareKeyboard: Boolean = true

    // Disabling the built-in player routes podcasts to the system's default
    // media player rather than the browser.
    override val supportsExternalAudioPlayer: Boolean = true

    // Desktop has no in-app browser, so the flag is ignored and links always go
    // to the system browser.
    override fun openUrl(url: String, useBuiltInBrowser: Boolean) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }

    override fun shareText(text: String) {
        runCatching {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }

    override suspend fun cacheAudio(url: String, onProgress: (Double?) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val dir = File(System.getProperty("java.io.tmpdir"), "vesti-audio").apply { mkdirs() }
            downloadToFile(url, File(dir, audioFileName(url)), onProgress)
        }

    // A single in-app player, created lazily on first playback so constructing
    // the platform never touches an audio device. Rodio keeps the output device
    // open across tracks, so the same instance is reused.
    private var audioPlayer: AudioPlayer? = null

    private fun ensureAudioPlayer(): AudioPlayer? =
        audioPlayer ?: runCatching { AudioPlayer() }
            .onSuccess { audioPlayer = it }
            .getOrNull()

    // Plays the downloaded enclosure in-process, so the shared player UI
    // (position, duration, scrubber) works as it does on Android.
    override fun playAudio(uri: String) {
        runCatching { ensureAudioPlayer()?.play(uri) }
    }

    // With the built-in player disabled, download the enclosure and hand the
    // file to the system's default media player — the behaviour desktop had
    // before the in-app player existed.
    override suspend fun openAudioExternally(
        url: String,
        useBuiltInBrowser: Boolean,
        onProgress: (Double?) -> Unit,
    ) {
        val uri = cacheAudio(url, onProgress) ?: return
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(File(uri))
            }
        }
    }

    override fun stopAudio() {
        runCatching { audioPlayer?.stop() }
    }

    // Once Rodio finishes a track it parks just short of the duration and
    // reports IDLE rather than null; the shared UI treats a null position as
    // "finished" (as Android does), so map the idle state to null here.
    override fun audioPositionMs(): Long? {
        val player = audioPlayer ?: return null
        return runCatching { if (player.isPlaying()) player.currentPosition() else null }.getOrNull()
    }

    override fun audioDurationMs(): Long? =
        runCatching { audioPlayer?.currentDuration() }.getOrNull()

    override fun seekAudio(positionMs: Long) {
        runCatching { audioPlayer?.seekTo(positionMs) }
    }
}

/** A stable cache file name for [url], keeping the audio extension. */
private fun audioFileName(url: String): String {
    val ext = url.substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .takeIf { it.length in 1..5 }
        ?: "mp3"
    return "${url.hashCode().toUInt().toString(16)}.$ext"
}

/**
 * Streams [url] into [file], reporting progress, and returns its path (or null
 * on failure). An existing non-empty file is reused; downloads land in a
 * `.part` file first so a failed attempt never poisons the cache.
 */
private fun downloadToFile(
    url: String,
    file: File,
    onProgress: (Double?) -> Unit,
): String? {
    if (file.exists() && file.length() > 0) {
        onProgress(1.0)
        return file.absolutePath
    }

    val tmp = File(file.parentFile, "${file.name}.part")
    return runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Vesti")
        }
        connection.connect()
        val total = connection.contentLengthLong.takeIf { it > 0 }
        connection.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    onProgress(total?.let { read.toDouble() / it })
                }
            }
        }
        connection.disconnect()
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) error("Could not move ${tmp.name} into place")
        file.absolutePath
    }.getOrElse {
        tmp.delete()
        null
    }
}
