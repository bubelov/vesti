package org.vestifeed.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
