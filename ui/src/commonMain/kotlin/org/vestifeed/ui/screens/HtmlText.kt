package org.vestifeed.ui.screens

/**
 * Turns an HTML fragment into plain text. Tags are replaced with a space so
 * adjacent blocks do not run together, a handful of common entities are
 * decoded, and runs of whitespace are collapsed. This is deliberately a very
 * small helper: the Compose screens only need readable prose, not a full HTML
 * parser or renderer.
 */
internal fun stripHtml(html: String): String {
    var text = html.replace(TAG, " ")
    text = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
    return text.replace(WHITESPACE, " ").trim()
}

private val TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
