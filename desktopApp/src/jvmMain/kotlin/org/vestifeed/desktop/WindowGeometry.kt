package org.vestifeed.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The window geometry remembered between launches.
 *
 * Only the *normal* size and whether the window was maximized are kept. The
 * position deliberately is not: a monitor can be unplugged or rearranged
 * between sessions, and a window restored to coordinates that no longer exist
 * is invisible with no way back. The window manager places it instead.
 *
 * Sizes are density-independent (dp), like the Compose window state they come
 * from, so a changed display scale does not shrink or blow up the window.
 */
internal data class WindowGeometry(
    val width: Int,
    val height: Int,
    val maximized: Boolean,
) {

    /**
     * Writes the geometry, replacing any previous file atomically so a process
     * killed mid-write leaves either the old file or the new one, never a
     * truncated one that would read as "no saved state".
     */
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("window", ".properties.tmp", file.parentFile)
        try {
            temp.outputStream().use { out ->
                Properties()
                    .apply {
                        setProperty(KEY_WIDTH, width.toString())
                        setProperty(KEY_HEIGHT, height.toString())
                        setProperty(KEY_MAXIMIZED, maximized.toString())
                    }
                    .store(out, "Vesti desktop window geometry")
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_MAXIMIZED = "maximized"

        /** Below this the reader's layout has no room to draw anything useful. */
        private const val MIN_WIDTH = 320
        private const val MIN_HEIGHT = 240

        /** Far beyond any real display; a file claiming more is corrupt. */
        private const val MAX_WIDTH = 20_000
        private const val MAX_HEIGHT = 20_000

        /**
         * The stored geometry, or null when there is none or it cannot be
         * trusted. Callers treat null as "first launch".
         */
        fun load(file: File): WindowGeometry? {
            if (!file.isFile) return null

            val properties = runCatching {
                Properties().apply { file.inputStream().use { load(it) } }
            }.getOrNull() ?: return null

            val width = properties.getProperty(KEY_WIDTH)?.toIntOrNull() ?: return null
            val height = properties.getProperty(KEY_HEIGHT)?.toIntOrNull() ?: return null
            if (width !in MIN_WIDTH..MAX_WIDTH) return null
            if (height !in MIN_HEIGHT..MAX_HEIGHT) return null

            return WindowGeometry(
                width = width,
                height = height,
                maximized = properties.getProperty(KEY_MAXIMIZED)?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}
