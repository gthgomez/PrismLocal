package com.prismai.llmhost.ui

import com.prismai.llmhost.ui.rag.DocumentIngestMessaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIngestMessagingTest {

    @Test
    fun allChunksFailed_isExplainedNotSilent() {
        val message = DocumentIngestMessaging.describe(inserted = 0, failed = 12, total = 12)
        assertTrue("a total failure must be explained", message.contains("could not"))
    }

    @Test
    fun partialFailure_isReportedHonestly() {
        val message = DocumentIngestMessaging.describe(inserted = 8, failed = 4, total = 12)
        assertTrue(message.contains("8"))
        assertTrue(message.contains("4"))
    }

    @Test
    fun fullSuccess_isNotOverExplained() {
        val message = DocumentIngestMessaging.describe(inserted = 12, failed = 0, total = 12)
        assertTrue(message.contains("12"))
        assertTrue(!message.contains("could not"))
    }

    @Test
    fun emptyInput_isReportedAsSuch() {
        assertEquals("Nothing to index", DocumentIngestMessaging.describe(0, 0, 0))
    }
}
