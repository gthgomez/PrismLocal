package com.prismai.llmhost.ui

import com.prismai.llmhost.AttachmentExtractionStatus
import com.prismai.llmhost.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStoreTest {

    private fun attachment(name: String) = PromptAttachment(
        uriString = "content://x/$name",
        name = name,
        mimeType = "text/plain",
        sizeBytes = 10L,
        extractionStatus = AttachmentExtractionStatus.EXTRACTED,
        promptText = "body",
    )

    @Test
    fun draftIsScopedToItsChat() {
        val store = DraftStore("chat_a")
        store.text = "hello"
        store.moveTo("chat_b")

        assertEquals("switching chats must not carry a draft", "", store.text)
    }

    @Test
    fun returningToAChatRestoresItsDraft() {
        val store = DraftStore("chat_a")
        store.text = "draft for a"
        store.moveTo("chat_b")
        store.text = "draft for b"
        store.moveTo("chat_a")

        assertEquals("draft for a", store.text)
    }

    @Test
    fun attachmentsFollowTheSameScoping() {
        val store = DraftStore("chat_a")
        store.attachments = listOf(attachment("a.txt"))
        store.moveTo("chat_b")
        assertTrue(store.attachments.isEmpty())
        store.moveTo("chat_a")
        assertEquals(1, store.attachments.size)
    }

    @Test
    fun clearEmptiesTheActiveDraftOnly() {
        val store = DraftStore("chat_a")
        store.text = "keep me"
        store.moveTo("chat_b")
        store.clear()
        store.moveTo("chat_a")
        assertEquals("keep me", store.text)
    }

    @Test
    fun snapshotIsValueCopied() {
        val store = DraftStore("chat_a")
        store.text = "original"
        val (text, _) = store.snapshotFor("chat_a")
        store.text = "mutated"
        assertEquals("snapshot must not alias live state", "original", text)
    }

    @Test
    fun nullChatIdIsTreatedAsItsOwnBucket() {
        val store = DraftStore(null)
        store.text = "no chat yet"
        store.moveTo(null)
        assertEquals("no chat yet", store.text)
    }

    @Test
    fun restoredDraftSurvivesWhenTheServiceReportsTheSameChat() {
        // Recreation: the service is unbound on the first frame, so the active
        // draft is loaded into a store seeded with the saved chat id. When the
        // service later reports that same id, nothing may move or discard it.
        val restored = listOf(attachment("a.txt"))
        val store = DraftStore("chat_a")
        store.restore("chat_a", "restored text", restored)

        val loaded = store.applyLiveDraft("chat_a", "restored text", restored)

        assertNull("same chat id must not move and discard the restored draft", loaded)
        assertEquals("restored text", store.text)
        assertEquals(1, store.attachments.size)
    }

    @Test
    fun transientUnknownChatIdDoesNotLoseTheDraft() {
        // Between rebinding and the service re-emitting the retained id, the
        // observed id can transiently be null. The live draft is attributed to
        // its own (saved) chat id, so the retained id recovers it.
        val restored = listOf(attachment("a.txt"))
        val store = DraftStore("chat_a")
        store.restore("chat_a", "restored text", restored)

        store.applyLiveDraft(null, "restored text", restored)
        val recovered = store.applyLiveDraft("chat_a", "", emptyList())

        assertEquals("restored text", recovered!!.first)
        assertEquals(1, recovered.second.size)
    }

    @Test
    fun switchingChatsSavesAndRestoresEachDraft() {
        val store = DraftStore("chat_a")
        store.restore("chat_a", "a text", listOf(attachment("a.txt")))

        val toB = store.applyLiveDraft("chat_b", "a text", listOf(attachment("a.txt")))
        assertEquals("", toB!!.first)
        assertTrue(toB.second.isEmpty())

        val backToA = store.applyLiveDraft("chat_a", "b text", listOf(attachment("b.txt")))
        assertEquals("a text", backToA!!.first)
        assertEquals("content://x/a.txt", backToA.second.single().uriString)

        assertEquals("chat b keeps its own draft", "b text", store.snapshotFor("chat_b").first)
    }

    /**
     * The whole store must survive process death, not only the active draft.
     * `encodeState`/`decodeState` back the Compose `Saver`, so the round trip
     * has to carry every keyed draft (active and inactive) and its attachments.
     */
    @Test
    fun stateRoundTripsEveryKeyedDraftIncludingInactiveOnes() {
        val store = DraftStore("chat_a")
        store.restore("chat_b", "b text", listOf(attachment("b.txt")))
        store.text = "a live edit"
        store.attachments = listOf(attachment("a.txt"))

        val restored = DraftStore.decodeState(store.encodeState())

        assertEquals("a live edit", restored.snapshotFor("chat_a").first)
        assertEquals("content://x/a.txt", restored.snapshotFor("chat_a").second.single().uriString)
        assertEquals("b text", restored.snapshotFor("chat_b").first)
        assertEquals("content://x/b.txt", restored.snapshotFor("chat_b").second.single().uriString)
    }

    @Test
    fun stateRoundTripsTheNullChatBucket() {
        val store = DraftStore(null)
        store.text = "no chat yet"

        val restored = DraftStore.decodeState(store.encodeState())

        assertEquals("no chat yet", restored.snapshotFor(null).first)
    }

    @Test
    fun largeAttachmentTextAcrossManyChatsIsStrictlyBoundedInSavedState() {
        DraftPayloadStore.resetForTesting()
        val store = DraftStore("chat_0")
        val longText = "A".repeat(16_000)

        // 10 chats with 6 attachments each = 60 attachments with ~960 KB total text
        for (chatIdx in 0 until 10) {
            val chatId = "chat_$chatIdx"
            val attachments = (1..6).map { attIdx ->
                PromptAttachment(
                    uriString = "content://media/$chatId/att_$attIdx",
                    name = "doc_$attIdx.txt",
                    mimeType = "text/plain",
                    sizeBytes = 16_000L,
                    extractionStatus = AttachmentExtractionStatus.EXTRACTED,
                    promptText = "$longText-$chatId-$attIdx",
                )
            }
            store.restore(chatId, "draft for $chatId", attachments)
        }

        val encodedState = store.encodeState()
        // Without payload offloading, this JSON was > 1 MB, crashing Android binder transaction limits.
        // With DraftPayloadStore offloading, the bundle JSON payload is strictly bounded (< 30 KB).
        assertTrue(
            "Serialized bundle state must be strictly bounded (< 40,000 chars), was ${encodedState.length}",
            encodedState.length < 40_000,
        )

        // Decode restores all drafts with their full 16,000-char prompt text
        val restored = DraftStore.decodeState(encodedState)
        for (chatIdx in 0 until 10) {
            val chatId = "chat_$chatIdx"
            val (text, atts) = restored.snapshotFor(chatId)
            assertEquals("draft for $chatId", text)
            assertEquals(6, atts.size)
            assertEquals("$longText-$chatId-1", atts[0].promptText)
            assertEquals("$longText-$chatId-6", atts[5].promptText)
        }
    }

    @Test
    fun clearingOneChatDoesNotTruncateAnotherChatsSharedAttachment() {
        DraftPayloadStore.resetForTesting()
        try {
            val full = "A".repeat(1_000)
            val shared = PromptAttachment(
                uriString = "content://media/shared.pdf",
                name = "shared.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1_000L,
                extractionStatus = AttachmentExtractionStatus.EXTRACTED,
                promptText = full,
            )
            val store = DraftStore("chat_a")
            store.restore("chat_a", "draft a", listOf(shared))
            store.restore("chat_b", "draft b", listOf(shared))

            // Persist both drafts' payloads the way the Compose Saver does.
            store.encodeState()

            // Clearing chat_a must not delete the payload chat_b still needs.
            store.clear()

            val restored = DraftStore.decodeState(store.encodeState())
            assertEquals(
                "chat_b must still restore the full offloaded payload",
                full,
                restored.snapshotFor("chat_b").second.single().promptText,
            )

            // Only once the last referencing draft is cleared is it released.
            restored.moveTo("chat_b")
            restored.clear()
            assertNull(
                "payload is released once no draft references it",
                DraftPayloadStore.get("content://media/shared.pdf"),
            )
        } finally {
            DraftPayloadStore.resetForTesting()
        }
    }

    @Test
    fun addAttachmentsToInactiveChatDoesNotModifyActiveChat() {
        val store = DraftStore("chat_a")
        store.attachments = listOf(attachment("a.txt"))

        val incomingAttachment = attachment("b.txt")
        store.addAttachments("chat_b", listOf(incomingAttachment))

        // Active chat a remains unchanged
        assertEquals(1, store.attachments.size)
        assertEquals("content://x/a.txt", store.attachments.single().uriString)

        // Target chat b draft received the attachment
        val (textB, attsB) = store.snapshotFor("chat_b")
        assertEquals(1, attsB.size)
        assertEquals("content://x/b.txt", attsB.single().uriString)
    }

    @Test
    fun clearIfMatchesOnlyClearsWhenPromptMatchesOriginatingDraft() {
        val store = DraftStore("chat_a")
        store.text = "sent prompt"
        val atts = listOf(attachment("a.txt"))
        store.attachments = atts

        // Mismatched prompt (e.g. user typed new text while send was in flight) does not clear
        store.clearIfMatches("chat_a", "different prompt", atts)
        assertEquals("sent prompt", store.text)

        // Matching prompt clears active draft
        store.clearIfMatches("chat_a", "sent prompt", atts)
        assertEquals("", store.text)
        assertTrue(store.attachments.isEmpty())
    }
}
