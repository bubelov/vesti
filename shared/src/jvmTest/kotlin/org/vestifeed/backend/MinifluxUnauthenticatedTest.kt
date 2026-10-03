package org.vestifeed.backend

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.vestifeed.auth.AuthEvents
import org.vestifeed.db.Database
import org.vestifeed.db.testDb
import org.vestifeed.util.toUrl

class MinifluxUnauthenticatedTest {

    private lateinit var server: MockWebServer
    private lateinit var db: Database

    @Before
    fun before() {
        server = MockWebServer().apply { start() }
        db = testDb()
        AuthEvents.reset()
    }

    @After
    fun after() {
        server.close()
        AuthEvents.reset()
    }

    @Test
    fun unauthorizedResponseThrowsAndSignalsEvent() {
        server.enqueue(MockResponse.Builder().code(401).build())

        val api = Miniflux(
            client = minifluxHttpClient(token = "expired"),
            baseUrl = server.url("/v1/").toString().toUrl(),
            db = db,
        )

        val countBefore = AuthEvents.invalidationCount.value
        assertThrows(MinifluxUnauthenticatedException::class.java) {
            runBlocking { api.getFeeds() }
        }
        assertEquals(countBefore + 1, AuthEvents.invalidationCount.value)
    }

    @Test
    fun otherErrorCodesDoNotSignalAuthEvent() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val api = Miniflux(
            client = minifluxHttpClient(token = "expired"),
            baseUrl = server.url("/v1/").toString().toUrl(),
            db = db,
        )

        val countBefore = AuthEvents.invalidationCount.value
        assertThrows(java.io.IOException::class.java) {
            runBlocking { api.getFeeds() }
        }
        assertEquals(countBefore, AuthEvents.invalidationCount.value)
    }

    @Test
    fun subscriberReceivesInvalidationSignal() {
        server.enqueue(MockResponse.Builder().code(401).build())

        val api = Miniflux(
            client = minifluxHttpClient(token = "expired"),
            baseUrl = server.url("/v1/").toString().toUrl(),
            db = db,
        )

        runBlocking {
            val collector = async {
                AuthEvents.invalidationCount
                    .filter { it > 0 }
                    .first()
            }

            assertThrows(MinifluxUnauthenticatedException::class.java) {
                runBlocking { api.getFeeds() }
            }

            withTimeout(2_000) { collector.await() }
        }
    }

    @Test
    fun nonAuthErrorProducesNoSubscriberSignal() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val api = Miniflux(
            client = minifluxHttpClient(token = "expired"),
            baseUrl = server.url("/v1/").toString().toUrl(),
            db = db,
        )

        val received = runBlocking {
            val collector = async {
                withTimeoutOrNull(200) {
                    AuthEvents.invalidationCount
                        .filter { it > 0 }
                        .first()
                }
            }

            assertThrows(java.io.IOException::class.java) {
                runBlocking { api.getFeeds() }
            }

            collector.await()
        }

        assertNull("Expected no AuthEvents emission for non-401 response", received)
    }

    @Test
    fun resetClearsCounter() {
        AuthEvents.reportInvalidated()
        AuthEvents.reportInvalidated()
        assertEquals(2L, AuthEvents.invalidationCount.value)

        AuthEvents.reset()

        assertEquals(0L, AuthEvents.invalidationCount.value)
    }
}
