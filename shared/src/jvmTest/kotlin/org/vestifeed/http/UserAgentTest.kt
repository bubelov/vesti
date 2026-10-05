package org.vestifeed.http

import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hosts may not send Ktor's engine default (`ktor-client`): the passed
 * User-Agent must reach the wire so a server can tell Android, desktop and the
 * browser apart.
 */
class UserAgentTest {

    @Test
    fun fetchClientSendsTheVestiUserAgent() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())

            vestiFetchHttpClient("Vesti/0.4.3 (Test)").get(server.url("/article").toString())

            assertEquals("Vesti/0.4.3 (Test)", server.takeRequest().headers["User-Agent"])
        }
    }

    @Test
    fun minifluxClientSendsTheVestiUserAgent() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())

            vestiHttpClient(token = "token", userAgent = "Vesti/0.4.3 (Test)")
                .get(server.url("/v1/entries").toString())

            assertEquals("Vesti/0.4.3 (Test)", server.takeRequest().headers["User-Agent"])
        }
    }

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            block(server)
        } finally {
            server.close()
        }
    }
}
