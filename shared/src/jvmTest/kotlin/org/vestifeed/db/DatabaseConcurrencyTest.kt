package org.vestifeed.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTagTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.db.table.TagTable

class DatabaseConcurrencyTest {

    /**
     * Two callers (the UI and a background worker) connect at startup. If
     * `connect()` published the connection before its table fields were ready,
     * the loser of the race would return early and touch an uninitialized
     * `lateinit` table. Every caller must end up with a fully initialized
     * database.
     */
    @Test
    fun concurrentConnectPublishesFullyInitializedState() = runBlocking<Unit> {
        val db = Database(BundledSQLiteDriver(), ":memory:")
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val jobs = (1..16).map {
            launch(Dispatchers.Default) {
                try {
                    db.connect()
                    // Touch a lateinit table: an early return would throw here.
                    db.conf.select()
                    db.entry.selectCount()
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        jobs.joinAll()

        assertTrue("concurrent connect() failed: $errors", errors.isEmpty())
    }

    /**
     * Same race, but with a v11 on-disk database whose v11→v12 `DROP COLUMN`
     * migration takes long enough to widen the pre-publish window. Booting a
     * real DB then hammering `connect()` from many threads is what exposed the
     * startup fetch stall in the desktop app.
     */
    @Test
    fun concurrentConnectWhileMigratingDropsOgColumns() = runBlocking<Unit> {
        val file = File.createTempFile("vesti-connect-race", ".db")
        file.delete()

        BundledSQLiteDriver().open(file.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V11)
            conn.execSQL(LinkTable.SCHEMA)
            conn.execSQL(ConfTable.SCHEMA)
            conn.execSQL(TagTable.SCHEMA)
            conn.execSQL(FeedTagTable.SCHEMA)
            conn.execSQL("BEGIN;")
            conn.prepare(
                """
                INSERT INTO entry (
                    content_type, content_src, content_text, summary, id, feed_id,
                    title, published, updated, author_name, ext_read, ext_read_synced,
                    ext_bookmarked, ext_bookmarked_synced, ext_comments_url,
                    ext_og_image_checked, ext_og_image_url, ext_og_image_width,
                    ext_og_image_height, ext_og_image_fetched_at, ext_og_log
                ) VALUES ('html', '', '', '', ?, 'feed-1', 'Entry',
                    '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '',
                    0, 1, 0, 1, '', 0, '', 0, 0, '', '[]');
                """.trimIndent()
            ).use { stmt ->
                repeat(30_000) { i ->
                    stmt.bindText(1, "entry-$i")
                    stmt.step()
                    stmt.reset()
                }
            }
            conn.execSQL("COMMIT;")
            conn.execSQL("PRAGMA user_version=11;")
        }

        val db = Database(BundledSQLiteDriver(), file.absolutePath)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val jobs = (1..32).map {
            launch(Dispatchers.Default) {
                try {
                    db.connect()
                    db.conf.select()
                    db.entry.selectCount()
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        jobs.joinAll()

        file.delete()
        assertTrue("concurrent connect() during migration failed: $errors", errors.isEmpty())
    }
}
