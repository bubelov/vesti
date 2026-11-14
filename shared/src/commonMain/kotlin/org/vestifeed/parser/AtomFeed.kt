package org.vestifeed.parser

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import org.vestifeed.util.toInstantOrNull

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

    // Only the feed's own direct children count: searching descendants would
    // pick up the first entry's title or author as if they were the feed's.
    val title = documentElement.childElements("title").firstOrNull()?.text()
        ?: return Result.failure(Exception("Channel has no title"))

    val links = documentElement.childElements("link")
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
    val feedElement = document.children().firstOrNull()
        ?: return Result.failure(Exception("Feed ID is missing"))
    val feedId = feedElement.childElements("id").firstOrNull()?.text()
        ?: return Result.failure(Exception("Feed ID is missing"))

    // RFC 4287 §4.1.1: an entry may omit atom:author when the feed supplies
    // one, so this is the fallback for every entry below.
    val feedAuthorName = feedElement.childElements("author")
        .firstOrNull()
        ?.authorName()
        .orEmpty()

    val entries = document.getElementsByTag("entry")

    val parsedEntries = entries.mapNotNull { entry ->
        // > atom:entry elements MUST contain exactly one atom:id element.
        // Source: https://tools.ietf.org/html/rfc4287
        val id = entry.childElements("id").firstOrNull()?.text() ?: return@mapNotNull null

        // > atom:entry elements MUST contain exactly one atom:title element.
        // Source: https://tools.ietf.org/html/rfc4287
        val title =
            entry.childElements("title").firstOrNull()?.text() ?: return@mapNotNull null

        val links = entry.childElements("link")
            .map { element -> element.toAtomLink().getOrElse { return Result.failure(it) } }

        val content: AtomEntryContent

        val contentElements = entry.childElements("content")

        when (contentElements.size) {
            0 -> {
                // > atom:entry elements that contain no child atom:content
                // > element MUST contain at least one atom:link element with a
                // > rel attribute value of "alternate".
                // Source: https://datatracker.ietf.org/doc/html/rfc4287#section-4.1.2
                //
                // The link carries the body in that case and the reader follows
                // it, so an empty content is correct here. An entry with neither
                // content nor an alternate link has nothing to show, so it is
                // dropped rather than listed as a blank article.
                if (links.none { it.rel is AtomLinkRel.Alternate }) return@mapNotNull null
                content = AtomEntryContent(
                    type = AtomEntryContentType.Text,
                    src = "",
                    text = "",
                )
            }

            1 -> {
                val element = contentElements.first()
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

        val summaryElements = entry.childElements("summary")

        when (summaryElements.size) {
            0 -> {
                summary = null
            }

            1 -> {
                val element = summaryElements.first()
                summary = AtomEntrySummary(
                    type = element.attr("type").trim(),
                    text = element.text(),
                )
            }

            else -> {
                return Result.failure(Exception("Feed entry has more than one summary element"))
            }
        }

        val elementsWithUpdatedTag = entry.childElements("updated")

        // > atom:entry elements MUST contain exactly one atom:updated element.
        // Source: https://tools.ietf.org/html/rfc4287
        if (elementsWithUpdatedTag.size != 1) {
            return Result.failure(Exception("atom:entry elements MUST contain exactly one atom:updated element"))
        }

        val updated = elementsWithUpdatedTag.first().text()

        // RFC 4287 §4.2.9: atom:published is optional (at most one) and is the
        // date the entry first appeared, which is what the lists sort by.
        // Ordering by atom:updated instead makes a feed that touches an old
        // post resurface it at the top, so the update time is only the fallback
        // for entries that omit the element or carry an unparseable date.
        val publishedElements = entry.childElements("published")
        if (publishedElements.size > 1) {
            return Result.failure(
                Exception(
                    "atom:entry elements MUST NOT contain more than one atom:published element",
                ),
            )
        }
        val published = publishedElements.firstOrNull()
            ?.text()
            ?.takeIf { it.isNotBlank() && it.toInstantOrNull() != null }
            ?: updated

        // RFC 4287 §4.1.1: an entry inherits its author from atom:source or,
        // failing that, from the feed document itself.
        val authorName = entry.childElements("author").firstOrNull()?.authorName()
            ?.takeIf { it.isNotBlank() }
            ?: entry.childElements("source").firstOrNull()
                ?.childElements("author")
                ?.firstOrNull()
                ?.authorName()
                ?.takeIf { it.isNotBlank() }
            ?: feedAuthorName

        AtomEntry(
            id = id,
            feedId = feedId,
            title = title,
            published = published,
            updated = updated,
            authorName = authorName,
            content = content,
            links = links,
            summary = summary,
        )
    }

    return Result.success(parsedEntries)
}

/** This element's direct children named [tag], in document order. */
private fun Element.childElements(tag: String): List<Element> =
    children().filter { it.tagName() == tag }

/** The `atom:name` of this `atom:author` element, or "" when it has none. */
private fun Element.authorName(): String =
    childElements("name").firstOrNull()?.text().orEmpty()

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
