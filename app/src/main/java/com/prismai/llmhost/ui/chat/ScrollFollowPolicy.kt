package com.prismai.llmhost.ui.chat

/**
 * Whether the transcript may auto-scroll.
 *
 * Requirement: keep the latest message visible when following the
 * conversation, but never pull someone away from older messages they are
 * reading. Auto-scroll resumes only when they return to the bottom.
 */
class ScrollFollowPolicy {
    private var following = true

    fun onUserScrolledAway() {
        following = false
    }

    fun onUserScrolledToBottom() {
        following = true
    }

    fun shouldAutoScroll(): Boolean = following
}
