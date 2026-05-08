package com.example.llmhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingTextStateTest {
    @Test
    fun staleChunksAreIgnoredAfterGenerationBegins() {
        val state = StreamingTextState()

        state.beginGeneration(expectedGenerationId = 2)
        state.append(GenerationChunk("old", tokenCount = 1, generationId = 1, isTerminal = false))
        state.append(GenerationChunk("new", tokenCount = 1, generationId = 2, isTerminal = true, terminalReason = "EOF"))

        assertEquals("new", state.snapshotText())
    }

    @Test
    fun concurrentAppendClearSnapshotDoesNotThrowOrCorruptState() = runBlocking {
        val state = StreamingTextState()
        state.beginGeneration(expectedGenerationId = 10)

        val writers = (0 until 8).map { writer ->
            launch(Dispatchers.Default) {
                repeat(100) { index ->
                    state.append(
                        GenerationChunk(
                            text = "$writer:$index;",
                            tokenCount = 1,
                            generationId = 10,
                            isTerminal = index == 99,
                            terminalReason = if (index == 99) "EOF" else "NONE",
                        )
                    )
                }
            }
        }
        val clearer = launch(Dispatchers.Default) {
            repeat(20) {
                state.snapshotText()
            }
        }

        (writers + clearer).joinAll()

        assertTrue(state.snapshotText().isNotEmpty())
    }
}
