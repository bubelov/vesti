package org.vestifeed.ui.screens

/**
 * A renderable piece of an entry's HTML body, in document order. The reader
 * renders paragraphs as prose and images as actual pictures (with their
 * `<figcaption>` as a caption), instead of flattening everything to text.
 */
internal sealed interface EntryBlock {
    /** A run of prose; may contain several paragraphs separated by blank lines. */
    data class Paragraph(val text: String) : EntryBlock

    /** An `<img>` with an absolute URL and an optional caption. */
    data class Image(val url: String, val caption: String?) : EntryBlock
}

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
    val text = stripHtml(html, preserveParagraphs = true)
    if (text.isNotBlank()) blocks += EntryBlock.Paragraph(text)
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

private val FIGURE = Regex(
    "<figure\\b[^>]*>(.*?)</figure>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val FIGCAPTION = Regex(
    "<figcaption\\b[^>]*>(.*?)</figcaption>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
