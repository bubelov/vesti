package org.vestifeed.backend

import org.junit.Test

class EntrySummaryTest {

    @Test
    fun skipsTableOfContentsAndPicksFirstParagraph() {
        val html = """
            <div class="table-of-contents">
                <h2>Table of Contents</h2>
                <ul>
                    <li><a href="#preface">Preface</a></li>
                    <li><a href="#conclusion">Conclusion</a></li>
                </ul>
            </div>
            <p>Most bitcoiners I know want smaller government, lower taxes, and full control over their money.</p>
        """.trimIndent()

        val summary = html.toEntrySummary()

        assert(summary.startsWith("Most bitcoiners")) { "got: $summary" }
        assert(!summary.contains("Table of Contents")) { "got: $summary" }
    }

    @Test
    fun handlesTableOfContentsInNav() {
        val body = ("A ".repeat(40) + "body paragraph.").trim()
        val html = """
            <nav id="TableOfContents">
                <ul><li><a href="#x">Section</a></li></ul>
            </nav>
            <p>$body</p>
        """.trimIndent()

        val summary = html.toEntrySummary()

        assert(summary.startsWith("A ")) { "got: $summary" }
        assert(summary.endsWith("body paragraph.")) { "got: $summary" }
        assert(!summary.contains("TableOfContents"))
    }

    @Test
    fun fallsBackToStrippedBodyWhenNoParagraphQualifies() {
        val html = "<p>short</p><p>also short</p>"

        val summary = html.toEntrySummary()

        assert(summary == "short also short")
    }

    @Test
    fun capsLongParagraphs() {
        val body = "x".repeat(200)
        val html = "<p>$body</p>"

        val summary = html.toEntrySummary(maxLength = 80)

        assert(summary.length == 80)
        assert(!summary.endsWith(" "))
    }

    @Test
    fun ignoresShortLeadParagraph() {
        val html = """
            <p>tiny</p>
            <p>${"Real content. ".repeat(10).trim()}</p>
        """.trimIndent()

        val summary = html.toEntrySummary()

        assert(summary.startsWith("Real content."))
    }

    @Test
    fun handlesRealWorldWealthTaxArticle() {
        val html = """
            <div class="table-of-contents">
                <h2>Table of Contents</h2>
                <nav id="TableOfContents">
                    <ul>
                        <li><a href="#preface">Preface</a></li>
                        <li><a href="#conclusion">Conclusion</a></li>
                    </ul>
                </nav>
            </div>
            <p>Most bitcoiners I know want smaller government, lower taxes, and full control over their money. So far so good, but they're also suspicious of anyone who talks about "the rich not paying their fair share". In my view, the latter issue is manufactured and goes against their own interests.</p>
        """.trimIndent()

        val summary = html.toEntrySummary()

        assert(summary.startsWith("Most bitcoiners")) { "got: $summary" }
        assert(!summary.contains("Table of Contents")) { "got: $summary" }
        assert(!summary.contains("Preface")) { "got: $summary" }
    }

    @Test
    fun decodesHtmlEntitiesInParagraph() {
        val html = """
            <p>It&rsquo;s surprisingly common for people &mdash; even smart ones &mdash; to assume &amp; hope the world works a certain way.</p>
        """.trimIndent()

        val summary = html.toEntrySummary()

        assert(summary.startsWith("It’s surprisingly common")) { "got: $summary" }
        assert(summary.contains("—")) { "got: $summary" }
        assert(summary.contains("& hope")) { "got: $summary" }
    }

    @Test
    fun decodesHtmlEntitiesInFallback() {
        val html = "<p>tiny</p><p>also tiny</p><p>Tom &amp; Jerry &mdash; classic</p>"

        val summary = html.toEntrySummary()

        assert(summary.contains("Tom & Jerry")) { "got: $summary" }
        assert(summary.contains("—")) { "got: $summary" }
    }

    @Test
    fun decodesAstralNumericEntity() {
        // U+1F600 GRINNING FACE needs a surrogate pair.
        val summary = "<p>${"x".repeat(90)} &#x1F600;</p>".toEntrySummary()

        assert(summary.contains("\uD83D\uDE00")) { "got: $summary" }
    }
}
