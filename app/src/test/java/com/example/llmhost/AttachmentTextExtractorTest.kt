package com.example.llmhost

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
}
