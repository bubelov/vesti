package org.vestifeed.opml

/**
 * Serializes [this] to a pretty-printed OPML document.
 *
 * Replaces the old `toXmlDocument()` / `toPrettyString()` pair, which relied
 * on `javax.xml` and `org.w3c.dom`. The output is a plain multi-line string
 * with a four-space indent and the same elements and attributes the DOM
 * writer produced, including the literal `"null"` values it emitted for the
 * three-state extension flags.
 */
fun OpmlDocument.toXml(): String {
    val builder = StringBuilder()

    builder.appendLine(
        """<opml xmlns:news="https://appreactor.co/news" version="${escapeAttribute(version.value)}">""",
    )
    builder.appendLine("    <head>")
    builder.appendLine("        <title>Subscriptions</title>")
    builder.appendLine("    </head>")
    builder.appendLine("    <body>")

    leafOutlines().forEach { outline ->
        val attributes = buildString {
            append(" text=\"")
            append(escapeAttribute(outline.text))
            append("\" xmlUrl=\"")
            append(escapeAttribute(outline.xmlUrl ?: ""))
            append("\" htmlUrl=\"")
            append(escapeAttribute(outline.htmlUrl ?: ""))
            append("\" news:openEntriesInBrowser=\"")
            append(outline.extOpenEntriesInBrowser.toString())
            append("\" news:blockedWords=\"")
            append(escapeAttribute(outline.extBlockedWords ?: ""))
            append("\" news:showPreviewImages=\"")
            append(outline.extShowPreviewImages.toString())
            append("\"")
        }

        builder.appendLine("        <outline$attributes/>")
    }

    builder.appendLine("    </body>")
    builder.append("</opml>")

    return builder.toString()
}

private fun escapeAttribute(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}
