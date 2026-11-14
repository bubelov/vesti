package org.vestifeed.og

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.db
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomLinkRel

/**
 * Preview images are supplementary, so the fetcher must not spend network or
 * battery while the app is in the background.
 *
 * [OgImagePlanningTest] covers the predicate; this checks that the loop honours
 * it, which is the part a refactor could quietly break. The foreground run at
 * the end is deliberate: it proves the seeded row is a real candidate, so the
 * background assertion cannot pass just because there was nothing to fetch.
 */
class OgImageFetcherTest {

    @Test
    fun makesNoRequestInTheBackgroundAndFetchesInTheForeground() = runTest {
        val database = db()
        seedPendingEntry(database)

        val requests = AtomicInteger(0)
        val engine = MockEngine {
            requests.incrementAndGet()
            respond(
                content = "<html><body><p>no og:image here</p></body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val client = HttpClient(engine)

        OgImageFetcher(
            db = database,
            userAgent = "test",
            httpClient = client,
            isForeground = { false },
        ).runOnce()

        assertEquals("A backgrounded app must not fetch previews", 0, requests.get())

        // Same database, same pending entry: now that the app is on screen it
        // is fetched, which also shows the row above really is a candidate.
        OgImageFetcher(
            db = database,
            userAgent = "test",
            httpClient = client,
            isForeground = { true },
        ).runOnce()

        assertEquals("A foregrounded app should fetch the pending preview", 1, requests.get())
    }

    /**
     * One entry that [EntryTable.selectPendingOgImageEntries] will return:
     * unchecked, its feed follows the global preview setting, and it has the
     * alternate link the fetcher needs to find the article page.
     */
    private suspend fun seedPendingEntry(database: Database) {
        database.feed.insertOrReplace(
            FeedTable.Feed(
                id = "feed-1",
                title = "Feed",
                extOpenEntriesInBrowser = null,
                extBlockedWords = "",
                extShowPreviewImages = null,
            ),
        )
        database.entry.insertOrReplace(
            listOf(
                EntryTable.Entry(
                    contentType = "html",
                    contentSrc = "",
                    contentText = "<p>Body</p>",
                    summary = null,
                    id = "entry-1",
                    feedId = "feed-1",
                    title = "Entry",
                    published = Clock.System.now(),
                    updated = Clock.System.now(),
                    authorName = "",
                    extRead = false,
                    extReadSynced = true,
                    extBookmarked = false,
                    extBookmarkedSynced = true,
                    extCommentsUrl = "",
                    extOpenGraphImageChecked = false,
                    extOpenGraphImageUrl = "",
                    extOpenGraphImageFetchedAt = null,
                    extOpenGraphImageLog = "[]",
                ),
            ),
        )
        database.link.insertForEntry(
            "entry-1",
            listOf(
                LinkTable.Link(
                    id = null,
                    feedId = null,
                    entryId = "entry-1",
                    href = "https://example.com/article",
                    rel = AtomLinkRel.Alternate,
                    type = "text/html",
                    hreflang = null,
                    title = null,
                    length = null,
                    extEnclosureDownloadProgress = null,
                    extCacheUri = null,
                ),
            ),
        )
    }
}
