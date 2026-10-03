package org.vestifeed.parser

import com.fleeksoft.ksoup.nodes.Document

enum class FeedType {
    ATOM,
    RSS,
    RDF,
    UNKNOWN,
}

fun feedType(document: Document): FeedType {
    val documentElement = document.children().first()!!

    if (documentElement.tagName() == "feed"
        && documentElement.attr("xmlns") == "http://www.w3.org/2005/Atom"
    ) {
        return FeedType.ATOM
    }

    if (documentElement.tagName() == "rss") {
        return FeedType.RSS
    }

    if (documentElement.tagName() == "rdf:RDF") {
        return FeedType.RDF
    }

    return FeedType.UNKNOWN
}
