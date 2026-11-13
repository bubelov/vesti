package org.vestifeed.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.vestifeed.db.Database
import org.vestifeed.util.toUrl

/**
 * A Miniflux backend whose sync body is supplied by the test. Everything else
 * is inherited but never called, so no request leaves the process.
 */
internal class FakeBackend(
    db: Database,
    private val onSync: suspend () -> Unit,
) : Miniflux(
    client = HttpClient(CIO),
    baseUrl = "http://localhost/v1/".toUrl(),
    db = db,
) {
    override suspend fun sync(initial: Boolean) = onSync()
}
