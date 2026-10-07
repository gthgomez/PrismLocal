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
}
