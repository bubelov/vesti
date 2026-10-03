package org.vestifeed.parser

import com.fleeksoft.ksoup.nodes.Document
import kotlin.time.Instant
import org.vestifeed.util.toInstantOrNull

data class RssFeed(
    // Mandatory attribute that specifies the version of RSS that the document conforms to
    val version: RssVersion,
    val channel: RssChannel,
) : Feed()

enum class RssVersion {
    RSS_2_0,
    RSS_0_92,
    RSS_1_0,
}

data class RssChannel(
    // The name of the channel. It's how people refer to your service. If you have an HTML website
    // that contains the same information as your RSS file, the title of your channel should be the
    // same as the title of your website
    val title: String,
    // The URL to the HTML website corresponding to the channel
    val link: String,
    // Phrase or sentence describing the channel
    val description: String,
    // A channel may contain any number of <item>s. An item may represent a "story" -- much like a
    // story in a newspaper or magazine; if so its description is a synopsis of the story, and the
    // link points to the full story. An item may also be complete in itself, if so, the
    // description contains the text (entity-encoded HTML is allowed), and the link and title may
    // be omitted. All elements of an item are optional, however at least one of title or
    // description must be present
    val items: Result<List<Result<RssItem>>>
)

data class RssItem(
    // The title of the item
    val title: String?,
    // The URL of the item
    val link: String?,
    // The item synopsis.
    val description: String?,
    // Email address of the author of the item
    val author: String?,
    // Includes the item in one or more categories
    val categories: List<RssItemCategory>,
    // URL of a page for comments relating to the item
    val comments: String?,
    // Describes a media object that is attached to the item
    val enclosure: RssItemEnclosure?,
    // A string that uniquely identifies the item
    val guid: RssItemGuid?,
    // Indicates when the item was published
    val pubDate: Instant?,
    // The RSS channel that the item came from
    val source: RssItemSource?,
)

data class RssItemCategory(
    // A string that identifies a categorization taxonomy
    val domain: String?,
    // Forward-slash-separated string that identifies a hierarchic location in the indicated
    // taxonomy
    val value: String,
)

data class RssItemEnclosure(
    // url says where the enclosure is located
    val url: String,
    // length says how big it is in bytes
    val length: Long,
    // type says what its type is, a standard MIME type
    val type: String,
)

sealed class RssItemGuid {
    data class StringGuid(val value: String) : RssItemGuid()

    // only if isPermalink = true
    data class UrlGuid(val value: String) : RssItemGuid()
}

data class RssItemSource(
    // url links to the source XML
    val url: String,
    // value is the name of the RSS channel that the item came from, derived from its <title>
    val value: String?,
)

fun rssFeed(document: Document): Result<RssFeed> {
    val rawVersion = document.children().first()!!.attr("version")

    if (rawVersion.isNullOrBlank()) {
        return Result.failure(Exception("RSS version is missing"))
    }

    val version = when (rawVersion) {
        "2.0" -> RssVersion.RSS_2_0
        "0.92" -> RssVersion.RSS_0_92
        else -> return Result.failure(Exception("Unsupported RSS version: $rawVersion"))
    }

    val channel = document.children().first()!!.getElementsByTag("channel").first()!!

    val title = channel.getElementsByTag("title").first()?.text()
        ?: return Result.failure(Exception("Channel has no title"))

    val link = channel.getElementsByTag("link").first()?.text()
        ?: return Result.failure(Exception("Channel has no link"))

    val description = channel.getElementsByTag("description").first()?.text()
        ?: return Result.failure(Exception("Channel has no description"))

    return Result.success(
        RssFeed(
            version = version,
            channel = RssChannel(
                title = title,
                link = link,
                description = description,
                items = rssItems(document)
            ),
        )
    )
}

fun rdfFeed(document: Document): Result<RssFeed> {
    val channel = document.children().first()!!.getElementsByTag("channel").first()
        ?: return Result.failure(Exception("Missing element: channel"))

    val title = channel.getElementsByTag("title").first()?.text()
        ?: return Result.failure(Exception("Channel has no title"))

    val link = channel.getElementsByTag("link").first()?.text()
        ?: return Result.failure(Exception("Channel has no link"))

    val description = channel.getElementsByTag("description").first()?.text()
        ?: return Result.failure(Exception("Channel has no description"))

    return Result.success(
        RssFeed(
            version = RssVersion.RSS_1_0,
            channel = RssChannel(
                title = title,
                link = link,
                description = description,
                items = rssItems(document),
            ),
        )
    )
}

fun rssItems(document: Document): Result<List<Result<RssItem>>> {
    document.children().first()!!.getElementsByTag("channel").first()
        ?: return Result.failure(Exception("Missing element: channel"))

    val itemElements = document.children().first()!!.getElementsByTag("item")

    val items: List<Result<RssItem>> = itemElements.map { element ->
        val link = element.getElementsByTag("link").first()?.text()

        val categories = element.getElementsByTag("category")
            .map { categoryElement ->
                RssItemCategory(
                    domain = categoryElement.attr("domain").ifBlank { null },
                    value = categoryElement.text(),
                )
            }

        val commentsElements = element.getElementsByTag("comments")

        if (commentsElements.size > 1) {
            return@map Result.failure(
                Exception("Expected 0 or 1 comments elements but got ${commentsElements.size}"),
            )
        }

        val comments = if (commentsElements.isEmpty()) null else {
            commentsElements.single().text()
        }

        var enclosure: RssItemEnclosure? = null

        element.getElementsByTag("enclosure").first()?.apply {
            val rawUrl = attr("url").ifBlank { null }
                ?: return@map Result.failure(Exception("Enclosure URL is missing"))

            val rawLength = attr("length").ifBlank { null }
                ?: return@map Result.failure(Exception("Enclosure length is missing"))

            val length = rawLength.toLongOrNull()
                ?: return@map Result.failure(Exception("Failed to parse enclosure length"))

            val type = attr("type").ifBlank { null }
                ?: return@map Result.failure(Exception("Enclosure type is missing"))

            enclosure = RssItemEnclosure(
                url = rawUrl,
                length = length,
                type = type,
            )
        }

        val pubDate = sequenceOf(
            element.getElementsByTag("pubDate").first()?.text()?.trim(),
            element.getElementsByTag("dc:date").first()?.text()?.trim(),
        ).firstNotNullOfOrNull { rawPubDate ->
            rawPubDate?.toInstantOrNull()
        }

        var guid: RssItemGuid? = null

        element.getElementsByTag("guid").first()?.apply {
            val permalink = attr("isPermaLink") == "true"

            guid = if (permalink) {
                RssItemGuid.UrlGuid(text())
            } else {
                RssItemGuid.StringGuid(text())
            }
        }

        var source: RssItemSource? = null

        element.getElementsByTag("source").first()?.apply {
            val rawUrl = attr("url").ifBlank { null }
                ?: return@map Result.failure(Exception("Source URL is missing"))

            source = RssItemSource(
                url = rawUrl,
                value = text(),
            )
        }

        val author = sequenceOf(
            element.getElementsByTag("author").first()?.text()?.trim(),
            element.getElementsByTag("dc:creator").first()?.text()?.trim(),
        ).firstOrNull { !it.isNullOrBlank() }

        Result.success(
            RssItem(
                title = element.getElementsByTag("title").first()?.text(),
                link = link,
                description = element.getElementsByTag("description").first()?.text(),
                author = author,
                categories = categories,
                comments = comments,
                enclosure = enclosure,
                guid = guid,
                pubDate = pubDate,
                source = source,
            )
        )
    }

    return Result.success(items)
}
