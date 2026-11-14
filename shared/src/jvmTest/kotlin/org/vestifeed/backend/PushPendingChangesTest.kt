package org.vestifeed.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.entry
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.testDb
import org.vestifeed.util.toUrl

/**
 * The push side of sync: a full sync and an entry action both flush pending
 * read/bookmark changes, and those flushes must not race each other or drop a
 * state change made while a request is in flight.
 */
class PushPendingChangesTest {

    /**
     * A Miniflux with no network: the push tests only need the two update
     * primitives and a way to pause inside them.
     */
    private class FakeMiniflux(db: Database) : Miniflux(
        client = HttpClient(CIO),
        baseUrl = "http://localhost/v1/".toUrl(),
        db = db,
    ) {
        val readPushes = mutableListOf<Pair<List<String>, Boolean>>()
        val bookmarkPushes = mutableListOf<Pair<List<String>, Boolean>>()
        var readCalls = 0
        var onRead: (suspend (List<String>, Boolean) -> Unit)? = null
        var onBookmark: (suspend (List<EntryTable.EntryWithoutContent>, Boolean) -> Unit)? = null

        override suspend fun addFeed(url: Url, categoryId: Long?): Backend.AddFeedResult =
            throw NotImplementedError()

        override suspend fun updateFeedTitle(feedId: String, newTitle: String): Result<Unit> =
            Result.success(Unit)

        override suspend fun deleteFeed(feedId: String): Result<Unit> = Result.success(Unit)

        override suspend fun markEntriesAsRead(entriesIds: List<String>, read: Boolean) {
            readCalls++
            onRead?.invoke(entriesIds, read)
            readPushes += entriesIds to read
        }

        override suspend fun markEntriesAsBookmarked(
            entries: List<EntryTable.EntryWithoutContent>,
            bookmarked: Boolean,
        ) {
            onBookmark?.invoke(entries, bookmarked)
            bookmarkPushes += entries.map { it.id } to bookmarked
        }
    }

    /**
     * The sync's push and an entry action's push must not overlap: two requests
     * for the same entry in flight can arrive out of order, and the older one
     * then wins on the server.
     */
    @Test
    fun overlappingPushesAreSerialized() = runBlocking {
        val db = testDb()
        db.entry.insertOrReplace(
            listOf(entry().copy(id = "1", extRead = true, extReadSynced = false))
        )
        val api = FakeMiniflux(db)

        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        api.onRead = { _, _ ->
            if (!firstStarted.isCompleted) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }

        val first = launch(Dispatchers.IO) { api.pushPendingChanges() }
        firstStarted.await()

        // Undispatched so it is known to have attempted the lock: it must be
        // parked there, with no API call made yet.
        val second = launch(start = CoroutineStart.UNDISPATCHED) { api.pushPendingChanges() }
        assertEquals("the second push must wait for the first", 1, api.readCalls)

        releaseFirst.complete(Unit)
        first.join()
        second.join()

        // The second push found nothing pending once it acquired the lock.
        assertEquals(1, api.readCalls)
        assertEquals(0, db.entry.selectByReadSynced(false).size)
    }

    /**
     * A read flipped while the "unread" request is in flight must stay pending:
     * marking it synced would make the following pull adopt the stale server
     * state and put the read entry back in the Unread tab.
     */
    @Test
    fun readFlippedDuringAPushStaysPendingAndIsPushedAfterwards() = runBlocking {
        val db = testDb()
        db.entry.insertOrReplace(
            listOf(entry().copy(id = "1", extRead = false, extReadSynced = false))
        )
        val api = FakeMiniflux(db)

        var flipped = false
        api.onRead = { _, read ->
            if (!flipped && !read) {
                flipped = true
                db.entry.updateReadAndReadSynced("1", extRead = true, extReadSynced = false)
            }
        }

        api.pushPendingChanges()

        val pending = db.entry.selectByReadSynced(false)
        assertEquals(1, pending.size)
        assertTrue("the newer read must not be marked delivered", pending.single().extRead)

        api.pushPendingChanges()

        assertEquals(0, db.entry.selectByReadSynced(false).size)
        assertEquals(true, api.readPushes.last().second)
    }

    /** The same contract for a bookmark flipped during an unbookmark request. */
    @Test
    fun bookmarkFlippedDuringAPushStaysPendingAndIsPushedAfterwards() = runBlocking {
        val db = testDb()
        db.entry.insertOrReplace(
            listOf(entry().copy(id = "1", extBookmarked = false, extBookmarkedSynced = false))
        )
        val api = FakeMiniflux(db)

        var flipped = false
        api.onBookmark = { _, bookmarked ->
            if (!flipped && !bookmarked) {
                flipped = true
                db.entry.updateBookmarkedAndBookmarkedSynced(
                    "1",
                    extBookmarked = true,
                    extBookmarkedSynced = false,
                )
            }
        }

        api.pushPendingChanges()

        val pending = db.entry.selectByBookmarkedSynced(false)
        assertEquals(1, pending.size)
        assertTrue("the newer bookmark must not be marked delivered", pending.single().extBookmarked)

        api.pushPendingChanges()

        assertEquals(0, db.entry.selectByBookmarkedSynced(false).size)
        assertEquals(true, api.bookmarkPushes.last().second)
    }

    /**
     * Read and bookmark groups are pushed with their absolute value, so a
     * server that already has the entry in the desired state is a no-op rather
     * than a toggle. The HTTP-level shape is covered in [MinifluxTest]; this
     * pins the value handed to the backend for each group.
     */
    @Test
    fun pushesCarryTheDesiredAbsoluteStateForBothGroups() = runBlocking {
        val db = testDb()
        db.entry.insertOrReplace(
            listOf(
                entry().copy(id = "read", extRead = true, extReadSynced = false),
                entry().copy(id = "unread", extRead = false, extReadSynced = false),
                entry().copy(id = "saved", extBookmarked = true, extBookmarkedSynced = false),
                entry().copy(id = "unsaved", extBookmarked = false, extBookmarkedSynced = false),
            )
        )
        val api = FakeMiniflux(db)

        api.pushPendingChanges()

        assertEquals(
            setOf(listOf("read") to true, listOf("unread") to false),
            api.readPushes.toSet(),
        )
        assertEquals(
            setOf(listOf("saved") to true, listOf("unsaved") to false),
            api.bookmarkPushes.toSet(),
        )
        assertEquals(0, db.entry.selectByReadSynced(false).size)
        assertEquals(0, db.entry.selectByBookmarkedSynced(false).size)
    }
}
