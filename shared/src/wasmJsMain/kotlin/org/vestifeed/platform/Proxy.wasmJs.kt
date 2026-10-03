package org.vestifeed.platform

import io.ktor.http.encodeURLParameter

/**
 * Routes cross-origin fetches through the same-origin relay served next to the
 * app. The absolute origin is used because Ktor needs an absolute URL.
 */
actual fun proxiedUrl(url: String): String = origin() + "/proxy?url=" + url.encodeURLParameter()

private fun origin(): String = js("self.location.origin")
