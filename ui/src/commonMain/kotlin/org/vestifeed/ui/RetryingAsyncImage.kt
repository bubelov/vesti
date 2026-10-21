package org.vestifeed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay

/**
 * Delays before each retry of a failed image, in order. The first ones cover a
 * network that is still coming up when the app launches (the radio waking up,
 * Android reporting no validated connection yet); the later ones give a flaky
 * host time to recover.
 */
private val RetryDelaysMillis = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

/**
 * [AsyncImage] with bounded retries.
 *
 * Coil keeps a failed load in its error state for as long as the composition
 * lives: a preview whose first request fails stays blank until its row is
 * recreated — scrolled out and back, or after a relaunch. A launch-time sync
 * makes that common, because the first screenful of images is requested while
 * the network is still settling. Retrying with a backoff lets such a transient
 * failure heal on its own; the image is recreated for every attempt (through
 * [key]) because Coil otherwise ignores a second request for the same model.
 */
@Composable
fun RetryingAsyncImage(
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
) {
    var attempt by remember(model) { mutableIntStateOf(0) }
    var failed by remember(model) { mutableStateOf(false) }

    LaunchedEffect(failed, attempt) {
        if (!failed || attempt >= RetryDelaysMillis.size) return@LaunchedEffect
        delay(RetryDelaysMillis[attempt])
        failed = false
        attempt++
    }

    key(attempt) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onSuccess = onSuccess,
            onError = { failed = true },
            modifier = modifier,
        )
    }
}
