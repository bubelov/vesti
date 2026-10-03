package org.vestifeed.opml

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

fun String.toOpml(): OpmlDocument {
    val xmlDocument = Ksoup.parse(this, Parser.xmlParser())
    return xmlDocument.toOpml()
}

private fun Document.toOpml(): OpmlDocument {
    val opmlElement = children().first()!!

    if (!opmlElement.hasAttr("version")) {
        throw Exception("OPML version is missing")
    }

    val opmlVersion = opmlVersion(opmlElement.attr("version")).getOrElse {
        throw Exception("Unsupported OPML version: ${opmlElement.attr("version")}", it)
    }

    val bodyElements = opmlElement.getElementsByTag("body")

    if (bodyElements.size == 0) {
        throw Exception("Document has no body")
    }

    if (bodyElements.size > 1) {
        throw Exception("Only a single body tag is permitted")
    }

    val bodyElement = bodyElements.first()!!

    val topLevelOutlines = bodyElement
        .children()
        .map { it.toOutline() }

    return OpmlDocument(
        version = opmlVersion,
        outlines = topLevelOutlines,
    )
}

private fun Element.toOutline(): OpmlOutline {
    return OpmlOutline(
        text = attr("text"),
        outlines = children().map { it.toOutline() },
        xmlUrl = attr("xmlUrl"),
        htmlUrl = attr("htmlUrl"),
        extOpenEntriesInBrowser = attr("news:openEntriesInBrowser").toBoolean(),
        extShowPreviewImages = when (attr("news:showPreviewImages")) {
            "true" -> true
            "false" -> false
            else -> null
        },
        extBlockedWords = attr("news:blockedWords"),
    )
}
