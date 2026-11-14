package org.vestifeed.sync

import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.db.table.ConfTable

/** The scheduling rules behind the "Fetch news in background" setting. */
class BackgroundSyncPolicyTest {

    private val signedIn = ConfTable.defaultConf().copy(
        backend = ConfTable.Backend.Miniflux,
        minifluxUrl = "https://reader.example.com",
        minifluxToken = "token",
    )

    @Test
    fun clampsAnIntervalBelowThePlatformFloor() {
        assertEquals(
            BackgroundSyncPolicy.MIN_INTERVAL_MILLIS,
            BackgroundSyncPolicy.intervalMillis(60_000L),
        )
    }

    @Test
    fun keepsAnIntervalAboveThePlatformFloor() {
        assertEquals(3.hours.inWholeMilliseconds, BackgroundSyncPolicy.intervalMillis(3.hours.inWholeMilliseconds))
    }

    @Test
    fun clampsAnIntervalThatIsAlreadyTheFloor() {
        val floor = BackgroundSyncPolicy.MIN_INTERVAL_MILLIS
        assertEquals(floor, BackgroundSyncPolicy.intervalMillis(floor))
    }

    @Test
    fun enabledWhenTheSettingIsOnAndABackendIsConfigured() {
        assertTrue(BackgroundSyncPolicy.isEnabled(signedIn))
    }

    @Test
    fun disabledWhenTheSettingIsOff() {
        assertFalse(BackgroundSyncPolicy.isEnabled(signedIn.copy(syncInBackground = false)))
    }

    @Test
    fun disabledWhenNoBackendIsConfigured() {
        assertFalse(
            BackgroundSyncPolicy.isEnabled(
                signedIn.copy(backend = null, minifluxUrl = null, minifluxToken = null),
            ),
        )
    }

    @Test
    fun embeddedSessionsScheduleTooBecauseTheyFetchTheirOwnFeeds() {
        assertTrue(
            BackgroundSyncPolicy.isEnabled(
                signedIn.copy(
                    backend = ConfTable.Backend.Embedded,
                    minifluxUrl = null,
                    minifluxToken = null,
                ),
            ),
        )
    }
}
