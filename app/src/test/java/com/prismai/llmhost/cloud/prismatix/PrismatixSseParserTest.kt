package com.prismai.llmhost.cloud.prismatix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class PrismatixSseParserTest {

    @Test
    fun parsesDeltasAndTerminalDoneCleanly() {
        val ssePayload = """
            : heartbeat
            
            data: {"type":"content_block_delta","delta":{"text":"Hello"}}
            
            data: {"type":"content_block_delta","delta":{"text":" world!"}}
            
            data: [DONE]
        """.trimIndent()

        val events = mutableListOf<PrismatixStreamEvent>()
        val inputStream = ByteArrayInputStream(ssePayload.toByteArray(StandardCharsets.UTF_8))

        PrismatixSseParser.parseStream(inputStream) { events.add(it) }

        assertEquals(3, events.size)
        assertEquals(PrismatixStreamEvent.Delta("Hello"), events[0])
        assertEquals(PrismatixStreamEvent.Delta(" world!"), events[1])
        assertEquals(PrismatixStreamEvent.Done, events[2])
    }

    @Test
    fun emitsProtocolErrorOnMalformedJsonFrame() {
        val ssePayload = """
            data: {"type":"content_block_delta","delta":{"text":"Prefix"}}
            data: {not valid json}
            data: {"type":"content_block_delta","delta":{"text":"Suffix"}}
            data: [DONE]
        """.trimIndent()

        val events = mutableListOf<PrismatixStreamEvent>()
        val inputStream = ByteArrayInputStream(ssePayload.toByteArray(StandardCharsets.UTF_8))

        PrismatixSseParser.parseStream(inputStream) { events.add(it) }

        // Must emit delta followed by Protocol Error, and abort further stream processing
        assertEquals(2, events.size)
        assertEquals(PrismatixStreamEvent.Delta("Prefix"), events[0])
        assertTrue(events[1] is PrismatixStreamEvent.Error)
        val error = events[1] as PrismatixStreamEvent.Error
        assertEquals(-2, error.statusCode)
        assertTrue(error.message.contains("Protocol Error"))
    }

    @Test
    fun parsesServerErrorPayloadInStream() {
        val ssePayload = """
            data: {"error":"Upstream provider rate limit"}
            data: [DONE]
        """.trimIndent()

        val events = mutableListOf<PrismatixStreamEvent>()
        val inputStream = ByteArrayInputStream(ssePayload.toByteArray(StandardCharsets.UTF_8))

        PrismatixSseParser.parseStream(inputStream) { events.add(it) }

        assertEquals(1, events.size)
        assertTrue(events[0] is PrismatixStreamEvent.Error)
        assertEquals("Upstream provider rate limit", (events[0] as PrismatixStreamEvent.Error).message)
    }
}
