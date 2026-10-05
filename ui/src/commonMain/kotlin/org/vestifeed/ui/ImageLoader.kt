package org.vestifeed.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import org.vestifeed.http.vestiFetchHttpClient

/**
 * Installs Coil's singleton loader with a Ktor network fetcher, so entry
 * preview images load on every target (the Android/JVM defaults already work,
 * but wasm needs the fetch to be explicit).
 *
 * Image URLs are passed through the same-origin feed proxy where CORS would
 * otherwise block them (see `org.vestifeed.platform.proxiedUrl`). The fetcher
 * carries the Vesti [userAgent] so image servers see the same client string as
 * every other request.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun VestiImageLoader(userAgent: String) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(vestiFetchHttpClient(userAgent))) }
            .build()
    }
}
