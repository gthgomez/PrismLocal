package com.prismai.llmhost
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AttachmentTextExtractorTest {
    @Test
    fun extractsQuotedPrintableHtmlFromMht() {
        val mht = """
            |MIME-Version: 1.0
            |Content-Type: multipart/related; boundary="----=_NextPart_000_0000"
            |
            |------=_NextPart_000_0000
            |Content-Type: text/html; charset="utf-8"
            |Content-Transfer-Encoding: quoted-printable
            |
            |<html><body><h1>Technical Details</h1><p>Stremio guide says install Torrentio.</p><p>Use Real-Debrid API token.</p></body></html>
            |------=_NextPart_000_0000--
        """.trimMargin()

        val result = AttachmentTextExtractor.fromBytes(
            name = "Technical Details _ Stremio _ Viren070's Guides.mht",
            mimeType = "message/rfc822",
            bytes = mht.toByteArray(Charsets.UTF_8),
        )

        assertEquals(AttachmentExtractionStatus.EXTRACTED, result.status)
        assertTrue(result.text.contains("Technical Details"))
        assertTrue(result.text.contains("install Torrentio"))
        assertTrue(result.text.contains("Real-Debrid API token"))
    }

    @Test
    fun extractsBase64HtmlFromMht() {
        val html = "<html><body><h1>Guide</h1><p>Configure Stremio add-ons from the community catalog.</p></body></html>"
        val encoded = Base64.getMimeEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        val mht = """
            |Content-Type: multipart/related; boundary="boundary42"
            |
            |--boundary42
            |Content-Type: text/html; charset=utf-8
            |Content-Transfer-Encoding: base64
            |
            |$encoded
            |--boundary42--
        """.trimMargin()

        val result = AttachmentTextExtractor.fromBytes(
            name = "guide.mhtml",
            mimeType = "application/x-mimearchive",
            bytes = mht.toByteArray(Charsets.UTF_8),
        )

        assertEquals(AttachmentExtractionStatus.EXTRACTED, result.status)
        assertTrue(result.text, result.text.contains("Configure Stremio add-ons"))
    }

    @Test
    fun metadataOnlyPromptWarnsModelNotToInferContents() {
        val attachment = PromptAttachment(
            uriString = "content://test/file.bin",
            name = "Technical Details _ Stremio _ Viren070's Guides.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 42L,
            extractionStatus = AttachmentExtractionStatus.METADATA_ONLY,
            promptText = "Extraction status: metadata_only. Unsupported file body for prompt injection; do not infer contents from the file name.",
        )

        val prompt = AttachmentTextExtractor.buildPrompt("Examine this.", listOf(attachment))

        assertTrue(prompt.contains("Only use attachment contents when extraction status is extracted"))
        assertTrue(prompt.contains("Extraction status: metadata_only"))
        assertTrue(prompt.contains("do not infer contents from the file name"))
    }

    @Test
    fun multiAttachmentPromptBuilderEnforcesGlobalCharCap() {
        val longText = "A".repeat(5_000)
        val attachment1 = PromptAttachment(
            uriString = "content://test/file1.txt",
            name = "file1.txt",
            mimeType = "text/plain",
            sizeBytes = 5000L,
            extractionStatus = AttachmentExtractionStatus.EXTRACTED,
            promptText = longText,
        )
        val attachment2 = PromptAttachment(
            uriString = "content://test/file2.txt",
            name = "file2.txt",
            mimeType = "text/plain",
            sizeBytes = 5000L,
            extractionStatus = AttachmentExtractionStatus.EXTRACTED,
            promptText = longText,
        )

        val maxTotal = 2_000
        val prompt = AttachmentTextExtractor.buildPrompt(
            prompt = "Review files",
            attachments = listOf(attachment1, attachment2),
            maxTotalAttachmentChars = maxTotal,
        )

        assertTrue(prompt.contains("[Truncated for attachment budget]"))
        // Body content only: equal share 1000 + 1000, marker included in each share
        assertTrue(
            "attachment body chars must not exceed global cap",
            prompt.count { it == 'A' } <= maxTotal,
        )
    }

    @Test
    fun manyAttachmentsDoNotOvershootGlobalCapViaPerFileFloor() {
        // Previous bug: coerceAtLeast(400) made 10 files * 400 = 4000 > cap 2000
        val longText = "B".repeat(5_000)
        val attachments = (1..10).map { i ->
            PromptAttachment(
                uriString = "content://test/file$i.txt",
                name = "file$i.txt",
                mimeType = "text/plain",
                sizeBytes = 5000L,
                extractionStatus = AttachmentExtractionStatus.EXTRACTED,
                promptText = longText,
            )
        }
        val maxTotal = 2_000
        val prompt = AttachmentTextExtractor.buildPrompt(
            prompt = "Review many",
            attachments = attachments,
            maxTotalAttachmentChars = maxTotal,
        )

        assertTrue(
            "10-file equal share must stay within global body budget (was overshooting with 400 floor)",
            prompt.count { it == 'B' } <= maxTotal,
        )
        // 2000 / 10 = 200 per file; bodies should be truncated
        assertTrue(prompt.contains("[Truncated for attachment budget]"))
    }

    @Test
    fun truncateAttachmentBodyKeepsMarkerInsideLimit() {
        val text = "C".repeat(100)
        val truncated = AttachmentTextExtractor.truncateAttachmentBody(text, limit = 40)
        assertEquals(40, truncated.length)
        assertTrue(truncated.endsWith("[Truncated for attachment budget]"))
        assertTrue(truncated.count { it == 'C' } < 40)
    }

    @Test
    fun zeroAttachmentBudgetYieldsEmptyBodies() {
        val attachment = PromptAttachment(
            uriString = "content://test/file.txt",
            name = "file.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            extractionStatus = AttachmentExtractionStatus.EXTRACTED,
            promptText = "SECRET_PAYLOAD",
        )
        val prompt = AttachmentTextExtractor.buildPrompt(
            prompt = "Hi",
            attachments = listOf(attachment),
            maxTotalAttachmentChars = 0,
        )
        assertTrue(!prompt.contains("SECRET_PAYLOAD"))
    }
}
