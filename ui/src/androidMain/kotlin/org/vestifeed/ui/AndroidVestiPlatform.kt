package org.vestifeed.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android implementation of the shared UI's platform services. */
class AndroidVestiPlatform(
    private val context: Context,
) : VestiPlatform {

    private var player: MediaPlayer? = null

    /**
     * Whether the player has finished preparing. Position/duration must not be
     * queried before then: MediaPlayer treats it as an error and tears itself
     * down.
     */
    private var prepared = false

    override fun openUrl(url: String, useBuiltInBrowser: Boolean) {
        val uri = Uri.parse(url)
        if (useBuiltInBrowser) {
            runCatching {
                CustomTabsIntent.Builder().build().launchUrl(context, uri)
            }.onFailure { openExternally(uri) }
        } else {
            openExternally(uri)
        }
    }

    private fun openExternally(uri: Uri) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override suspend fun cacheAudio(url: String, onProgress: (Double?) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "audio").apply { mkdirs() }
            downloadToFile(url, File(dir, audioFileName(url)), onProgress)
        }

    override fun playAudio(uri: String) {
        stopAudio()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(uri)
                setOnPreparedListener { mp ->
                    prepared = true
                    mp.start()
                }
                setOnCompletionListener { stopAudio() }
                setOnErrorListener { _, _, _ ->
                    stopAudio()
                    true
                }
                prepareAsync()
            }
        }.onFailure { stopAudio() }
    }

    override fun stopAudio() {
        prepared = false
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    override fun audioPositionMs(): Long? =
        if (prepared) player?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } else null

    override fun audioDurationMs(): Long? =
        if (prepared) {
            player?.let { runCatching { it.duration.toLong().takeIf { duration -> duration > 0 } }.getOrNull() }
        } else {
            null
        }

    override fun seekAudio(positionMs: Long) {
        if (prepared) player?.runCatching { seekTo(positionMs.toInt()) }
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
