package org.vestifeed.http

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/**
 * OkHttp's default connect and read/write-inactivity budgets, restored for
 * Ktor.
 *
 * Ktor's own default is infinite (`INFINITE_TIMEOUT_MS`), so a host that
 * accepts the connection and then goes silent suspends the caller forever.
 * The OpenGraph fetcher is the worst hit: it walks entries sequentially, so
 * one stalled article page freezes preview images for every later entry, and
 * the stalled request is never retried.
 */
internal const val VESTI_CONNECT_TIMEOUT_MILLIS: Long = 10_000

internal const val VESTI_SOCKET_TIMEOUT_MILLIS: Long = 10_000

/**
 * Installs [HttpTimeout] with OkHttp-compatible connect and socket budgets.
 *
 * [requestTimeoutMillis] caps the whole exchange; the default (`null`) leaves
 * it unbounded, matching OkHttp, which only bounds connect and reads.
 */
internal fun HttpClientConfig<*>.vestiTimeouts(requestTimeoutMillis: Long? = null) {
    install(HttpTimeout) {
        connectTimeoutMillis = VESTI_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = VESTI_SOCKET_TIMEOUT_MILLIS
        this.requestTimeoutMillis = requestTimeoutMillis
    }
}
