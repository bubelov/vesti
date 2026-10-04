package org.vestifeed.db.table

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vestifeed.db.Database
import org.vestifeed.db.testDb

class ConfTest {

    private lateinit var db: Database

    @Before
    fun before() {
        db = testDb()
    }

    @Test
    fun confSchema_constants() {
        assertEquals("embedded", ConfTable.Backend.Embedded.name.lowercase())
        assertEquals("miniflux", ConfTable.Backend.Miniflux.name.lowercase())
    }

    @Test
    fun confSchema_createTableStatement() {
        val statement = ConfTable.SCHEMA
        assertTrue(statement.contains("CREATE TABLE conf"))
        assertTrue(statement.contains("backend TEXT"))
        assertTrue(statement.contains("miniflux_url TEXT"))
        assertTrue(statement.contains("background_sync_interval_millis INTEGER NOT NULL"))
        assertTrue(statement.contains("entry_body_font_size INTEGER NOT NULL"))
        assertTrue(statement.contains("use_built_in_audio_player INTEGER NOT NULL"))
        assertTrue(statement.contains("show_tags_tab INTEGER NOT NULL"))
        assertTrue(statement.contains("show_podcasts_tab INTEGER NOT NULL"))
    }

    @Test
    fun confDefaults_values() {
        val defaultConf = ConfTable.defaultConf()
        assertEquals(null, defaultConf.backend)
        assertEquals(null, defaultConf.minifluxUrl)
        assertEquals(null, defaultConf.minifluxToken)
        assertEquals(null, defaultConf.minifluxIncrementalSyncTimestamp)
        assertTrue(defaultConf.showPreviewImages)
        assertTrue(defaultConf.cropPreviewImages)
        assertTrue(defaultConf.syncOnStartup)
        assertTrue(defaultConf.syncInBackground)
        assertEquals(10800000L, defaultConf.backgroundSyncIntervalMillis)
        assertTrue(defaultConf.useBuiltInBrowser)
        assertTrue(defaultConf.showPreviewText)
        assertEquals(16, defaultConf.entryBodyFontSize)
        assertFalse(defaultConf.useBuiltInAudioPlayer)
        assertFalse(defaultConf.showTagsTab)
        assertFalse(defaultConf.showPodcastsTab)
        assertEquals(ConfTable.EntriesView.Cards, defaultConf.entriesView)
    }

    @Test
    fun confQueries_select_emptyReturnsDefault() = runBlocking<Unit> {
        val result = db.conf.select()
        val defaultConf = ConfTable.defaultConf()
        assertEquals(defaultConf.backend, result.backend)
        assertEquals(defaultConf.backgroundSyncIntervalMillis, result.backgroundSyncIntervalMillis)
    }

    @Test
    fun confQueries_insertAndSelect() = runBlocking<Unit> {
        val conf = createConf()
        db.conf.insert(conf)

        val result = db.conf.select()
        assertEquals(conf.backend, result.backend)
        assertEquals(conf.minifluxUrl, result.minifluxUrl)
        assertEquals(conf.minifluxToken, result.minifluxToken)
        assertEquals(conf.minifluxIncrementalSyncTimestamp, result.minifluxIncrementalSyncTimestamp)
        assertEquals(conf.showPreviewImages, result.showPreviewImages)
        assertEquals(conf.cropPreviewImages, result.cropPreviewImages)
        assertEquals(conf.syncOnStartup, result.syncOnStartup)
        assertEquals(conf.syncInBackground, result.syncInBackground)
        assertEquals(conf.backgroundSyncIntervalMillis, result.backgroundSyncIntervalMillis)
        assertEquals(conf.useBuiltInBrowser, result.useBuiltInBrowser)
        assertEquals(conf.showPreviewText, result.showPreviewText)
        assertEquals(conf.entryBodyFontSize, result.entryBodyFontSize)
        assertEquals(conf.useBuiltInAudioPlayer, result.useBuiltInAudioPlayer)
        assertEquals(conf.showTagsTab, result.showTagsTab)
        assertEquals(conf.showPodcastsTab, result.showPodcastsTab)
        assertEquals(conf.entriesView, result.entriesView)
    }

