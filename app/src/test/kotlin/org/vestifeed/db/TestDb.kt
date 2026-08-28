package org.vestifeed.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

fun db() = Database(BundledSQLiteDriver(), ":memory:")

/**
 * Schema of the `link` table as it looked from v1 through v7 — used by
 * migration tests that boot a DB at an older user_version. Once v8 lands,
 * [org.vestifeed.db.table.LinkTable.SCHEMA] already contains
 * `ext_played` / `ext_played_at`, so any test that wants to exercise the
 * v7→v8 ALTER has to install this older schema explicitly.
 */
const val LINK_SCHEMA_V7 = """
    CREATE TABLE link (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        href TEXT NOT NULL,
        rel TEXT,
        type TEXT,
        hreflang TEXT,
        title TEXT,
        length TEXT,
        feed_id TEXT REFERENCES feed(id),
        entry_id TEXT REFERENCES entry(id),
        ext_enclosure_download_progress REAL,
        ext_cache_uri TEXT,
        UNIQUE(feed_id, href, rel),
        UNIQUE(entry_id, href, rel),
        CHECK ((feed_id IS NULL) <> (entry_id IS NULL))
    ) STRICT;
"""

/**
 * Schema of the `entry` table as it looked from v1 through v8 — used by
 * migration tests that boot a DB at an older user_version. Once v9 lands,
 * [org.vestifeed.db.table.EntryTable.SCHEMA] already contains
 * `ext_og_log`, so any test that wants to exercise the v8→v9 ALTER has
 * to install this older schema explicitly.
 */
const val ENTRY_SCHEMA_V8 = """
    CREATE TABLE entry (
        content_type TEXT,
        content_src TEXT,
        content_text TEXT,
        summary TEXT,
        id TEXT PRIMARY KEY NOT NULL,
        feed_id TEXT NOT NULL,
        title TEXT NOT NULL,
        published TEXT NOT NULL,
        updated TEXT NOT NULL,
        author_name TEXT NOT NULL,
        ext_read INTEGER NOT NULL,
        ext_read_synced INTEGER NOT NULL,
        ext_bookmarked INTEGER NOT NULL,
        ext_bookmarked_synced INTEGER NOT NULL,
        ext_comments_url TEXT NOT NULL,
        ext_og_image_checked INTEGER NOT NULL,
        ext_og_image_url TEXT NOT NULL,
        ext_og_image_width INTEGER NOT NULL,
        ext_og_image_height INTEGER NOT NULL,
        ext_og_image_fetched_at TEXT NOT NULL DEFAULT ''
    ) STRICT;
"""