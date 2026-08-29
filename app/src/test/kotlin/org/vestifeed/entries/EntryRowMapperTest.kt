package org.vestifeed.entries

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryRowMapperTest {

    @Test
    fun joinSubtitleIncludesAuthorWhenOptedInAndPresent() {
        assertEquals(
            "Space.com · Jeff Spry · 5 mins ago",
            EntryRowMapper.joinSubtitle(
                feedTitle = "Space.com",
                authorName = "Jeff Spry",
                showAuthorName = true,
                timestamp = "5 mins ago",
            ),
        )
    }

    @Test
    fun joinSubtitleOmitsAuthorWhenOptedOut() {
        assertEquals(
            "Space.com · 5 mins ago",
            EntryRowMapper.joinSubtitle(
                feedTitle = "Space.com",
                authorName = "Jeff Spry",
                showAuthorName = false,
                timestamp = "5 mins ago",
            ),
        )
    }

    @Test
    fun joinSubtitleOmitsAuthorWhenBlank() {
        assertEquals(
            "Space.com · 5 mins ago",
            EntryRowMapper.joinSubtitle(
                feedTitle = "Space.com",
                authorName = "",
                showAuthorName = true,
                timestamp = "5 mins ago",
            ),
        )
        assertEquals(
            "Space.com · 5 mins ago",
            EntryRowMapper.joinSubtitle(
                feedTitle = "Space.com",
                authorName = "   ",
                showAuthorName = true,
                timestamp = "5 mins ago",
            ),
        )
    }

    @Test
    fun joinSubtitleOmitsAuthorWhenWhitespaceOnly() {
        assertEquals(
            "Space.com · 5 mins ago",
            EntryRowMapper.joinSubtitle(
                feedTitle = "Space.com",
                authorName = " \t\n ",
                showAuthorName = true,
                timestamp = "5 mins ago",
            ),
        )
    }

    @Test
    fun resolveShowImage_explicitShowOverridesGlobalHide() {
        assertEquals(true, EntryRowMapper.resolveShowImage(perFeed = true, global = false))
    }

    @Test
    fun resolveShowImage_explicitHideOverridesGlobalShow() {
        assertEquals(false, EntryRowMapper.resolveShowImage(perFeed = false, global = true))
    }

    @Test
    fun resolveShowImage_followSettingsFollowsGlobalOn() {
        assertEquals(true, EntryRowMapper.resolveShowImage(perFeed = null, global = true))
    }

    @Test
    fun resolveShowImage_followSettingsFollowsGlobalOff() {
        assertEquals(false, EntryRowMapper.resolveShowImage(perFeed = null, global = false))
    }
}