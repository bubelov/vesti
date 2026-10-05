package org.vestifeed.backend

import io.ktor.client.HttpClient
import org.vestifeed.http.vestiHttpClient

/**
 * The Miniflux HTTP client: a Ktor client carrying the API token and the
 * 401-aware error handling. The [debug] flag is accepted for parity with the
 * old OkHttp factory but is currently unused.
 */
fun minifluxHttpClient(
    token: String,
    debug: Boolean = false,
    userAgent: String? = null,
): HttpClient = vestiHttpClient(token = token, debug = debug, userAgent = userAgent)