    @Test
    fun confQueries_insert_replacesExisting() = runBlocking<Unit> {
        val conf1 = createConf(backend = ConfTable.Backend.Embedded)
        db.conf.insert(conf1)

        db.conf.delete()

        val conf2 = createConf(backend = ConfTable.Backend.Miniflux)
        db.conf.insert(conf2)

        val result = db.conf.select()
        assertEquals(ConfTable.Backend.Miniflux, result.backend)
    }

    @Test
    fun confQueries_update() = runBlocking<Unit> {
        val initialConf = createConf(
            backend = ConfTable.Backend.Embedded,
            showPreviewImages = false,
        )
        db.conf.insert(initialConf)

        db.conf.update { it.copy(backend = ConfTable.Backend.Miniflux, showPreviewImages = true) }

        val result = db.conf.select()
        assertEquals(ConfTable.Backend.Miniflux, result.backend)
        assertTrue(result.showPreviewImages)
    }

    @Test
    fun confQueries_delete() = runBlocking<Unit> {
        val conf = createConf()
        db.conf.insert(conf)
        assertEquals(conf.backend, db.conf.select().backend)

        db.conf.delete()
        val defaultConf = ConfTable.defaultConf()
        assertEquals(defaultConf.backend, db.conf.select().backend)
    }

    @Test
    fun confQueries_updatePartialFields() = runBlocking<Unit> {
        db.conf.insert(createConf(showPreviewImages = false))
        db.conf.update { it.copy(syncOnStartup = false) }
        val result = db.conf.select()
        assertEquals(false, result.showPreviewImages)
        assertFalse(result.syncOnStartup)
    }

    @Test
    fun confQueries_showTagsTab_roundTrips() = runBlocking<Unit> {
        db.conf.insert(createConf(showTagsTab = false))
        assertFalse(db.conf.select().showTagsTab)

        db.conf.update { it.copy(showTagsTab = true) }
        assertTrue(db.conf.select().showTagsTab)

        db.conf.update { it.copy(showTagsTab = false) }
        assertFalse(db.conf.select().showTagsTab)
    }

    @Test
    fun confQueries_showPodcastsTab_roundTrips() = runBlocking<Unit> {
        db.conf.insert(createConf(showPodcastsTab = false))
        assertFalse(db.conf.select().showPodcastsTab)

        db.conf.update { it.copy(showPodcastsTab = true) }
        assertTrue(db.conf.select().showPodcastsTab)

        db.conf.update { it.copy(showPodcastsTab = false) }
        assertFalse(db.conf.select().showPodcastsTab)
    }

    private fun createConf(
        backend: ConfTable.Backend = ConfTable.Backend.Embedded,
        minifluxUrl: String = "https://miniflux.example.com",
        minifluxToken: String = "miniflux-token",
        minifluxIncrementalSyncTimestamp: String? = "2024-01-01T00:00:00Z",
        showPreviewImages: Boolean = true,
        cropPreviewImages: Boolean = false,
        syncOnStartup: Boolean = false,
        syncInBackground: Boolean = false,
        backgroundSyncIntervalMillis: Long = 3600000L,
        useBuiltInBrowser: Boolean = false,
        showPreviewText: Boolean = false,
        entryBodyFontSize: Int = 20,
        showAuthorName: Boolean = false,
        useBuiltInAudioPlayer: Boolean = false,
        showTagsTab: Boolean = false,
        showPodcastsTab: Boolean = false,
        entriesView: ConfTable.EntriesView = ConfTable.EntriesView.List,
    ) = ConfTable.Conf(
        backend = backend,
        minifluxUrl = minifluxUrl,
        minifluxToken = minifluxToken,
        minifluxIncrementalSyncTimestamp = minifluxIncrementalSyncTimestamp,
        showPreviewImages = showPreviewImages,
        cropPreviewImages = cropPreviewImages,
        syncOnStartup = syncOnStartup,
        syncInBackground = syncInBackground,
        backgroundSyncIntervalMillis = backgroundSyncIntervalMillis,
        useBuiltInBrowser = useBuiltInBrowser,
        showPreviewText = showPreviewText,
        entryBodyFontSize = entryBodyFontSize,
        showAuthorName = showAuthorName,
        useBuiltInAudioPlayer = useBuiltInAudioPlayer,
        showTagsTab = showTagsTab,
        showPodcastsTab = showPodcastsTab,
        entriesView = entriesView,
    )
}
