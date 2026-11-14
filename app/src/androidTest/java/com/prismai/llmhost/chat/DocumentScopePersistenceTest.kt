package com.prismai.llmhost.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentScopePersistenceTest {
    @Test
    fun documentScopeSurvivesTranscriptRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "document-scope-${UUID.randomUUID()}.json")
        try {
            val store = TranscriptStore(context)
            val messages = listOf(
                TranscriptMessage(1, TranscriptRole.USER, "Who owns it?", documentScope = "cedar"),
                TranscriptMessage(2, TranscriptRole.ASSISTANT, "Mira", documentScope = "cedar"),
                TranscriptMessage(3, TranscriptRole.USER, "ordinary chat"),
            )
            store.writeTranscriptFile(file, messages)
            assertEquals(messages, store.readTranscriptFile(file))
        } finally {
            file.delete()
        }
    }
}
