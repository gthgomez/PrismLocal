package com.prismai.llmhost.export

import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.junit.Assert.*
import org.junit.Test

class QualityDatasetFilterTest {

    @Test
    fun testFiltersShortAndErrorMessages() {
        val messages = listOf(
            TranscriptMessage(id = 1L, role = TranscriptRole.USER, text = "hi"), // too short
            TranscriptMessage(id = 2L, role = TranscriptRole.ASSISTANT, text = "generation failed in native runtime"), // error
            TranscriptMessage(id = 3L, role = TranscriptRole.USER, text = "What is the capital of France?"),
            TranscriptMessage(id = 4L, role = TranscriptRole.ASSISTANT, text = "The capital of France is Paris.")
        )

        val filtered = QualityDatasetFilter.filterValidMessages(messages)
        assertEquals(2, filtered.size)
        assertEquals("What is the capital of France?", filtered[0].text)
        assertEquals("The capital of France is Paris.", filtered[1].text)
    }
}
