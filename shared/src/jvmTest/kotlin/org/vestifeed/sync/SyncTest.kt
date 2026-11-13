package org.vestifeed.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.vestifeed.backend.FakeBackend
import org.vestifeed.db.entry
import org.vestifeed.db.testDb

/** The sync outcome the entries screen uses to decide whether to scroll. */
class SyncTest {

    @Test
    fun startupSync_reportsHowManyEntriesItAdded() = runBlocking {
        val db = testDb()
        var entriesToAdd = 2
        val backend = FakeBackend(db) {
            repeat(entriesToAdd) {
                db.entry.insertOrReplace(listOf(entry()))
            }
            entriesToAdd = 0
        }
        val sync = Sync(this, db, backendFactory = { _, _ -> backend })

        sync.runInForeground(SyncReason.Startup)

        val first = sync.lastRun.value
        assertEquals(SyncReason.Startup, first?.reason)
        assertEquals(2L, first?.newEntries)

        // A second startup run with nothing new reports zero new entries but a
        // fresh id, so the screen still knows the run happened.
        sync.runInForeground(SyncReason.Startup)

        val second = sync.lastRun.value
        assertEquals(SyncReason.Startup, second?.reason)
        assertEquals(0L, second?.newEntries)
        assertEquals((first?.id ?: 0L) + 1L, second?.id)
    }

    @Test
    fun userSync_keepsItsReason() = runBlocking {
        val db = testDb()
        val backend = FakeBackend(db) {}
        val sync = Sync(this, db, backendFactory = { _, _ -> backend })

        sync.runInForeground(SyncReason.User)

        assertEquals(SyncReason.User, sync.lastRun.value?.reason)
    }
}
