package org.vestifeed.backend

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vestifeed.db.db
import org.vestifeed.db.table.ConfTable

class BackendTest {

    @Test
    fun embeddedBackend() = runBlocking {
        val db = db()

        db.conf.update { it.copy(backend = ConfTable.Backend.Embedded) }

        var attempts = 0L

        var api: Backend? = null
        while (attempts < 20) {
            try {
                api = backend(db)
                break
            } catch (_: Throwable) {
                attempts += 1
                delay(10 * attempts)
            }
        }

        assertTrue("expected Embedded, got $api", api is Embedded)
        assertEquals(0, api!!.getFeeds().size)
    }
}
