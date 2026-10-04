package org.vestifeed.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {

    @Test
    fun default_flattensEverythingToASingleLine() {
        assertEquals("One Two", stripHtml("<p>One</p><p>Two</p>"))
    }

    @Test
    fun preserveParagraphs_separatesParagraphsWithBlankLines() {
        assertEquals("One\n\nTwo", stripHtml("<p>One</p><p>Two</p>", preserveParagraphs = true))
    }

    @Test
    fun preserveParagraphs_breakBecomesSingleNewline() {
        assertEquals("One\nTwo", stripHtml("One<br>Two", preserveParagraphs = true))
    }

    @Test
    fun preserveParagraphs_headingsAreSeparated() {
        assertEquals(
            "Title\n\nBody",
            stripHtml("<h2>Title</h2><p>Body</p>", preserveParagraphs = true),
        )
    }

    @Test
    fun preserveParagraphs_listItemsReadAsLines() {
        assertEquals(
            "A\nB",
            stripHtml("<ul><li>A</li><li>B</li></ul>", preserveParagraphs = true),
        )
    }

    @Test
    fun preserveParagraphs_collapsesSourceFormattingWhitespace() {
        assertEquals("One two", stripHtml("<p>One\n    two</p>", preserveParagraphs = true))
    }

    @Test
    fun preserveParagraphs_collapsesRunsOfBlankLines() {
        assertEquals("One\n\nTwo", stripHtml("<p>One</p>\n\n\n<p>Two</p>", preserveParagraphs = true))
    }

    @Test
    fun preserveParagraphs_decodesEntities() {
        assertEquals("A & B", stripHtml("<p>A&nbsp;&amp;&nbsp;B</p>", preserveParagraphs = true))
    }

    @Test
    fun decodesTypographicNamedEntities() {
        assertEquals("Plato’s God", stripHtml("<p>Plato&rsquo;s God</p>"))
        assertEquals("a — b", stripHtml("<p>a &mdash; b</p>"))
    }

    @Test
    fun decodesNumericEntities() {
        assertEquals("it’s", stripHtml("<p>it&#8217;s</p>"))
        assertEquals("it’s", stripHtml("<p>it&#x2019;s</p>"))
    }

    @Test
    fun dropsScriptAndStyleBodies() {
        assertEquals(
            "Hello world",
            stripHtml("<p>Hello</p><script>var x = 1 < 2; alert('hi')</script><style>p{color:red}</style><p>world</p>"),
        )
    }

    @Test
    fun dropsComments() {
        assertEquals("Hello world", stripHtml("<p>Hello <!-- ignore > me --> world</p>"))
    }
}
