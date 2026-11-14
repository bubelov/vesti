package org.vestifeed.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assert.fail
import org.w3c.dom.Element

/**
 * Keeps the locale files honest.
 *
 * They drifted once already: at the KMP port the 28 locales were filled from the
 * old Android resources, and the 25 strings added afterwards were never
 * translated, so half the diagnostics screen stayed English on every
 * non-English device for months. Nothing failed, because nothing looked.
 *
 * Delete this file if English fallback for new strings is the intended policy -
 * the checks below are a choice, not a platform requirement.
 */
class TranslationCoverageTest {

    private val resources = File("src/commonMain/composeResources")

    private data class Entry(val name: String, val quantity: String?, val text: String)

    private fun parse(file: File): List<Entry> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val resourcesElement = root.documentElement
        require(resourcesElement.tagName == "resources") { "${file.name}: not a resources file" }
        val entries = mutableListOf<Entry>()
        val children = resourcesElement.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            when (element.tagName) {
                "string" -> entries += Entry(element.getAttribute("name"), null, element.textContent)
                "plurals" -> {
                    val quantityNodes = element.childNodes
                    for (j in 0 until quantityNodes.length) {
                        val item = quantityNodes.item(j) as? Element ?: continue
                        if (item.tagName != "item") continue
                        entries += Entry(
                            element.getAttribute("name"),
                            item.getAttribute("quantity"),
                            item.textContent,
                        )
                    }
                }
            }
        }
        return entries
    }

    /** The names of the declared resources, one per `<string>`/`<plurals>`. */
    private fun resourceNames(file: File): List<String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val children = root.childNodes
        return buildList {
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                if (element.tagName == "string" || element.tagName == "plurals") {
                    add(element.getAttribute("name"))
                }
            }
        }
    }

    private fun localeFiles(): List<File> = resources.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values-") }
        .sortedBy { it.name }
        .map { File(it, "strings.xml") }

    @Test
    fun everyLocaleTranslatesEverySourceString() {
        assertTrue(resources.isDirectory, "resource directory not found at ${resources.absolutePath}")

        val source = parse(File(resources, "values/strings.xml"))
        val sourceKeys = source.map { it.name }.toSet()
        val locales = localeFiles()
        assertTrue(locales.isNotEmpty(), "no locale directories found")

        val report = mutableListOf<String>()
        for (file in locales) {
            val locale = file.parentFile.name
            val keys = resourceNames(file)
            val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) report += "$locale: duplicate keys $duplicates"

            val missing = sourceKeys - keys.toSet()
            if (missing.isNotEmpty()) {
                report += "$locale: missing ${missing.size} of ${sourceKeys.size}: " +
                    missing.sorted().joinToString(", ")
            }
            val unknown = keys.toSet() - sourceKeys
            if (unknown.isNotEmpty()) {
                report += "$locale: keys absent from values/strings.xml: " +
                    unknown.sorted().joinToString(", ")
            }
        }

        if (report.isNotEmpty()) {
            fail("Translation files out of step with values/strings.xml:\n" + report.joinToString("\n"))
        }
    }

    /**
     * A translation must keep the placeholders its source has. Compose Resources
     * only substitutes positional ones, so a bare `%d` is both a mismatch and a
     * literal that leaks into the UI.
     */
    @Test
    fun everyTranslationKeepsItsPlaceholders() {
        val source = parse(File(resources, "values/strings.xml"))
            .associateBy { it.name to it.quantity }

        val report = mutableListOf<String>()
        for (file in localeFiles()) {
            val locale = file.parentFile.name
            for (entry in parse(file)) {
                val original = source[entry.name to entry.quantity] ?: continue
                val expected = placeholders(original.text)
                val actual = placeholders(entry.text)

                if (actual != expected) {
                    report += "$locale ${entry.name}${entry.quantity?.let { " ($it)" } ?: ""}: " +
                        "expected $expected, found $actual"
                }
                val bare = BARE_PLACEHOLDER.findAll(entry.text).map { it.value }.toList()
                if (bare.isNotEmpty()) {
                    report += "$locale ${entry.name}: non-positional placeholders $bare"
                }
            }
        }

        if (report.isNotEmpty()) {
            fail("Translation placeholders do not match the source:\n" + report.joinToString("\n"))
        }
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    private companion object {
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[a-zA-Z]")
        val BARE_PLACEHOLDER = Regex("%[a-zA-Z]")
    }
}
