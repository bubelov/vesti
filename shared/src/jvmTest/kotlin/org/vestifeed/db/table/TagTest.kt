package org.vestifeed.db.table

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.testDb

class TagTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = testDb()
    }

    @Test
    fun tagSchema_createTableStatement() {
        val statement = TagTable.SCHEMA
        assertTrue(statement.contains("CREATE TABLE tag"))
        assertTrue(statement.contains("id TEXT PRIMARY KEY NOT NULL"))
        assertTrue(statement.contains("name TEXT NOT NULL UNIQUE"))
        assertTrue(statement.contains("ext_source TEXT NOT NULL"))
        assertTrue(statement.contains("ext_miniflux_id INTEGER"))
    }

    @Test
    fun tagQueries_insertOrReplace() = runBlocking<Unit> {
        val tag = createTag()
        db.tag.insertOrReplace(tag)
        assertEquals(listOf(tag), db.tag.selectAll())
    }

    @Test
    fun tagQueries_insertOrReplace_multiple() = runBlocking<Unit> {
        val tags = listOf(
            createTag(id = "t1", name = "a"),
            createTag(id = "t2", name = "b"),
            createTag(id = "t3", name = "c"),
        )
        db.tag.insertOrReplace(tags)
        assertEquals(3, db.tag.selectAll().size)
    }

    @Test
    fun tagQueries_insertOrReplace_emptyList() = runBlocking<Unit> {
        db.tag.insertOrReplace(emptyList())
        assertTrue(db.tag.selectAll().isEmpty())
    }

    @Test
    fun tagQueries_insertOrReplace_updatesExisting() = runBlocking<Unit> {
        val tag = createTag(name = "Tech")
        db.tag.insertOrReplace(tag)

        val updated = tag.copy(name = "Technology")
        db.tag.insertOrReplace(updated)

        val all = db.tag.selectAll()
        assertEquals(1, all.size)
        assertEquals("Technology", all.single().name)
        assertEquals(tag.id, all.single().id)
    }

    @Test
    fun tagQueries_selectAll_sortsByName() = runBlocking<Unit> {
        val tags = listOf(
            createTag(id = "t1", name = "Zebra"),
            createTag(id = "t2", name = "Apple"),
            createTag(id = "t3", name = "Mango"),
        )
        db.tag.insertOrReplace(tags)

        val result = db.tag.selectAll()
        assertEquals("Apple", result[0].name)
        assertEquals("Mango", result[1].name)
        assertEquals("Zebra", result[2].name)
    }

    @Test
    fun tagQueries_selectById() = runBlocking<Unit> {
        val tags = listOf(
            createTag(id = "t1", name = "a"),
            createTag(id = "t2", name = "b"),
            createTag(id = "t3", name = "c"),
        )
        db.tag.insertOrReplace(tags)

        val target = tags[1]
        assertEquals(target, db.tag.selectById(target.id))
    }

    @Test
    fun tagQueries_selectById_notFound() = runBlocking<Unit> {
        assertNull(db.tag.selectById("non-existent-id"))
    }

    @Test
    fun tagQueries_selectByMinifluxId() = runBlocking<Unit> {
        val tag = createTag(name = "Tech", extMinifluxId = 42L)
        db.tag.insertOrReplace(tag)
        assertEquals(tag, db.tag.selectByMinifluxId(42L))
        assertNull(db.tag.selectByMinifluxId(999L))
    }

    @Test
    fun tagQueries_selectByName() = runBlocking<Unit> {
        val tag = createTag(name = "Tech")
        db.tag.insertOrReplace(tag)
        assertEquals(tag, db.tag.selectByName("Tech"))
        assertNull(db.tag.selectByName("Other"))
    }

    @Test
    fun tagQueries_deleteById() = runBlocking<Unit> {
        val tags = listOf(createTag(id = "t1", name = "a"), createTag(id = "t2", name = "b"))
        db.tag.insertOrReplace(tags)

        db.tag.deleteById(tags[1].id)

        val result = db.tag.selectAll()
        assertEquals(1, result.size)
        assertEquals(tags[0].id, result.single().id)
    }

    @Test
    fun tagQueries_deleteAll() = runBlocking<Unit> {
        val tags = listOf(createTag(id = "t1", name = "a"), createTag(id = "t2", name = "b"))
        db.tag.insertOrReplace(tags)
        db.tag.deleteAll()
        assertTrue(db.tag.selectAll().isEmpty())
    }

    @Test
    fun tagQueries_minifluxSourcePreserved() = runBlocking<Unit> {
        val tag = createTag(name = "Tech", extSource = TagTable.Source.Miniflux, extMinifluxId = 7L)
        db.tag.insertOrReplace(tag)
        assertEquals(TagTable.Source.Miniflux, db.tag.selectById(tag.id)!!.extSource)
        assertEquals(7L, db.tag.selectById(tag.id)!!.extMinifluxId)
    }

    @Test
    fun tagQueries_embeddedSourcePreserved() = runBlocking<Unit> {
        val tag = createTag(name = "Tech", extSource = TagTable.Source.Embedded, extMinifluxId = null)
        db.tag.insertOrReplace(tag)
        val loaded = db.tag.selectById(tag.id)!!
        assertEquals(TagTable.Source.Embedded, loaded.extSource)
        assertNull(loaded.extMinifluxId)
    }

    @Test
    fun tagQueries_uniqueNameConstraintEnforced() = runBlocking<Unit> {
        val tag = createTag(name = "Tech")
        db.tag.insertOrReplace(tag)
        // SQLite UNIQUE constraint is checked on commit; the second insert
        // would throw at the statement step boundary for an INSERT that
        // violates the unique name constraint. We catch the thrown
        // IllegalStateException to assert the constraint exists.
        val duplicate = createTag(name = "Tech", id = "different-id")
        try {
            db.tag.insertOrReplace(duplicate)
            // insertOrReplace uses ON CONFLICT REPLACE under the hood, so
            // the duplicate replaces the existing row instead of throwing.
            // Confirm that the existing tag was replaced.
            val all = db.tag.selectAll()
            assertEquals(1, all.size)
            assertEquals("different-id", all.single().id)
        } catch (e: Exception) {
            // OK if it does throw; we just want to assert the constraint is in place.
            assertTrue(e.message?.contains("UNIQUE") == true || e.message?.contains("unique") == true)
        }
    }

    private fun createTag(
        id: String = "tag-id",
        name: String = "Test Tag",
        extSource: TagTable.Source = TagTable.Source.Embedded,
        extMinifluxId: Long? = null,
    ) = TagTable.Tag(
        id = id,
        name = name,
        extSource = extSource,
        extMinifluxId = extMinifluxId,
    )
}
