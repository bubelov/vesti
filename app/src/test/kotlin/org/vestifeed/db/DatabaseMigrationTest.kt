package org.vestifeed.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTagTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.TagTable
import java.io.File

class DatabaseMigrationTest {

    private lateinit var dbFile: File

    @Before
    fun before() {
        dbFile = File.createTempFile("vesti-migration-test", ".db")
    }

    @After
    fun after() {
        dbFile.delete()
    }

    @Test
    fun migrate_v1ToV2_addsEntryBodyFontSizeColumn() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            val v1Schema = """
                CREATE TABLE conf (
                    backend TEXT,
                    miniflux_url TEXT,
                    miniflux_token TEXT,
                    minifluxIncrementalSyncTimestamp TEXT,
                    show_preview_images INTEGER NOT NULL,
                    crop_preview_images INTEGER NOT NULL,
                    sync_on_startup INTEGER NOT NULL,
                    sync_in_background INTEGER NOT NULL,
                    background_sync_interval_millis INTEGER NOT NULL,
                    use_built_in_browser INTEGER NOT NULL,
                    show_preview_text INTEGER NOT NULL
                ) STRICT;
            """.trimIndent()
            conn.execSQL(v1Schema)

            conn.prepare(
                """
                INSERT INTO conf (
                    show_preview_images, crop_preview_images, sync_on_startup,
                    sync_in_background, background_sync_interval_millis,
                    use_built_in_browser, show_preview_text
                ) VALUES (1, 1, 1, 1, 10800000, 1, 1);
                """.trimIndent()
            ).use { stmt ->
                stmt.step()
            }

