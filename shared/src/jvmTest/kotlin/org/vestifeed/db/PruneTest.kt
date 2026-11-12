package org.vestifeed.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.table.LinkTable
import org.vestifeed.parser.AtomLinkRel

class PruneTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = db()
    }

    @Test
    fun pruneEntriesNotIn_deletesMissingEntriesAndTheirLinks() = runBlocking<Unit> {
        db.entry.insertOrReplace(
            listOf(
                entry().copy(id = "1"),
                entry().copy(id = "2"),
                entry().copy(id = "3"),
            )
        )
        db.link.insertForEntry("1", listOf(link("1")))
        db.link.insertForEntry("3", listOf(link("3")))

        val deleted = db.pruneEntriesNotIn(setOf(1L, 2L))

        assertEquals(1, deleted)
        assertNotNull(db.entry.selectById("1"))
        assertNotNull(db.entry.selectById("2"))
        assertNull(db.entry.selectById("3"))
        assertEquals(1, db.link.selectByEntryId("1").size)
        assertTrue(db.link.selectByEntryId("3").isEmpty())
    }

    @Test
    fun pruneEntriesNotIn_keepsNonNumericEmbeddedEntries() = runBlocking<Unit> {
        db.entry.insertOrReplace(
            listOf(
                entry().copy(id = "1"),
                // Embedded entries use URLs as IDs and are not part of the
                // Miniflux catalog, so a Miniflux reconcile must leave them.
                entry().copy(id = "https://example.com/post"),
            )
        )

        assertEquals(0, db.pruneEntriesNotIn(setOf(1L)))
        assertNotNull(db.entry.selectById("https://example.com/post"))
    }

    @Test
    fun pruneEntriesNotIn_emptyServerSetIsANoOp() = runBlocking<Unit> {
        db.entry.insertOrReplace(listOf(entry().copy(id = "1")))

        assertEquals(0, db.pruneEntriesNotIn(emptySet()))
        assertNotNull(db.entry.selectById("1"))
    }

    private fun link(entryId: String) = LinkTable.Link(
        id = null,
        feedId = null,
        entryId = entryId,
        href = "https://example.com/$entryId",
        rel = AtomLinkRel.Alternate,
        type = "text/html",
        hreflang = null,
        title = null,
        length = null,
        extEnclosureDownloadProgress = null,
        extCacheUri = null,
    )
}
