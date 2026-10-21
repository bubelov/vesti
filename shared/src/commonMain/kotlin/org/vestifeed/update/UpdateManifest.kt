package org.vestifeed.update

import kotlinx.serialization.Serializable
import org.vestifeed.json.vestiJson

/**
 * The build published at an update channel's manifest URL, e.g.
 * `https://vestifeed.org/releases/vesti-nightly.json`. `devtools publish`
 * uploads it next to the APK, so an installed build can discover a newer one
 * without downloading it first.
 */
@Serializable
data class UpdateManifest(
    /** Monotonic within a channel: a higher value is a newer build. */
    val versionCode: Int = 0,
    val versionName: String = "",
    /**
     * Build time in epoch milliseconds. The tie-breaker within one version
     * code, which a nightly keeps at the release's value.
     */
    val buildTime: Long = 0L,
    /** Absolute URL of the APK to download. */
    val apk: String = "",
)

/** Parses a channel manifest, or null when [text] is not one. */
fun parseUpdateManifest(text: String): UpdateManifest? =
    runCatching { vestiJson.decodeFromString<UpdateManifest>(text) }.getOrNull()