            conn.execSQL("PRAGMA user_version=1;")
        }

        val db = Database(driver, dbFile.absolutePath)

        val conf = db.conf.select()
        assertEquals(16, conf.entryBodyFontSize)
        assertTrue(conf.showPreviewImages)
        assertTrue(conf.syncInBackground)
        assertEquals(10800000L, conf.backgroundSyncIntervalMillis)
    }

    @Test
    fun migrate_v0ToV2_setsDefaultEntryBodyFontSize() {
        val db = Database(BundledSQLiteDriver(), dbFile.absolutePath)

        val conf = db.conf.select()
        assertEquals(16, conf.entryBodyFontSize)
    }

    @Test
    fun migrate_v2ToV3_addsShowAuthorNameColumn() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            val v2Schema = """
                CREATE TABLE conf (
                    backend TEXT,
                    miniflux_url TEXT,
                    miniflux_token TEXT,
                    minifluxIncrementalSyncTimestamp TEXT,
                    show_preview_images INTEGER NOT NULL,
                    crop_preview_images INTEGER NOT NULL,
                    sync_on_startup INTEGER NOT NULL,
                    sync_in_background INTEGER NOT NULL,
                    background_sync_interval_millis INTEGER NOT NULL,
                    use_built_in_browser INTEGER NOT NULL,
                    show_preview_text INTEGER NOT NULL,
                    entry_body_font_size INTEGER NOT NULL
                ) STRICT;
            """.trimIndent()
            conn.execSQL(v2Schema)

            conn.prepare(
                """
                INSERT INTO conf (
                    show_preview_images, crop_preview_images, sync_on_startup,
                    sync_in_background, background_sync_interval_millis,
                    use_built_in_browser, show_preview_text, entry_body_font_size
                ) VALUES (1, 1, 1, 1, 10800000, 1, 1, 16);
                """.trimIndent()
            ).use { stmt ->
                stmt.step()
            }

            conn.execSQL("PRAGMA user_version=2;")
        }

        val db = Database(driver, dbFile.absolutePath)

        val conf = db.conf.select()
        assertEquals(16, conf.entryBodyFontSize)
        assertEquals(false, conf.showAuthorName)

        db.conf.update { it.copy(showAuthorName = true) }
        assertEquals(true, db.conf.select().showAuthorName)
    }

    @Test
    fun migrate_v3ToV4_addsUseBuiltInAudioPlayerColumn() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            val v3Schema = """
                CREATE TABLE conf (
                    backend TEXT,
                    miniflux_url TEXT,
                    miniflux_token TEXT,
                    minifluxIncrementalSyncTimestamp TEXT,
                    show_preview_images INTEGER NOT NULL,
                    crop_preview_images INTEGER NOT NULL,
                    sync_on_startup INTEGER NOT NULL,
                    sync_in_background INTEGER NOT NULL,
                    background_sync_interval_millis INTEGER NOT NULL,
                    use_built_in_browser INTEGER NOT NULL,
                    show_preview_text INTEGER NOT NULL,
                    entry_body_font_size INTEGER NOT NULL,
                    show_author_name INTEGER NOT NULL DEFAULT 0
                ) STRICT;
            """.trimIndent()
            conn.execSQL(v3Schema)

            conn.prepare(
                """
                INSERT INTO conf (
                    show_preview_images, crop_preview_images, sync_on_startup,
                    sync_in_background, background_sync_interval_millis,
                    use_built_in_browser, show_preview_text, entry_body_font_size,
                    show_author_name
                ) VALUES (1, 1, 1, 1, 10800000, 1, 1, 16, 0);
                """.trimIndent()
            ).use { stmt ->
                stmt.step()
            }

            conn.execSQL("PRAGMA user_version=3;")
        }

        val db = Database(driver, dbFile.absolutePath)

        val conf = db.conf.select()
        assertEquals(false, conf.useBuiltInAudioPlayer)

        db.conf.update { it.copy(useBuiltInAudioPlayer = true) }
        assertEquals(true, db.conf.select().useBuiltInAudioPlayer)
    }

    @Test
    fun migrate_v4ToV5_createsTagAndFeedTagTables() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            val v4Schema = """
                CREATE TABLE conf (
                    backend TEXT,
                    miniflux_url TEXT,
                    miniflux_token TEXT,
                    minifluxIncrementalSyncTimestamp TEXT,
                    show_preview_images INTEGER NOT NULL,
                    crop_preview_images INTEGER NOT NULL,
                    sync_on_startup INTEGER NOT NULL,
                    sync_in_background INTEGER NOT NULL,
                    background_sync_interval_millis INTEGER NOT NULL,
                    use_built_in_browser INTEGER NOT NULL,
                    show_preview_text INTEGER NOT NULL,
                    entry_body_font_size INTEGER NOT NULL,
                    show_author_name INTEGER NOT NULL DEFAULT 0,
                    use_built_in_audio_player INTEGER NOT NULL DEFAULT 0
                ) STRICT;
            """.trimIndent()
            conn.execSQL(v4Schema)

            conn.execSQL("PRAGMA user_version=4;")
        }

        val db = Database(driver, dbFile.absolutePath)

        assertTrue(db.tag.selectAll().isEmpty())
        assertTrue(db.feedTag.selectTagIdsByFeedId("anything").isEmpty())

        val tag = TagTable.Tag(
            id = "t1",
            name = "Tech",
            extSource = TagTable.Source.Embedded,
            extMinifluxId = null,
        )
        db.tag.insertOrReplace(tag)
        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = "f1",
                title = "Feed",
                extOpenEntriesInBrowser = null,
                extBlockedWords = "",
                extShowPreviewImages = null,
            )
        )
        db.feedTag.insert(feedId = "f1", tagId = "t1")

        assertEquals(listOf(tag), db.tag.selectAll())
        assertEquals(listOf("t1"), db.feedTag.selectTagIdsByFeedId("f1"))
    }

    @Test
    fun migrate_v0ToV5_createsTagAndFeedTagTables() {
        val db = Database(BundledSQLiteDriver(), dbFile.absolutePath)
        assertTrue(db.tag.selectAll().isEmpty())
        assertTrue(db.feedTag.selectTagIdsByFeedId("anything").isEmpty())
    }

    @Test
    fun migrate_v5ToV6_addsShowTagsTabColumn() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            val v5Schema = """
                CREATE TABLE conf (
                    backend TEXT,
                    miniflux_url TEXT,
                    miniflux_token TEXT,
                    minifluxIncrementalSyncTimestamp TEXT,
                    show_preview_images INTEGER NOT NULL,
                    crop_preview_images INTEGER NOT NULL,
                    sync_on_startup INTEGER NOT NULL,
                    sync_in_background INTEGER NOT NULL,
                    background_sync_interval_millis INTEGER NOT NULL,
                    use_built_in_browser INTEGER NOT NULL,
                    show_preview_text INTEGER NOT NULL,
                    entry_body_font_size INTEGER NOT NULL,
                    show_author_name INTEGER NOT NULL DEFAULT 0,
                    use_built_in_audio_player INTEGER NOT NULL DEFAULT 0
                ) STRICT;
            """.trimIndent()
            conn.execSQL(v5Schema)

            conn.prepare(
                """
                INSERT INTO conf (
                    show_preview_images, crop_preview_images, sync_on_startup,
                    sync_in_background, background_sync_interval_millis,
                    use_built_in_browser, show_preview_text, entry_body_font_size,
                    show_author_name, use_built_in_audio_player
                ) VALUES (1, 1, 1, 1, 10800000, 1, 1, 16, 0, 0);
                """.trimIndent()
            ).use { stmt ->
                stmt.step()
            }

            conn.execSQL("PRAGMA user_version=5;")
        }

        val db = Database(driver, dbFile.absolutePath)

        val conf = db.conf.select()
        assertEquals(false, conf.showTagsTab)

        db.conf.update { it.copy(showTagsTab = true) }
        assertEquals(true, db.conf.select().showTagsTab)
    }

    @Test
    fun migrate_v6ToV7_addsShowPodcastsTabColumn() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(TagTable.SCHEMA)
            conn.execSQL(FeedTagTable.SCHEMA)
            conn.execSQL(LINK_SCHEMA_V7)

            val v6Schema = """
                CREATE TABLE conf (
                    backend TEXT,
                    miniflux_url TEXT,
                    miniflux_token TEXT,
                    minifluxIncrementalSyncTimestamp TEXT,
                    show_preview_images INTEGER NOT NULL,
                    crop_preview_images INTEGER NOT NULL,
                    sync_on_startup INTEGER NOT NULL,
                    sync_in_background INTEGER NOT NULL,
                    background_sync_interval_millis INTEGER NOT NULL,
                    use_built_in_browser INTEGER NOT NULL,
                    show_preview_text INTEGER NOT NULL,
                    entry_body_font_size INTEGER NOT NULL,
                    show_author_name INTEGER NOT NULL DEFAULT 0,
                    use_built_in_audio_player INTEGER NOT NULL DEFAULT 0,
                    show_tags_tab INTEGER NOT NULL DEFAULT 0
                ) STRICT;
            """.trimIndent()
            conn.execSQL(v6Schema)

            conn.prepare(
                """
                INSERT INTO conf (
                    show_preview_images, crop_preview_images, sync_on_startup,
                    sync_in_background, background_sync_interval_millis,
                    use_built_in_browser, show_preview_text, entry_body_font_size,
                    show_author_name, use_built_in_audio_player, show_tags_tab
                ) VALUES (1, 1, 1, 1, 10800000, 1, 1, 16, 0, 0, 0);
                """.trimIndent()
            ).use { stmt ->
                stmt.step()
            }

            conn.execSQL("PRAGMA user_version=6;")
        }

        val db = Database(driver, dbFile.absolutePath)

        val conf = db.conf.select()
        assertEquals(false, conf.showPodcastsTab)

        db.conf.update { it.copy(showPodcastsTab = true) }
        assertEquals(true, db.conf.select().showPodcastsTab)
    }

    @Test
    fun migrate_v7ToV8_addsExtPlayedColumns() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            conn.prepare(
                """
                INSERT INTO link (
                    feed_id, href, rel, type, hreflang, title, length,
                    ext_enclosure_download_progress, ext_cache_uri
                ) VALUES (
                    'f1', 'https://example.com/a.mp3', 'Enclosure', 'audio/mpeg',
                    null, null, null, 1.0, '/cache/a.mp3'
                );
                """.trimIndent()
            ).use { stmt ->
                stmt.step()
            }

            conn.execSQL("PRAGMA user_version=7;")
        }

        val db = Database(driver, dbFile.absolutePath)

        // The row survives the migration with its download state intact, and
        // the new played defaults to 0 so existing enclosures don't suddenly
        // look played. The repo-side helper should also accept the
        // corresponding update so the badge can flip once the user listens.
        val link = db.link.selectByFeedId("f1").single()
        assertEquals(1.0, link.extEnclosureDownloadProgress)
        assertEquals(false, link.extPlayed)
        assertEquals(null, link.extPlayedAt)

        db.link.updatePlayedAndPlayedAt(link.id!!, played = true, playedAt = null)
        val played = db.link.selectByFeedId("f1").single()
        assertEquals(true, played.extPlayed)
        assertEquals(null, played.extPlayedAt)
    }

    @Test
    fun migrate_v8ToV9_addsExtOgLogColumn() {
        val driver = BundledSQLiteDriver()
        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL(FeedTable.SCHEMA)
            conn.execSQL(ENTRY_SCHEMA_V8)
            conn.execSQL(LINK_SCHEMA_V7)

            conn.execSQL("PRAGMA user_version=8;")
        }

        val db = Database(driver, dbFile.absolutePath)

        // No fetcher has touched this entry yet, so the column defaults to
        // an empty array of { timestamp, message } objects.
        val inserted = db.entry.insertOrReplace()
        val reloaded = db.entry.selectById(inserted.id)!!
        assertEquals("[]", reloaded.extOpenGraphImageLog)

        db.entry.updateOgLog(
            extOgLog = """[{"timestamp":"2026-08-28T10:00:00Z","message":"hello"}]""",
            id = inserted.id,
        )
        val updated = db.entry.selectById(inserted.id)!!
        assertEquals(
            """[{"timestamp":"2026-08-28T10:00:00Z","message":"hello"}]""",
            updated.extOpenGraphImageLog,
        )
    }
}
