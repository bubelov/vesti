package org.vestifeed.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedBadgeLabelTest {

    @Test
    fun singleWord_usesFirstTwoLetters() {
        assertEquals("HA", feedBadgeLabel("Hacker"))
        assertEquals("X", feedBadgeLabel("x"))
    }

    @Test
    fun multipleWords_usesInitialsOfFirstTwo() {
        assertEquals("HN", feedBadgeLabel("Hacker News: Front Page"))
        assertEquals("TM", feedBadgeLabel("The Marginalian"))
    }

    @Test
    fun blankTitle_fallsBackToQuestionMark() {
        assertEquals("?", feedBadgeLabel(""))
        assertEquals("?", feedBadgeLabel("   "))
    }
}
