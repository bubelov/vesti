package org.vestifeed.desktop

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

    // There is no in-app player: hand the downloaded file to the system's
    // default media player.
    override fun playAudio(uri: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(File(uri))
            }
        }
    }

    override fun stopAudio() = Unit

    // Playback is external, so there is no position to report or seek.
    override fun audioPositionMs(): Long? = null

    override fun audioDurationMs(): Long? = null

    override fun seekAudio(positionMs: Long) = Unit
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
