package com.prismai.llmhost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditReportTest {

    @Test
    fun reportEntry_isSerializableAndRoundTrips() {
        val entry = SecurityAuditLog.ReportEntry(
            messageId = "m-1",
            reason = "Dangerous or harmful instructions",
            excerpt = "do the thing",
        )
        val json = entry.toJson()
        assertEquals("m-1", json.getString("messageId"))
        assertTrue(json.toString().contains("report"))
    }

    @Test
    fun reportEntry_keepsTimestamp() {
        val entry = SecurityAuditLog.ReportEntry("m-1", "reason", "excerpt", timestampMs = 12345L)
        assertEquals(12345L, entry.toJson().getLong("timestampMs"))
    }

    /**
     * The dialog claimed a local save while persisting nothing. Verify the
     * report actually lands on disk through the same append-only path used by
     * the rest of the audit log.
     */
    @Test
    fun appendReport_persistsAJsonLine() {
        val dir = Files.createTempDirectory("security-audit-report").toFile()
        try {
            val log = SecurityAuditLog()
            log.init(dir)

            log.appendReport("m-1", "Dangerous or harmful instructions", "do the thing")

            val file = File(dir, "security_audit.jsonl")
            assertTrue("report file must be created", file.exists())
            val line = file.readLines().single()
            assertTrue(line.contains("\"type\":\"ai_content_report\""))
            assertTrue(line.contains("\"messageId\":\"m-1\""))
        } finally {
            dir.deleteRecursively()
        }
    }
}
