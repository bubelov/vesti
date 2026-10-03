package org.vestifeed.platform

/**
 * Rewrites a cross-origin URL so it is fetched through the same-origin relay.
 *
 * On the browser a fetch to an arbitrary feed or image origin is blocked by
 * CORS, so the wasm actual routes it through `app.vestifeed.org/proxy`; the
 * Android and JVM hosts fetch directly.
 */
expect fun proxiedUrl(url: String): String
