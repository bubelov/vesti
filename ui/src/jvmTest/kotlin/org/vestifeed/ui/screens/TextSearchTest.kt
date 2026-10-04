package org.vestifeed.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TextSearchTest {

    @Test
    fun findMatches_returnsInclusiveRangesInOrder() {
        assertEquals(
            listOf(0..3, 9..12),
            findMatches("test and test", "test"),
        )
    }

    @Test
    fun findMatches_isCaseInsensitive() {
        assertEquals(listOf(0..6, 14..20), findMatches("Bitcoin rules bitcoin", "bitcoin"))
    }

    @Test
    fun findMatches_doesNotOverlap() {
        // "aa" occurrences in "aaaa" are non-overlapping: [0..1], [2..3].
        assertEquals(listOf(0..1, 2..3), findMatches("aaaa", "aa"))
    }

    @Test
    fun findMatches_blankQueryYieldsNothing() {
        assertEquals(emptyList(), findMatches("some text", ""))
        assertEquals(emptyList(), findMatches("some text", "   "))
    }

    @Test
    fun findMatches_trimsQuery() {
        assertEquals(listOf(4..7), findMatches("the text", "  text  "))
    }

    @Test
    fun findMatches_noOccurrenceIsEmpty() {
        assertEquals(emptyList(), findMatches("some text", "missing"))
    }
}
