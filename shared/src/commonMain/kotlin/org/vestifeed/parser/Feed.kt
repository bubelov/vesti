package org.vestifeed.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.parser.Parser

sealed class Feed

fun feed(bytes: ByteArray, mediaType: String): FeedResult {
    return if (
        mediaType.startsWith("application/rss+xml")
        || mediaType.startsWith("application/x-rss+xml")
        || mediaType.startsWith("application/atom+xml")
        || mediaType.startsWith("application/x-atom+xml")
        || mediaType.startsWith("application/xml")
        || mediaType.startsWith("text/xml")
        // Some servers (notably Google Sites / Blogger) label Atom/RSS feeds as
        // text/plain. Probe the body as XML and let the parser figure out the
        // format — XML parsing will fail loudly if the body really isn't XML.
        || mediaType.startsWith("text/plain")
    ) {
        feedFromXml(bytes)
    } else {
        FeedResult.UnsupportedMediaType(mediaType)
    }
}

private fun feedFromXml(bytes: ByteArray): FeedResult {
    val document = runCatching {
        Ksoup.parse(bytes.decodeToString(), Parser.xmlParser())
    }.getOrNull() ?: return FeedResult.ParserError(IllegalStateException("Failed to parse feed"))

    return documentToFeedResult(document)
}

private fun documentToFeedResult(document: Document): FeedResult {
    return when (feedType(document)) {
        FeedType.ATOM -> {
            atomFeed(document).map {
                FeedResult.Success(it)
            }.getOrElse {
                FeedResult.ParserError(it)
            }
        }

        FeedType.RSS -> {
            rssFeed(document).map {
                FeedResult.Success(it)
            }.getOrElse {
                FeedResult.ParserError(it)
            }
        }

        FeedType.RDF -> {
            rdfFeed(document).map {
                FeedResult.Success(it)
            }.getOrElse {
                FeedResult.ParserError(it)
            }
        }

        FeedType.UNKNOWN -> FeedResult.UnsupportedFeedType
    }
}
