package org.vestifeed.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

/**
 * Installs Coil's singleton loader with a Ktor network fetcher, so entry
 * preview images load on every target (the Android/JVM defaults already work,
 * but wasm needs the fetch to be explicit).
 *
 * Image URLs are passed through the same-origin feed proxy where CORS would
 * otherwise block them (see `org.vestifeed.platform.proxiedUrl`).
 */
@Composable
fun VestiImageLoader() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
}
