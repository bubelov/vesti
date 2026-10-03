package org.vestifeed.backend

import okio.IOException

class MinifluxUnauthenticatedException(
    val url: String,
) : IOException("Miniflux token rejected (HTTP 401) by $url")
