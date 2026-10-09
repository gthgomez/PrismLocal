package com.prismai.llmhost.ui

import com.prismai.llmhost.ui.rag.DocumentIngestMessaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIngestMessagingTest {

    @Test
    fun allChunksFailed_isExplainedNotSilent() {
        val message = DocumentIngestMessaging.describe(embedded = 0, failed = 12, total = 12, committed = false)
        assertTrue("a total failure must be explained", message.contains("could not"))
        assertTrue("the preserved index must be stated", message.contains("preserved"))
    }

    @Test
    fun allChunksFailed_pinsTheWording() {
        // Pins the exact total-failure message so an unrelated phrase (e.g. "could not parse")
        // cannot satisfy the substring assertion above.
        assertEquals(
            "Could not index this document: 12 of 12 chunks could not be embedded; the previous index was preserved",
            DocumentIngestMessaging.describe(embedded = 0, failed = 12, total = 12, committed = false),
        )
    }

    @Test
    fun partialFailure_isRejectedAndReportedHonestly() {
        // A partial new chunk set is never committed, so the message states the counts and that
        // the previous index survived instead of claiming a partial index was written.
        val message = DocumentIngestMessaging.describe(embedded = 8, failed = 4, total = 12, committed = false)
        assertTrue(message.contains("4"))
        assertTrue(message.contains("12"))
        assertTrue(message.contains("previous index was preserved"))
    }

    @Test
    fun fullSuccess_isNotOverExplained() {
        val message = DocumentIngestMessaging.describe(embedded = 12, failed = 0, total = 12, committed = true)
        assertTrue(message.contains("12"))
        assertFalse(message.contains("could not"))
    }

    @Test
    fun emptyInput_isReportedAsSuch() {
        assertEquals("Nothing to index", DocumentIngestMessaging.describe(0, 0, 0, committed = false))
    }
}
