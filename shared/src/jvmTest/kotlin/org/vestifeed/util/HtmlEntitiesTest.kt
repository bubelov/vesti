package org.vestifeed.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlEntitiesTest {

    @Test
    fun decodesCommonNamedEntities() {
        assertEquals("Plato’s God", "Plato&rsquo;s God".decodeHtmlEntities())
        assertEquals("a — b", "a &mdash; b".decodeHtmlEntities())
        assertEquals("“quoted”", "&ldquo;quoted&rdquo;".decodeHtmlEntities())
        assertEquals("wait…", "wait&hellip;".decodeHtmlEntities())
        assertEquals("AT&T", "AT&amp;T".decodeHtmlEntities())
    }

    @Test
    fun decodesNumericReferences() {
        assertEquals("it’s", "it&#8217;s".decodeHtmlEntities())
        assertEquals("it’s", "it&#x2019;s".decodeHtmlEntities())
        assertEquals("A", "&#65;".decodeHtmlEntities())
        assertEquals("A", "&#x41;".decodeHtmlEntities())
    }

    @Test
    fun decodesAstralCodePointAsSurrogatePair() {
        assertEquals("\uD83D\uDE00", "&#128512;".decodeHtmlEntities())
    }

    @Test
    fun leavesUnknownAndBareAmpersandsAlone() {
        assertEquals("AT&T", "AT&T".decodeHtmlEntities())
        assertEquals("&notreal;", "&notreal;".decodeHtmlEntities())
        assertEquals("Tom & Jerry", "Tom & Jerry".decodeHtmlEntities())
    }

    @Test
    fun ignoresAmpersandsWithoutASemicolon() {
        assertEquals("AT&amp and more", "AT&amp and more".decodeHtmlEntities())
    }

    @Test
    fun earlyReturnWhenNoAmpersand() {
        assertEquals("plain text", "plain text".decodeHtmlEntities())
    }
}
