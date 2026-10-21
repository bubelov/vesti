package org.vestifeed.desktop

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The UI scale of the Wayland session, or [current] when it cannot be read.
 *
 * Compose Desktop has no native Wayland backend on Linux: it runs on XWayland,
 * and its density comes from AWT, which applies whole-number scales only. At
 * KDE's 275%, the window is therefore laid out at 200%. This reads the scale the
 * compositor is actually configured with so the caller can apply it as the
 * composition's density instead.
 *
 * Only Wayland sessions are considered: an X11 session has no compositor scale
 * to recover, and the toolkit's own density is all there is.
 */
internal fun waylandUiScale(current: Float): Float {
    if (!isWaylandSession()) return current

    val detected = kscreenScale() ?: waylandInfoScale() ?: return current
    // Only ever raise: if the toolkit already reports the same or a bigger
    // factor there is nothing to recover, and shrinking the UI would be wrong.
    return if (detected > current + 0.01f && detected <= 4f) detected else current
}

private fun isWaylandSession(): Boolean {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return false
    return System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true ||
        !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
}

/**
 * KDE's `kscreen-doctor -j` reports the scale of each output. The app opens
 * maximized on the primary output (priority 1), and a window has one density
 * for all of its content, so that output's scale is the one to use.
 */
private fun kscreenScale(): Float? {
    val output = runCommand("kscreen-doctor", "-j") ?: return null
    val scale = runCatching {
        Json.parseToJsonElement(output).jsonObject["outputs"]?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { it["enabled"]?.jsonPrimitive?.booleanOrNull == true }
            ?.minByOrNull { it["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            ?.get("scale")?.jsonPrimitive?.floatOrNull
    }.getOrNull()
    return scale?.takeIf { it in 1.01f..4f }
}

/**
 * Compositor-agnostic fallback: an output's current mode width divided by the
 * logical width from xdg-output is its fractional scale (3840 / 1396 = 2.75).
 * Only the first output can be paired this way; a single-output session is the
 * common case, and KDE's kscreen above resolves the rest.
 */
private fun waylandInfoScale(): Float? {
    val output = runCommand("wayland-info") ?: return null
    val modeWidth = Regex("""(?m)^\s*width:\s*(\d+)\s*px""")
        .find(output.substringAfter("interface: 'wl_output'", ""))
        ?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val logicalWidth = Regex("""(?m)^\s*logical_width:\s*(\d+)""")
        .find(output.substringAfter("xdg_output_v1", ""))
        ?.groupValues?.get(1)?.toIntOrNull() ?: return null
    if (logicalWidth <= 0) return null
    return (modeWidth.toFloat() / logicalWidth).takeIf { it in 1.01f..4f }
}

/** Runs a query command, returning its output, or null if it is missing or fails. */
private fun runCommand(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        null
    } else if (process.exitValue() == 0) {
        output
    } else {
        null
    }
}.getOrNull()
