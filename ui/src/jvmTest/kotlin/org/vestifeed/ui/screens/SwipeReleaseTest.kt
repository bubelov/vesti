package org.vestifeed.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeReleaseTest {

    private val width = 1000f
    private val escapeVelocity = 300f

    private fun release(
        offset: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ) = resolveSwipeRelease(
        offset = offset,
        velocityX = velocityX,
        velocityY = velocityY,
        width = width,
        escapeVelocity = escapeVelocity,
    )

    @Test
    fun releasedShortOfHalfWidth_doesNotCommit() {
        assertFalse(release(offset = width * 0.49f).commit)
    }

    @Test
    fun releasedPastHalfWidth_commitsInTheDraggedDirection() {
        assertTrue(release(offset = width * 0.6f).commit)
        assertTrue(release(offset = width * 0.6f).bookmark)
        assertTrue(release(offset = -width * 0.6f).commit)
        assertFalse(release(offset = -width * 0.6f).bookmark)
    }

    @Test
    fun aFastFlickCommitsEvenWhenTheCardBarelyMoved() {
        val fastFlick = release(offset = 40f, velocityX = escapeVelocity + 1f)
        assertTrue(fastFlick.commit)
        assertTrue(fastFlick.bookmark)
    }

    @Test
    fun aFastFlickBackCommitsAsMarkRead() {
        val fastFlick = release(offset = -40f, velocityX = -(escapeVelocity + 1f))
        assertTrue(fastFlick.commit)
        assertFalse(fastFlick.bookmark)
    }

    @Test
    fun aSlowFlickFallsBackToTheReleasePosition() {
        val slow = release(offset = 40f, velocityX = escapeVelocity - 1f)
        assertFalse(slow.commit)
    }

    @Test
    fun aFlickOppositeTheDragDoesNotCommitByVelocity() {
        // Dragged left but flicked right on release: the position (well short
        // of half a width) must decide, not the flick.
        val flickedBack = release(offset = -40f, velocityX = escapeVelocity + 1f)
        assertFalse(flickedBack.commit)
    }

    @Test
    fun aVerticalFlingDoesNotCommitHorizontally() {
        val fling = release(
            offset = 40f,
            velocityX = escapeVelocity + 1f,
            velocityY = escapeVelocity + 200f,
        )
        assertFalse(fling.commit)
    }
}
