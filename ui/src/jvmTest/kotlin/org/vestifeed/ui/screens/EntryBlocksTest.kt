package org.vestifeed.ui.screens

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryBlocksTest {

    @Test
    fun figureBecomesImageWithCaption() {
        val html = "<p>Hello</p>" +
            "<figure><a href=\"x\"><img src=\"https://a/1.webp\" alt=\"\" /></a>" +
            "<figcaption>Two loaves</figcaption></figure>" +
            "<p>Bye</p>"

        assertEquals(
            listOf(
                EntryBlock.Paragraph("Hello"),
                EntryBlock.Image("https://a/1.webp", "Two loaves"),
                EntryBlock.Paragraph("Bye"),
            ),
            parseEntryBlocks(html),
        )
    }

    @Test
    fun imagesKeepDocumentOrder() {
        val html = "<p>One</p>" +
            "<figure><img src=\"https://a/1.jpg\"><figcaption>First</figcaption></figure>" +
            "<p>Two</p>" +
            "<figure><img src=\"https://a/2.jpg\"><figcaption>Second</figcaption></figure>" +
            "<p>Three</p>"

        assertEquals(
            listOf(
                EntryBlock.Paragraph("One"),
                EntryBlock.Image("https://a/1.jpg", "First"),
                EntryBlock.Paragraph("Two"),
                EntryBlock.Image("https://a/2.jpg", "Second"),
                EntryBlock.Paragraph("Three"),
            ),
            parseEntryBlocks(html),
        )
    }

    @Test
    fun strayImgOutsideFigureBecomesImage() {
        val html = "<p>A</p><img src=\"https://a/3.jpg\"><p>B</p>"

        assertEquals(
            listOf(
                EntryBlock.Paragraph("A"),
                EntryBlock.Image("https://a/3.jpg", null),
                EntryBlock.Paragraph("B"),
            ),
            parseEntryBlocks(html),
        )
    }

    @Test
    fun protocolRelativeUrlGetsHttps() {
        assertEquals(
            listOf(EntryBlock.Image("https://cdn/x.jpg", null)),
            parseEntryBlocks("<img src=\"//cdn/x.jpg\">"),
        )
    }

    @Test
    fun relativeUrlIsDropped() {
        assertEquals(
            listOf(EntryBlock.Paragraph("A"), EntryBlock.Paragraph("B")),
            parseEntryBlocks("<p>A</p><img src=\"/img/x.jpg\"><p>B</p>"),
        )
    }

    @Test
    fun paragraphsKeepTheirBreaks() {
        assertEquals(
            listOf(EntryBlock.Paragraph("One\n\nTwo")),
            parseEntryBlocks("<p>One</p><p>Two</p>"),
        )
    }

    @Test
    fun anchorBecomesClickableSpan() {
        val html = "<p>See <a href=\"https://example.com/x\">this</a> now</p>"

        assertEquals(
            listOf(
                EntryBlock.Paragraph(
                    text = "See this now",
                    links = listOf(LinkSpan(start = 4, end = 8, url = "https://example.com/x")),
                ),
            ),
            parseEntryBlocks(html),
        )
    }

    @Test
    fun anchorWithMarkupInsideKeepsItsRange() {
        val html = "<p>A <a href=\"https://e/x\"><b>bold</b> tail</a> end</p>"

        assertEquals(
            listOf(
                EntryBlock.Paragraph(
                    text = "A bold tail end",
                    links = listOf(LinkSpan(start = 2, end = 11, url = "https://e/x")),
                ),
            ),
            parseEntryBlocks(html),
        )
    }

    @Test
    fun severalAnchorsKeepTheirOwnRanges() {
        val html = "<p><a href=\"https://a\">one</a> and <a href=\"https://b\">two</a></p>"

        assertEquals(
            listOf(
                EntryBlock.Paragraph(
                    text = "one and two",
                    links = listOf(
                        LinkSpan(start = 0, end = 3, url = "https://a"),
                        LinkSpan(start = 8, end = 11, url = "https://b"),
                    ),
                ),
            ),
            parseEntryBlocks(html),
        )
    }

    @Test
    fun anchorWithoutHrefStaysPlainText() {
        assertEquals(
            listOf(EntryBlock.Paragraph("plain")),
            parseEntryBlocks("<p><a>plain</a></p>"),
        )
    }

    @Test
    fun resolveLink_leavesAbsoluteUrlsAlone() {
        assertEquals("https://x/y", resolveLink("https://x/y", baseUrl = null))
        assertEquals("http://x/y", resolveLink("http://x/y", baseUrl = null))
    }

    @Test
    fun resolveLink_upgradesProtocolRelativeUrls() {
        assertEquals("https://cdn/x.jpg", resolveLink("//cdn/x.jpg", baseUrl = null))
    }

    @Test
    fun resolveLink_keepsMailtoAndTel() {
        assertEquals("mailto:a@b.c", resolveLink("mailto:a@b.c", baseUrl = null))
        assertEquals("tel:+123", resolveLink("tel:+123", baseUrl = null))
    }

    @Test
    fun resolveLink_resolvesRelativeTargetsAgainstTheEntryUrl() {
        val base = "https://site.com/news/2026/article.html"
        assertEquals("https://site.com/other", resolveLink("/other", base))
        assertEquals("https://site.com/news/2026/next.html", resolveLink("next.html", base))
    }

    @Test
    fun resolveLink_rejectsFragmentsAndScriptLinks() {
        assertEquals(null, resolveLink("#section", baseUrl = "https://site.com/a"))
        assertEquals(null, resolveLink("javascript:alert(1)", baseUrl = "https://site.com/a"))
    }

    @Test
    fun resolveLink_relativeTargetWithoutABaseIsNotALink() {
        assertEquals(null, resolveLink("next.html", baseUrl = null))
        assertEquals(null, resolveLink("/other", baseUrl = ""))
    }

    @Test
    fun paragraphAnnotatedString_attachesLinkAnnotationForResolvedSpan() {
        val paragraph = EntryBlock.Paragraph(
            text = "See this now",
            links = listOf(LinkSpan(start = 4, end = 8, url = "https://e/x")),
        )

        val annotated = paragraphAnnotatedString(
            paragraph = paragraph,
            matches = emptyList(),
            currentMatch = -1,
            matchStyle = SpanStyle(),
            currentStyle = SpanStyle(),
            linkStyle = SpanStyle(),
            resolveUrl = { it },
            onOpenLink = {},
        )

        assertEquals("See this now", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        assertEquals(4, links[0].start)
        assertEquals(8, links[0].end)
        assertEquals("https://e/x", (links[0].item as LinkAnnotation.Url).url)
    }

    @Test
    fun paragraphAnnotatedString_skipsUnresolvableLinks() {
        val paragraph = EntryBlock.Paragraph(
            text = "a b",
            links = listOf(LinkSpan(start = 0, end = 1, url = "#frag")),
        )

        val annotated = paragraphAnnotatedString(
            paragraph = paragraph,
            matches = emptyList(),
            currentMatch = -1,
            matchStyle = SpanStyle(),
            currentStyle = SpanStyle(),
            linkStyle = SpanStyle(),
            resolveUrl = { resolveLink(it, baseUrl = null) },
            onOpenLink = {},
        )

        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }
}
