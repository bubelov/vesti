package org.vestifeed.curated

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A curated collection of feeds — either a topic ("Programming") or a country's
 * news sources ("Poland"). Collections come from the embedded, filtered
 * Awesome RSS Feeds catalog (see `scripts/generate_curated_feeds.py`).
 */
@Serializable
data class CuratedCollection(
    val id: String,
    val name: String,
    val group: CuratedGroup,
    val feeds: List<CuratedFeed>,
)

/** How [CuratedCollection]s are grouped in the browse UI. */
@Serializable
enum class CuratedGroup {
    /** Topic picks: Programming, Science, Food, … */
    Recommended,

    /** Local news feeds grouped by country. */
    Countries,
}

/** A single feed suggestion: the display [title], the feed [url] and a blurb. */
@Serializable
data class CuratedFeed(
    val title: String,
    val url: String,
    val description: String = "",
)

/** The embedded Awesome RSS Feeds catalog, with its attribution [source]. */
@Serializable
data class CuratedCatalog(
    val source: String,
    val license: String = "",
    val collections: List<CuratedCollection>,
) {
    fun collection(id: String): CuratedCollection? = collections.firstOrNull { it.id == id }

    fun inGroup(group: CuratedGroup): List<CuratedCollection> =
        collections.filter { it.group == group }
}

private val curatedJson = Json { ignoreUnknownKeys = true }

/** Parses the JSON shipped as `files/curated_feeds.json`. */
fun parseCuratedCatalog(json: String): CuratedCatalog =
    curatedJson.decodeFromString<CuratedCatalog>(json)

/** The bare host of [url] for display, e.g. `example.com`. */
fun feedHost(url: String): String {
    val withoutScheme = url.substringAfter("://", url)
    val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return host.removePrefix("www.")
}
