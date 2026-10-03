package org.vestifeed.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import okio.IOException
import org.vestifeed.auth.AuthEvents
import org.vestifeed.backend.MinifluxUnauthenticatedException
import org.vestifeed.json.parseJsonObject
import org.vestifeed.json.stringOrNull

/**
 * Builds the shared Ktor client, the multiplatform replacement for the OkHttp
 * builder plus its interceptors.
 *
 * Two OkHttp behaviours are reproduced:
 * - the `X-Auth-Token` header is attached to every request via [DefaultRequest]
 *   (the old `tokenAuthInterceptor`);
 * - non-2xx responses run through a response validator (the old
 *   `errorInterceptor`): a 401 reports [AuthEvents.reportInvalidated] and throws
 *   [MinifluxUnauthenticatedException]; any other non-2xx reads the body and
 *   throws an [IOException] carrying the server's `error_message` when present.
 *
 * [expectSuccess] is disabled so the validator - not Ktor's default - decides
 * what counts as an error, and callers can still inspect status codes they care
 * about (for example the `201` from feed creation).
 */
@Suppress("UNUSED_PARAMETER")
fun vestiHttpClient(
    token: String? = null,
    debug: Boolean = false,
): HttpClient = HttpClient {
    expectSuccess = false

    if (token != null) {
        install(DefaultRequest) {
            headers.append("X-Auth-Token", token)
        }
    }

    HttpResponseValidator {
        validateResponse { response ->
            if (response.status.isSuccess()) {
                return@validateResponse
            }

            val url = response.request.url.toString()

            if (response.status.value == 401) {
                AuthEvents.reportInvalidated()
                throw MinifluxUnauthenticatedException(url)
            }

            val fallback = "Endpoint $url failed with response code ${response.status.value}"
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            val message = runCatching {
                parseJsonObject(body).stringOrNull("error_message") ?: fallback
            }.getOrDefault(fallback)

            throw IOException(message)
        }
    }
}
