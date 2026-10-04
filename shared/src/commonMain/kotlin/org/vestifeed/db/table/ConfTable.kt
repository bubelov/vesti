package org.vestifeed.db.table

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import org.vestifeed.db.bindTextOrNull
import org.vestifeed.db.getTextOrNull
import org.vestifeed.db.withTransaction
import kotlin.use

class ConfTable(private val conn: SQLiteConnection) {
    companion object {
        const val SCHEMA = """
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
                show_tags_tab INTEGER NOT NULL DEFAULT 0,
                show_podcasts_tab INTEGER NOT NULL DEFAULT 0,
                entries_view TEXT NOT NULL DEFAULT 'cards'
            ) STRICT;
        """

        fun defaultConf(): Conf = Conf(
            backend = null,
            minifluxUrl = null,
            minifluxToken = null,
            minifluxIncrementalSyncTimestamp = null,
            showPreviewImages = true,
            cropPreviewImages = true,
            syncOnStartup = true,
            syncInBackground = true,
            backgroundSyncIntervalMillis = 10800000L,
            useBuiltInBrowser = true,
            showPreviewText = true,
            entryBodyFontSize = 16,
            showAuthorName = false,
            useBuiltInAudioPlayer = true,
            showTagsTab = false,
            showPodcastsTab = true,
            entriesView = EntriesView.Cards,
        )
    }

    enum class Backend {
        Miniflux,
        Embedded,
    }

    /** How the entries screens lay their rows out. */
    enum class EntriesView {
        /** One column of compact rows with the preview image on the left. */
        List,

        /** A grid of Material cards with the preview image on top. */
        Cards,
    }

    data class Conf(
        // miniflux or embedded
        val backend: Backend?,
        // https://miniflux.app/docs/api.html
        val minifluxUrl: String?,
        // Per-application API keys (since version 2.0.21) -> preferred method
        val minifluxToken: String?,
        // if null, should fetch read + starred and set to
        // current timestamp to fetch future updates
        val minifluxIncrementalSyncTimestamp: String?,
        // based on HTML OpenGraph tags
        val showPreviewImages: Boolean,
        // fixed height
        val cropPreviewImages: Boolean,
        // default on when it's cheap (Miniflux)
        // default off when it's expensive (embedded)
        val syncOnStartup: Boolean,
        // pre-fetched news speed things up when user comes back
        val syncInBackground: Boolean,
        val backgroundSyncIntervalMillis: Long,
        val useBuiltInBrowser: Boolean,
        val showPreviewText: Boolean,
        // size of the entry body text in sp
        val entryBodyFontSize: Int,
        // off by default; when true the entries adapter shows the author on
        // the secondary line between the feed title and the post date
        val showAuthorName: Boolean,
        // on by default; when true, downloaded audio enclosures are played
        // inside the app instead of being handed off to an external player
        val useBuiltInAudioPlayer: Boolean,
        // off by default; when true, the bottom nav shows a "Tags"
        // tab listing the user's tags and filtering entries by tag on click
        val showTagsTab: Boolean,
        // on by default, but the tab only appears when a feed actually has
        // audio enclosures; when true, the bottom nav shows a "Podcasts" tab
        // listing all audio enclosures on the device, sorted by entry publish
        // date
        val showPodcastsTab: Boolean,
        // how the entries screens lay their rows out
        val entriesView: EntriesView,
    )

    fun SQLiteStatement.toConf(): Conf = Conf(
        backend = getBackendOrNull(0),
        minifluxUrl = getTextOrNull(1),
        minifluxToken = getTextOrNull(2),
        minifluxIncrementalSyncTimestamp = getTextOrNull(3),
        showPreviewImages = getInt(4) == 1,
        cropPreviewImages = getInt(5) == 1,
        syncOnStartup = getInt(6) == 1,
        syncInBackground = getInt(7) == 1,
        backgroundSyncIntervalMillis = getLong(8),
        useBuiltInBrowser = getInt(9) == 1,
        showPreviewText = getInt(10) == 1,
        entryBodyFontSize = getInt(11),
        showAuthorName = getInt(12) == 1,
        useBuiltInAudioPlayer = getInt(13) == 1,
        showTagsTab = getInt(14) == 1,
        showPodcastsTab = getInt(15) == 1,
        entriesView = getEntriesView(16),
    )

    suspend fun insert(conf: Conf) {
        conn.prepare(
            """
            INSERT OR REPLACE INTO conf (backend, miniflux_url, miniflux_token, minifluxIncrementalSyncTimestamp, show_preview_images, crop_preview_images, sync_on_startup, sync_in_background, background_sync_interval_millis, use_built_in_browser, show_preview_text, entry_body_font_size, show_author_name, use_built_in_audio_player, show_tags_tab, show_podcasts_tab, entries_view)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """
        ).use { stmt ->
            stmt.bindTextOrNull(1, conf.backend?.name?.lowercase())
            stmt.bindTextOrNull(2, conf.minifluxUrl)
            stmt.bindTextOrNull(3, conf.minifluxToken)
            stmt.bindTextOrNull(4, conf.minifluxIncrementalSyncTimestamp)
            stmt.bindInt(5, if (conf.showPreviewImages) 1 else 0)
            stmt.bindInt(6, if (conf.cropPreviewImages) 1 else 0)
            stmt.bindInt(7, if (conf.syncOnStartup) 1 else 0)
            stmt.bindInt(8, if (conf.syncInBackground) 1 else 0)
            stmt.bindLong(9, conf.backgroundSyncIntervalMillis)
            stmt.bindInt(10, if (conf.useBuiltInBrowser) 1 else 0)
            stmt.bindInt(11, if (conf.showPreviewText) 1 else 0)
            stmt.bindInt(12, conf.entryBodyFontSize)
            stmt.bindInt(13, if (conf.showAuthorName) 1 else 0)
            stmt.bindInt(14, if (conf.useBuiltInAudioPlayer) 1 else 0)
            stmt.bindInt(15, if (conf.showTagsTab) 1 else 0)
            stmt.bindInt(16, if (conf.showPodcastsTab) 1 else 0)
            stmt.bindText(17, conf.entriesView.name.lowercase())
            stmt.step()
        }
    }

    suspend fun select(): Conf {
        conn.prepare(
            """
            SELECT backend, miniflux_url, miniflux_token, minifluxIncrementalSyncTimestamp, show_preview_images, crop_preview_images, sync_on_startup, sync_in_background, background_sync_interval_millis, use_built_in_browser, show_preview_text, entry_body_font_size, show_author_name, use_built_in_audio_player, show_tags_tab, show_podcasts_tab, entries_view
            FROM conf
            """
        ).use { stmt ->
            return if (stmt.step()) {
                stmt.toConf()
            } else {
                defaultConf()
            }
        }
    }

    suspend fun update(newConf: (Conf) -> Conf) {
        withTransaction(conn) {
            val updatedConf = newConf(select())
            delete()
            insert(updatedConf)
        }
    }

    suspend fun delete() {
        conn.execSQL("DELETE FROM conf")
    }

    fun SQLiteStatement.getBackendOrNull(index: Int): Backend? =
        if (isNull(index)) null else Backend.entries.single { it.name.lowercase() == getText(index) }

    fun SQLiteStatement.getEntriesView(index: Int): EntriesView =
        EntriesView.entries.firstOrNull { it.name.lowercase() == getText(index) } ?: EntriesView.Cards
}
