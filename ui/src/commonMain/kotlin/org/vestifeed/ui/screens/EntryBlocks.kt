package org.vestifeed.ui.screens

/**
 * A renderable piece of an entry's HTML body, in document order. The reader
 * renders paragraphs as prose and images as actual pictures (with their
 * `<figcaption>` as a caption), instead of flattening everything to text.
 */
internal sealed interface EntryBlock {
    /**
     * A run of prose; may contain several paragraphs separated by blank lines.
     * [links] are the clickable spans of [text] (half-open `[start, end)`
     * ranges, in document order) with the raw `href` their anchor declared.
     */
    data class Paragraph(
        val text: String,
        val links: List<LinkSpan> = emptyList(),
    ) : EntryBlock

    /** An `<img>` with an absolute URL and an optional caption. */
    data class Image(val url: String, val caption: String?) : EntryBlock
}

/**
 * A clickable span of an [EntryBlock.Paragraph]: the `[start, end)` range of its
 * `text` that opens [url]. [url] is the anchor's raw `href`; the reader resolves
 * it against the entry's own URL via [resolveLink].
 */
internal data class LinkSpan(val start: Int, val end: Int, val url: String)

/**
 * Splits [html] into [EntryBlock]s. `<figure>` blocks (and any stray `<img>`)
 * become image blocks; everything between them is plain-prose stripped with
 * [stripHtml]. Images without an absolute URL are dropped rather than rendered
 * broken.
 */
internal fun parseEntryBlocks(html: String): List<EntryBlock> {
    if (html.isBlank()) return emptyList()

    val blocks = mutableListOf<EntryBlock>()
    var cursor = 0
    for (figure in FIGURE.findAll(html)) {
        addTextBlocks(html.substring(cursor, figure.range.first), blocks)

        val inner = figure.groupValues[1]
        val src = imageSrc(inner)
        val caption = FIGCAPTION.find(inner)
            ?.groupValues
            ?.get(1)
            ?.let { stripHtml(it) }
            ?.takeIf { it.isNotBlank() }
        if (src != null) blocks += EntryBlock.Image(src, caption)

        cursor = figure.range.last + 1
    }
    addTextBlocks(html.substring(cursor), blocks)
    return blocks
}

private fun addTextBlocks(chunk: String, blocks: MutableList<EntryBlock>) {
    if (chunk.isBlank()) return

    var cursor = 0
    for (img in IMG.findAll(chunk)) {
        addParagraph(chunk.substring(cursor, img.range.first), blocks)
        val src = imageSrc(img.value)
        if (src != null) blocks += EntryBlock.Image(src, caption = null)
        cursor = img.range.last + 1
    }
    addParagraph(chunk.substring(cursor), blocks)
}

private fun addParagraph(html: String, blocks: MutableList<EntryBlock>) {
    if (html.isBlank()) return
    val (text, links) = stripHtmlAndLinks(html)
    if (text.isNotBlank()) blocks += EntryBlock.Paragraph(text, links)
}

/**
 * [stripHtml]s [html] (paragraph-preserving) while recording where each
 * `<a href=…>`'s text landed in the result, so the reader can make it
 * clickable. Anchors without an `href` are left as plain prose.
 *
 * The text is stripped from the document as a whole, so its whitespace is
 * collapsed exactly once; each anchor's own text is then located inside it, in
 * document order and after the previous link. The stripped length of the
 * prefix biases the search past an identical literal sitting earlier in the
 * paragraph, while the previous link's end keeps the spans from overlapping.
 */
private fun stripHtmlAndLinks(html: String): Pair<String, List<LinkSpan>> {
    val text = stripHtml(html, preserveParagraphs = true)
    val links = mutableListOf<LinkSpan>()
    var cursor = 0
    for (match in ANCHOR.findAll(html)) {
        val href = HREF.find(match.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
        if (href.isEmpty()) continue
        val anchorText = stripHtml(match.groupValues[2], preserveParagraphs = true).trim()
        if (anchorText.isEmpty()) continue

        val prefixLength = stripHtml(
            html.substring(0, match.range.first),
            preserveParagraphs = true,
        ).length
        val from = maxOf(cursor, prefixLength - 1).coerceIn(0, text.length)
        val start = text.indexOf(anchorText, from).takeIf { it >= 0 }
            ?: text.indexOf(anchorText, cursor).takeIf { it >= 0 }
            ?: continue

        links += LinkSpan(start, start + anchorText.length, href)
        cursor = start + anchorText.length
    }
    return text to links
}

/**
 * The URL a paragraph link should open, or null when [href] is not something
 * worth opening. Absolute `http(s)`, protocol-relative and `mailto:`/`tel:`
 * targets pass through; a relative target is resolved against [baseUrl] (the
 * entry's own article URL); a bare fragment or `javascript:` is dropped.
 */
internal fun resolveLink(href: String, baseUrl: String?): String? {
    val target = href.trim()
    if (target.isEmpty()) return null
    val lower = target.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> target
        target.startsWith("//") -> "https:$target"
        lower.startsWith("mailto:") || lower.startsWith("tel:") -> target
        lower.startsWith("javascript:") || target.startsWith("#") -> null
        target.startsWith("/") -> originOf(baseUrl)?.let { it + target }
        else -> directoryOf(baseUrl)?.let { it + target }
    }
}

/** `scheme://host[:port]` of [url], or null when it has no scheme. */
private fun originOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return null
    val hostStart = schemeEnd + 3
    val pathStart = url.indexOf('/', startIndex = hostStart)
    return if (pathStart < 0) url else url.substring(0, pathStart)
}

/** The directory of [url] (everything up to and including its last slash). */
private fun directoryOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val withoutQuery = url.substringBefore('#').substringBefore('?')
    val slash = withoutQuery.lastIndexOf('/')
    val schemeEnd = withoutQuery.indexOf("://")
    return if (slash <= schemeEnd + 2) {
        originOf(withoutQuery)?.plus("/")
    } else {
        withoutQuery.substring(0, slash + 1)
    }
}

/** The absolute URL of the first `<img src=…>` in [html], or null. */
private fun imageSrc(html: String): String? {
    val src = IMG_SRC.find(html)?.groupValues?.get(1)?.trim().orEmpty()
    return when {
        src.startsWith("http://") || src.startsWith("https://") -> src
        src.startsWith("//") -> "https:$src"
        else -> null
    }
}

private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

private val IMG_SRC = Regex(
    """\bsrc\s*=\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE,
)

/** An `<a>` anchor: group 1 is its attributes, group 2 its inner markup. */
private val ANCHOR = Regex(
    "<a\\b([^>]*)>(.*?)</a\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** The `href` of an anchor's attributes. */
private val HREF = Regex(
    """\bhref\s*=\s*["']([^"']*)["']""",
    RegexOption.IGNORE_CASE,
)

private val FIGURE = Regex(
    "<figure\\b[^>]*>(.*?)</figure>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val FIGCAPTION = Regex(
    "<figcaption\\b[^>]*>(.*?)</figcaption>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
