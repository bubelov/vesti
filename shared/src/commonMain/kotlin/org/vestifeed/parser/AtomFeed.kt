package org.vestifeed.parser

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

data class AtomFeed(
    val title: String,
    val links: List<AtomLink>,
    val entries: List<AtomEntry>,
) : Feed()

data class AtomEntry(
    val id: String,
    val feedId: String,
    val title: String,
    val published: String,
    val updated: String,
    val authorName: String,
    val content: AtomEntryContent,
    val links: List<AtomLink>,
    val summary: AtomEntrySummary?,
)

data class AtomLink(
    val href: String,
    val rel: AtomLinkRel?,
    val type: String,
    val hreflang: String,
    val title: String,
    val length: Long?,
)

sealed class AtomLinkRel {
    object Alternate : AtomLinkRel()
    object Enclosure : AtomLinkRel()
    object Related : AtomLinkRel()
    object Self : AtomLinkRel()
    object Via : AtomLinkRel()
    data class Custom(val value: String) : AtomLinkRel()
}

/*
https://datatracker.ietf.org/doc/html/rfc4287#section-4.1.3

atomInlineTextContent =
  element atom:content {
     atomCommonAttributes,
     attribute type { "text" | "html" }?,
     (text)*
  }

atomInlineXHTMLContent =
  element atom:content {
     atomCommonAttributes,
     attribute type { "xhtml" },
     xhtmlDiv
  }

atomInlineOtherContent =
  element atom:content {
     atomCommonAttributes,
     attribute type { atomMediaType }?,
     (text|anyElement)*
  }

atomOutOfLineContent =
  element atom:content {
     atomCommonAttributes,
     attribute type { atomMediaType }?,
     attribute src { atomUri },
     empty
  }

atomContent = atomInlineTextContent
| atomInlineXHTMLContent
| atomInlineOtherContent
| atomOutOfLineContent
 */
data class AtomEntryContent(
    val type: AtomEntryContentType,
    val src: String,
    val text: String,
)

sealed class AtomEntryContentType {
    object Text : AtomEntryContentType()
    object Html : AtomEntryContentType()
    object Xhtml : AtomEntryContentType()
    data class Mime(val mime: String) : AtomEntryContentType()
}

data class AtomEntrySummary(
    val type: String,
    val text: String,
)

fun atomFeed(document: Document): Result<AtomFeed> {
    val documentElement = document.children().first()!!

    val title = documentElement.getElementsByTag("title").first()?.text()
        ?: return Result.failure(Exception("Channel has no title"))

    val links = documentElement.children()
        .filter { it.tagName() == "link" }
        .map { element -> element.toAtomLink().getOrElse { return Result.failure(it) } }

    val entries = atomEntries(document).getOrElse {
        return Result.failure(it)
    }

    return Result.success(
        AtomFeed(
            title = title,
            links = links,
            entries = entries,
        )
    )
}

fun atomEntries(document: Document): Result<List<AtomEntry>> {
    val feedId = document.getElementsByTag("id").first()?.text()
        ?: return Result.failure(Exception("Feed ID is missing"))

    val entries = document.getElementsByTag("entry")

    val parsedEntries = entries.mapNotNull { entry ->
        // > atom:entry elements MUST contain exactly one atom:id element.
        // Source: https://tools.ietf.org/html/rfc4287
        val id = entry.getElementsByTag("id").first()?.text() ?: return@mapNotNull null

        // > atom:entry elements MUST contain exactly one atom:title element.
        // Source: https://tools.ietf.org/html/rfc4287
        val title =
            entry.getElementsByTag("title").first()?.text() ?: return@mapNotNull null

        val content: AtomEntryContent

        val contentElements = entry.getElementsByTag("content")

        when (contentElements.size) {
            0 -> {
                /*
                TODO

                https://datatracker.ietf.org/doc/html/rfc4287#section-4.1.2

                atom:entry elements that contain no child atom:content element
                MUST contain at least one atom:link element with a rel attribute
                value of "alternate"
                 */
                content = AtomEntryContent(
                    type = AtomEntryContentType.Text,
                    src = "",
                    text = "",
                )
            }

            1 -> {
                val element = contentElements.first()!!
                val rawContentType = element.attr("type").trim()
                val src = element.attr("src").trim()

                if (rawContentType.isEmpty() && src.isNotEmpty()) {
                    return Result.failure(Exception("Content type is missing"))
                }

                val contentType = when (rawContentType) {
                    "", "text" -> AtomEntryContentType.Text
                    "html" -> AtomEntryContentType.Html
                    "xhtml" -> AtomEntryContentType.Xhtml
                    else -> AtomEntryContentType.Mime(rawContentType)
                }

                content = AtomEntryContent(
                    type = contentType,
                    src = src,
                    text = element.text(),
                )
            }

            else -> {
                return Result.failure(Exception("Feed entry has more than one content element"))
            }
        }

        val summary: AtomEntrySummary?

        val summaryElements = entry.getElementsByTag("summary")

        when (summaryElements.size) {
            0 -> {
                summary = null
            }

            1 -> {
                val element = summaryElements.first()!!
                val rawContentType = element.attr("type").trim()

                summary = AtomEntrySummary(
                    type = rawContentType,
                    text = element.text(),
                )
            }

            else -> {
                return Result.failure(Exception("Feed entry has more than one summary element"))
            }
        }

        val elementsWithUpdatedTag = entry.getElementsByTag("updated")

        // > atom:entry elements MUST contain exactly one atom:updated element.
        // Source: https://tools.ietf.org/html/rfc4287
        if (elementsWithUpdatedTag.size != 1) {
            return Result.failure(Exception("atom:entry elements MUST contain exactly one atom:updated element"))
        }

        val updated = elementsWithUpdatedTag.first()!!.text()

        // > atom:entry elements MUST contain exactly one atom:updated element.
        // Source: https://tools.ietf.org/html/rfc4287
        val author = entry.getElementsByTag("author").first()

        // TODO
        // atom:entry elements MUST contain one or more atom:author elements, unless the atom:entry
        // contains an atom:source element that contains an atom:author element or, in an Atom Feed
        // Document, the atom:feed element contains an atom:author element itself.
        // Source: https://tools.ietf.org/html/rfc4287
        val authorName = author?.getElementsByTag("name")?.first()?.text() ?: ""

        val linkElements = entry.getElementsByTag("link")

        val links = linkElements
            .map { element -> element.toAtomLink().getOrElse { return Result.failure(it) } }

        AtomEntry(
            id = id,
            feedId = feedId,
            title = title,
            published = updated, // TODO
            updated = updated,
            authorName = authorName,
            content = content,
            links = links,
            summary = summary,
        )
    }

    return Result.success(parsedEntries)
}

private fun Element.toAtomLink(): Result<AtomLink> {
    val rel = when (attr("rel")) {
        "" -> AtomLinkRel.Alternate
        "alternate" -> AtomLinkRel.Alternate
        "enclosure" -> AtomLinkRel.Enclosure
        "related" -> AtomLinkRel.Related
        "self" -> AtomLinkRel.Self
        "via" -> AtomLinkRel.Via
        else -> AtomLinkRel.Custom(attr("rel"))
    }

    val lengthAttrName = "length"

    val length = if (attr(lengthAttrName).toLongOrNull() != null) {
        attr(lengthAttrName).toLong()
    } else {
        null
    }

    return Result.success(
        AtomLink(
            href = attr("href"),
            rel = rel,
            type = attr("type"),
            hreflang = attr("hreflang"),
            title = attr("title"),
            length = length,
        )
    )
}
