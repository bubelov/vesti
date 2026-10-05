package org.vestifeed.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import okio.ByteString.Companion.encodeUtf8
import org.vestifeed.db.Database
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomEntry
import org.vestifeed.parser.AtomFeed
import org.vestifeed.parser.AtomLink
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.parser.Feed
import org.vestifeed.parser.FeedResult
import org.vestifeed.parser.RssFeed
import org.vestifeed.parser.RssItem
import org.vestifeed.parser.RssItemGuid
import org.vestifeed.parser.feed
import org.vestifeed.platform.proxiedUrl
import org.vestifeed.util.toInstant

typealias ParsedFeed = Feed

internal class EmbeddedFeedFetcher(
    private val db: Database,
    private val httpClient: HttpClient,
) {

    suspend fun fetchEntries(
        feed: FeedTable.Feed,
    ): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
        val feedLinks = db.link.selectByFeedId(feed.id)
        val feedSelfLink = feedLinks.firstOrNull { it.rel is AtomLinkRel.Self }
            ?: throw Exception("self link is missing")
        val response = httpClient.get(proxiedUrl(feedSelfLink.href))
        if (!response.status.isSuccess()) throw Exception("feed request failed")
        val feedResult = feed(response.readBytes(), response.contentType()?.toString() ?: "")
        return when (feedResult) {
            is FeedResult.Success -> feedResult.feed.getEntries(feed.id)

            is FeedResult.UnsupportedMediaType -> throw Exception("unsupported media type")
            is FeedResult.UnsupportedFeedType -> throw Exception("unsupported feed type")
            is FeedResult.IOError -> throw feedResult.cause
            is FeedResult.ParserError -> throw feedResult.cause
        }
    }
}

internal fun ParsedFeed.toFeed(feedUrl: Url): Pair<FeedTable.Feed, List<LinkTable.Link>> {
    return when (this) {
        is AtomFeed -> {
            val selfLink = links.single { it.rel == AtomLinkRel.Self }
            val links = links.map { it.toLink(feedId = selfLink.href, entryId = null) }

            Pair(
                FeedTable.Feed(
                    id = selfLink.href,
                    title = title,
                    extOpenEntriesInBrowser = false,
                    extBlockedWords = "",
                    extShowPreviewImages = null,
                ), links
            )
        }

        is RssFeed -> {
            val selfLink = LinkTable.Link(
                feedId = channel.link,
                entryId = null,
                href = feedUrl.toString(),
                rel = AtomLinkRel.Self,
                type = null,
                hreflang = null,
                title = null,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
                id = null,
            )

            val alternateLink = LinkTable.Link(
                feedId = channel.link,
                entryId = null,
                href = channel.link,
                rel = AtomLinkRel.Alternate,
                type = null,
                hreflang = null,
                title = null,
                length = null,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
                id = null,
            )

            Pair(
                FeedTable.Feed(
                    id = channel.link,
                    title = channel.title,
                    extOpenEntriesInBrowser = false,
                    extBlockedWords = "",
                    extShowPreviewImages = null,
                ), listOf(selfLink, alternateLink)
            )
        }
    }
}

internal fun ParsedFeed.getEntries(feedId: String): List<Pair<EntryTable.Entry, List<LinkTable.Link>>> {
    return when (this) {
        is RssFeed -> {
            this.channel.items
                .getOrElse { emptyList() }
                .filter { it.isSuccess }
                .map { it.getOrThrow().toEntry(feedId) }
        }

        is AtomFeed -> {
            this.entries.map { it.toEntry(feedId) }
        }
    }
}

internal fun AtomLink.toLink(
    feedId: String?,
    entryId: String?,
): LinkTable.Link {
    return LinkTable.Link(
        id = null,
        feedId = feedId,
        entryId = entryId,
        href = href,
        rel = rel,
        type = type,
        hreflang = hreflang,
        title = title,
        length = length,
        extEnclosureDownloadProgress = null,
        extCacheUri = null,
    )
}

internal fun AtomEntry.toEntry(feedId: String): Pair<EntryTable.Entry, List<LinkTable.Link>> {
    return Pair(
        EntryTable.Entry(
            contentType = content.type.toString(),
            contentSrc = content.src,
            contentText = content.text,
            summary = summary?.text ?: "",
            id = id,
            feedId = feedId,
            title = title,
            published = published.toInstant(),
            updated = updated.toInstant(),
            authorName = authorName,
            extRead = false,
            extReadSynced = true,
            extBookmarked = false,
            extBookmarkedSynced = true,
            extCommentsUrl = "",
            extOpenGraphImageChecked = false,
            extOpenGraphImageUrl = "",
            extOpenGraphImageFetchedAt = null,
            extOpenGraphImageLog = "[]",
        ), links.map {
            LinkTable.Link(
                id = null,
                feedId = null,
                entryId = id,
                href = it.href,
                rel = it.rel,
                type = it.type,
                hreflang = it.hreflang,
                title = it.title,
                length = it.length,
                extEnclosureDownloadProgress = null,
                extCacheUri = null,
            )
        }
    )
}

internal fun RssItem.toEntry(feedId: String): Pair<EntryTable.Entry, List<LinkTable.Link>> {
    val id = when (val guid = guid) {
        is RssItemGuid.StringGuid -> "guid:${guid.value}"
        is RssItemGuid.UrlGuid -> "guid:${guid.value}"
        else -> {
            val feedIdComponent = "feed-id:$feedId"
            val titleHashComponent = "title-sha256:${sha256(title ?: "")}"
            val descriptionHashComponent = "description-sha256:${sha256(description ?: "")}"
            "$feedIdComponent,$titleHashComponent,$descriptionHashComponent"
        }
    }

    val links = mutableListOf<LinkTable.Link>()

    if (!link.isNullOrBlank()) {
        links += LinkTable.Link(
            id = null,
            feedId = null,
            entryId = id,
            href = link,
            rel = AtomLinkRel.Alternate,
            type = "text/html",
            hreflang = "",
            title = "",
            length = null,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        )
    }

    if (enclosure != null) {
        links += LinkTable.Link(
            id = null,
            feedId = null,
            entryId = id,
            href = enclosure.url,
            rel = AtomLinkRel.Enclosure,
            type = enclosure.type,
            hreflang = "",
            title = "",
            length = enclosure.length,
            extEnclosureDownloadProgress = null,
            extCacheUri = null,
        )
    }

    val rawDescription = description ?: ""
    val summary = rawDescription.toEntrySummary()

    return Pair(
        EntryTable.Entry(
            contentType = "html",
            contentSrc = "",
            contentText = rawDescription,
            summary = summary,
            id = id,
            feedId = feedId,
            title = title ?: "",
            published = pubDate ?: Clock.System.now(),
            updated = pubDate ?: Clock.System.now(),
            authorName = author ?: "",
            extRead = false,
            extReadSynced = true,
            extBookmarked = false,
            extBookmarkedSynced = true,
            extCommentsUrl = "",
            extOpenGraphImageChecked = false,
            extOpenGraphImageUrl = "",
            extOpenGraphImageFetchedAt = null,
            extOpenGraphImageLog = "[]",
        ), links
    )
}

private fun sha256(string: String): String {
    return string.encodeUtf8().sha256().base64()
}
