package com.prismai.llmhost.ui

import androidx.compose.ui.unit.dp
import com.prismai.llmhost.ui.chat.ScrollFollowPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPolicyTest {

    @Test
    fun shortHeight_collapsesTheTray() {
        assertFalse(LayoutPolicy.trayVisible(attachmentCount = 4, isShortHeight = true))
    }

    @Test
    fun normalHeight_keepsTheTray() {
        assertTrue(LayoutPolicy.trayVisible(attachmentCount = 4, isShortHeight = false))
    }

    @Test
    fun noAttachments_neverShowsATray() {
        assertFalse(LayoutPolicy.trayVisible(attachmentCount = 0, isShortHeight = false))
    }

    @Test
    fun shortHeight_compactsTheHeader() {
        assertTrue(LayoutPolicy.headerCompact(availableHeightDp = 380.dp))
        assertFalse(LayoutPolicy.headerCompact(availableHeightDp = 800.dp))
    }

    @Test
    fun followPolicy_followsUntilTheUserScrollsAway() {
        val p = ScrollFollowPolicy()
        assertTrue(p.shouldAutoScroll())
        p.onUserScrolledAway()
        assertFalse("must not yank the reader back", p.shouldAutoScroll())
    }

    @Test
    fun followPolicy_resumesWhenTheUserReturnsToTheBottom() {
        val p = ScrollFollowPolicy()
        p.onUserScrolledAway()
        p.onUserScrolledToBottom()
        assertTrue(p.shouldAutoScroll())
    }
}
