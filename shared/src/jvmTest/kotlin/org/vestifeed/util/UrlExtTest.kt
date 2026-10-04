package org.vestifeed.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlExtTest {

    @Test
    fun withHttpsScheme_prependsSchemeToBareHost() {
        assertEquals("https://bubelov.com", "bubelov.com".withHttpsScheme())
    }

    @Test
    fun withHttpsScheme_prependsSchemeToHostAndPath() {
        assertEquals("https://bubelov.com/feed.xml", "bubelov.com/feed.xml".withHttpsScheme())
    }

    @Test
    fun withHttpsScheme_leavesHttpsUrlUnchanged() {
        assertEquals("https://example.com/feed.xml", "https://example.com/feed.xml".withHttpsScheme())
    }

    @Test
    fun withHttpsScheme_leavesHttpUrlUnchanged() {
        assertEquals("http://example.com/feed.xml", "http://example.com/feed.xml".withHttpsScheme())
    }

    @Test
    fun withHttpsScheme_thenToUrlAcceptsBareHost() {
        val url = "bubelov.com".withHttpsScheme().toUrl()
        assertEquals("https", url.protocol.name)
        assertEquals("bubelov.com", url.host)
    }
}
