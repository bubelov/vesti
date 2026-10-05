package org.vestifeed.backend

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.vestifeed.db.Database
import org.vestifeed.db.table.FeedTable
import org.vestifeed.http.vestiFetchHttpClient
import org.vestifeed.parser.FeedResult
import org.vestifeed.parser.feed
import org.vestifeed.platform.proxiedUrl
import org.vestifeed.util.toUrl

open class Embedded(
    db: Database,
    userAgent: String = "",
    httpClient: HttpClient = vestiFetchHttpClient(userAgent),
) : Backend(db) {

    private val httpClient = httpClient

    private val fetcher = EmbeddedFeedFetcher(db, httpClient)

    override suspend fun addFeed(url: Url, categoryId: Long?): AddFeedResult {
        val response = httpClient.get(proxiedUrl(url.toString()))

        if (!response.status.isSuccess()) {
            throw Exception("Cannot fetch feed (url = $url, code = ${response.status.value})")
        }

        val contentType = response.contentType()?.toString() ?: ""

        if (contentType.startsWith("text/html")) {
            val html = runCatching {
                Ksoup.parse(response.bodyAsText())
            }.getOrElse {
                throw Exception("Failed to read response", it)
            }

            val feedElements = buildList {
                addAll(html.select("link[type=\"application/atom+xml\"]"))
                addAll(html.select("link[type=\"application/rss+xml\"]"))
            }

            if (feedElements.isEmpty()) {
                throw Exception("Cannot find feed links in HTML page (url = $url)")
            }

            val href = feedElements.first().attr("href")
            val absolute = !href.startsWith("/")

            return if (absolute) {
                addFeed(href.toUrl(), categoryId)
            } else {
                addFeed("$url$href".toUrl(), categoryId)
            }
        } else {
            val result = runCatching {
                feed(response.readBytes(), contentType)
            }.getOrElse {
                throw Exception("Failed to read response", it)
            }

            return when (result) {
                is FeedResult.Success -> {
                    val (feed, feedLinks) = result.feed.toFeed(url)
                    AddFeedResult(
                        feed = feed,
                        feedLinks = feedLinks,
                        entries = result.feed.getEntries(feed.id),
                    )
                }

                is FeedResult.UnsupportedMediaType -> {
                    throw Exception("Unsupported media type: ${result.mediaType}")
                }

                is FeedResult.UnsupportedFeedType -> {
                    throw Exception("Unsupported feed type")
                }

                is FeedResult.IOError -> {
                    throw result.cause
                }

                is FeedResult.ParserError -> {
                    throw result.cause
                }
            }
        }
    }

    override suspend fun getFeeds(): List<FeedTable.Feed> {
        return db.feed.selectAll()
    }

    override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun deleteFeed(feedId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun sync(initial: Boolean) {
        val feeds = db.feed.selectAll()

        for (feed in feeds) {
            val feedEntries = fetcher.fetchEntries(feed)
            db.transaction {
                db.entry.insertOrReplace(feedEntries.map { it.first })
                for (feedEntry in feedEntries) {
                    db.link.insertForEntry(feedEntry.first.id, feedEntry.second)
                }
            }
        }
    }
}
