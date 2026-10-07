package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSelectionLimitTest {

    /**
     * The picker reported "Importing N" while processing every file and then
     * keeping only the last 6. The limit must decide what gets *read* from the
     * content provider, not just what is retained afterwards.
     */
    @Test
    fun limitIsAppliedBeforeProcessing() {
        val selected = List(10) { "file$it.txt" }
        val accepted = AttachmentSelection.takeUpTo(selected, AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
        assertEquals(AttachmentSelection.MAX_PROMPT_ATTACHMENTS, accepted.size)
        assertEquals("file0.txt", accepted.first())
    }

    @Test
    fun selectionUnderLimitIsUnchanged() {
        val selected = listOf("a.txt", "b.txt")
        assertEquals(selected, AttachmentSelection.takeUpTo(selected, AttachmentSelection.MAX_PROMPT_ATTACHMENTS))
    }

    @Test
    fun selectionIsDeduplicatedPreservingOrder() {
        val selected = listOf("a.txt", "b.txt", "a.txt")
        assertEquals(listOf("a.txt", "b.txt"), AttachmentSelection.takeUpTo(selected, AttachmentSelection.MAX_PROMPT_ATTACHMENTS))
    }

    @Test
    fun emptySelectionYieldsEmpty() {
        assertTrue(AttachmentSelection.takeUpTo(emptyList(), AttachmentSelection.MAX_PROMPT_ATTACHMENTS).isEmpty())
    }
}
