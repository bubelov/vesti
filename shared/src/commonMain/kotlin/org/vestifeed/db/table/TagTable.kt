package org.vestifeed.db.table

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class TagTable(private val conn: SQLiteConnection) {
    companion object {
        const val SCHEMA = """
            CREATE TABLE tag (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL UNIQUE,
                ext_source TEXT NOT NULL,
                ext_miniflux_id INTEGER
            ) STRICT;
          """
    }

    data class Tag(
        val id: String,
        val name: String,
        val extSource: Source,
        val extMinifluxId: Long?,
    )

    enum class Source {
        Miniflux,
        Embedded,
    }

    suspend fun insertOrReplace(tag: Tag) {
        insertOrReplace(listOf(tag))
    }

    suspend fun insertOrReplace(tags: List<Tag>) {
        if (tags.isEmpty()) {
            return
        }
        // Upsert by id rather than INSERT OR REPLACE: the latter deletes the
        // existing row first, and `feed_tag.tag_id` has ON DELETE CASCADE, so
        // renaming a tag would drop every feed that uses it.
        conn.prepare(
            """
            INSERT INTO tag (id, name, ext_source, ext_miniflux_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                ext_source = excluded.ext_source,
                ext_miniflux_id = excluded.ext_miniflux_id;
            """
        ).use { stmt ->
            tags.forEach { tag ->
                stmt.bindText(1, tag.id)
                stmt.bindText(2, tag.name)
                stmt.bindText(3, tag.extSource.name.lowercase())
                if (tag.extMinifluxId == null) {
                    stmt.bindNull(4)
                } else {
                    stmt.bindLong(4, tag.extMinifluxId)
                }
                stmt.step()
                stmt.reset()
            }
        }
    }

    suspend fun selectAll(): List<Tag> {
        conn.prepare(
            """
            SELECT id, name, ext_source, ext_miniflux_id
            FROM tag
            ORDER BY name;
            """
        ).use { stmt ->
            return buildList {
                while (stmt.step()) {
                    add(
                        Tag(
                            id = stmt.getText(0),
                            name = stmt.getText(1),
                            extSource = sourceFromString(stmt.getText(2)),
                            extMinifluxId = if (stmt.isNull(3)) null else stmt.getLong(3),
                        )
                    )
                }
            }
        }
    }

    suspend fun selectById(id: String): Tag? {
        conn.prepare(
            """
            SELECT id, name, ext_source, ext_miniflux_id
            FROM tag
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, id)
            return if (stmt.step()) {
                Tag(
                    id = stmt.getText(0),
                    name = stmt.getText(1),
                    extSource = sourceFromString(stmt.getText(2)),
                    extMinifluxId = if (stmt.isNull(3)) null else stmt.getLong(3),
                )
            } else null
        }
    }

    suspend fun selectByMinifluxId(minifluxId: Long): Tag? {
        conn.prepare(
            """
            SELECT id, name, ext_source, ext_miniflux_id
            FROM tag
            WHERE ext_miniflux_id = ?;
            """
        ).use { stmt ->
            stmt.bindLong(1, minifluxId)
            return if (stmt.step()) {
                Tag(
                    id = stmt.getText(0),
                    name = stmt.getText(1),
                    extSource = sourceFromString(stmt.getText(2)),
                    extMinifluxId = if (stmt.isNull(3)) null else stmt.getLong(3),
                )
            } else null
        }
    }

    suspend fun selectByName(name: String): Tag? {
        conn.prepare(
            """
            SELECT id, name, ext_source, ext_miniflux_id
            FROM tag
            WHERE name = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, name)
            return if (stmt.step()) {
                Tag(
                    id = stmt.getText(0),
                    name = stmt.getText(1),
                    extSource = sourceFromString(stmt.getText(2)),
                    extMinifluxId = if (stmt.isNull(3)) null else stmt.getLong(3),
                )
            } else null
        }
    }

    suspend fun deleteById(id: String) {
        conn.prepare(
            """
            DELETE FROM tag
            WHERE id = ?;
            """
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.step()
        }
    }

    suspend fun deleteAll() {
        conn.execSQL("DELETE FROM tag;")
    }

    private fun sourceFromString(value: String): Source {
        return Source.entries.single { it.name.lowercase() == value }
    }
}
