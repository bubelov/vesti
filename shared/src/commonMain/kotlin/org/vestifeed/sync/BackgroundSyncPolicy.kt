package org.vestifeed.sync

import org.vestifeed.db.table.ConfTable

/**
 * When a host with a background scheduler should sync on its own.
 *
 * Only Android has one; the desktop window and the browser report
 * `supportsBackgroundSync = false`, so this describes the periodic job that
 * wakes the app, pulls the feeds and lets it sleep again.
 */
object BackgroundSyncPolicy {

    /**
     * WorkManager's floor for periodic work. A shorter configured interval is
     * clamped rather than rejected: the stored value is a preference, not a
     * contract, and a shorter one simply cannot be honoured.
     */
    const val MIN_INTERVAL_MILLIS: Long = 15 * 60 * 1000L

    /** The periodic interval to schedule for a configured [intervalMillis]. */
    fun intervalMillis(intervalMillis: Long): Long =
        intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)

    /**
     * Whether [conf] asks for periodic sync at all: the setting is on and a
     * backend is configured to sync against. A host that has never signed in
     * must not schedule work that can only fail.
     */
    fun isEnabled(conf: ConfTable.Conf): Boolean =
        conf.syncInBackground && conf.backend != null
}
