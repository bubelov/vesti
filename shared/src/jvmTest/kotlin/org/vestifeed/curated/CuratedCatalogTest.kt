package org.vestifeed.curated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CuratedCatalogTest {

    private val json = """
        {
          "source": "https://github.com/plenaryapp/awesome-rss-feeds",
          "license": "CC0-1.0",
          "collections": [
            {
              "id": "recommended-tech",
              "name": "Tech",
              "group": "Recommended",
              "feeds": [
                {"title": "Ars Technica", "url": "https://arstechnica.com/feed", "description": "All stories"}
              ]
            },
            {
              "id": "countries-poland",
              "name": "Poland",
              "group": "Countries",
              "feeds": [
                {"title": "Onet", "url": "https://onet.pl/rss"}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesCollectionsAndGroups() {
        val catalog = parseCuratedCatalog(json)

        assertEquals("https://github.com/plenaryapp/awesome-rss-feeds", catalog.source)
        assertEquals("CC0-1.0", catalog.license)
        assertEquals(2, catalog.collections.size)
        assertEquals(listOf("Tech"), catalog.inGroup(CuratedGroup.Recommended).map { it.name })
        assertEquals(listOf("Poland"), catalog.inGroup(CuratedGroup.Countries).map { it.name })
    }

    @Test
    fun resolvesCollectionByIdAndDefaultsMissingDescription() {
        val catalog = parseCuratedCatalog(json)

        val tech = catalog.collection("recommended-tech")
        assertEquals("Tech", tech?.name)
        assertEquals("All stories", tech?.feeds?.single()?.description)
        assertNull(catalog.collection("missing"))

        // A missing description deserializes to the empty default.
        assertEquals("", catalog.collection("countries-poland")?.feeds?.single()?.description)
    }

    @Test
    fun feedHostStripsSchemePathAndWww() {
        assertEquals("example.com", feedHost("https://example.com/feed.xml"))
        assertEquals("example.com", feedHost("http://www.example.com/rss?a=1"))
        assertEquals("sub.example.com", feedHost("https://sub.example.com"))
        assertEquals("example.com", feedHost("example.com/feed"))
    }
}
