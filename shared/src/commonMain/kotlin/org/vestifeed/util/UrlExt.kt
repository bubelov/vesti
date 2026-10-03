package org.vestifeed.util

import io.ktor.http.Url

/** Parses [this] into an absolute HTTP(S) [Url], throwing when it is not one. */
fun String.toUrl(): Url = toUrlOrNull() ?: throw IllegalArgumentException("Not an HTTP(S) URL: $this")

/**
 * Parses [this] into an absolute HTTP(S) [Url], or null when it is not one.
 *
 * Ktor's [Url] parser accepts a relative string and fills in a default host, so
 * the scheme is checked up front; this matches the old `toHttpUrlOrNull`, which
 * also rejected anything that was not an absolute HTTP(S) URL.
 */
fun String.toUrlOrNull(): Url? {
    val schemeEnd = indexOf("://")
    if (schemeEnd <= 0) return null
    when (substring(0, schemeEnd).lowercase()) {
        "http", "https" -> Unit
        else -> return null
    }
    return runCatching { Url(this) }.getOrNull()
}
