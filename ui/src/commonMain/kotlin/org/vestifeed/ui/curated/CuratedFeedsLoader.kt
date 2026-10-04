package org.vestifeed.ui.curated

import org.vestifeed.curated.CuratedCatalog
import org.vestifeed.curated.parseCuratedCatalog
import org.vestifeed.ui.resources.Res

/**
 * Loads the embedded Awesome RSS Feeds catalog. The JSON ships as a Compose
 * resource so the same data is available on Android, the JVM and the browser.
 */
suspend fun loadCuratedCatalog(): CuratedCatalog {
    val json = Res.readBytes("files/curated_feeds.json").decodeToString()
    return parseCuratedCatalog(json)
}
