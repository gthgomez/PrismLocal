package com.prismai.llmhost.cloud.prismatix

import com.prismai.llmhost.work.DistributionConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PrismatixClientTest {

    private val playConfig = object : DistributionConfig {
        override val developerWorkMode: Boolean = false
        override val localProcessExecutionAvailable: Boolean = false
        override val babelHostExecutionAvailable: Boolean = false
    }

    private val devConfig = object : DistributionConfig {
        override val developerWorkMode: Boolean = true
        override val localProcessExecutionAvailable: Boolean = true
        override val babelHostExecutionAvailable: Boolean = true
    }

    private class MockHttpURLConnection(
        url: URL,
        private val responseStatusCode: Int,
        private val responseBody: String,
        private val responseHeaders: Map<String, String> = emptyMap(),
        private val isError: Boolean = false,
    ) : HttpURLConnection(url) {
        val capturedOutputStream = ByteArrayOutputStream()
        val capturedRequestProperties = mutableMapOf<String, String>()

        override fun getOutputStream(): OutputStream = capturedOutputStream

        override fun getInputStream(): InputStream {
            if (isError) throw java.io.IOException("HTTP error response: $responseStatusCode")
            return ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
        }

        override fun getErrorStream(): InputStream? {
            if (!isError) return null
            return ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
        }

        override fun getResponseCode(): Int = responseStatusCode
        override fun getResponseMessage(): String = if (responseStatusCode == 200) "OK" else "Error"

        override fun getHeaderField(name: String?): String? = responseHeaders[name]

        override fun setRequestProperty(key: String, value: String) {
            capturedRequestProperties[key] = value
        }

        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
    }

    @Test
    fun successfulStreamReconstructsMetadataAndDeltas() = runBlocking {
        val sseResponse = """
            data: {"type":"content_block_delta","delta":{"text":"The"}}
            data: {"type":"content_block_delta","delta":{"text":" answer is 42."}}
            data: [DONE]
        """.trimIndent()

        val headers = mapOf(
            "X-Router-Model" to "gemini-2.5-flash",
            "X-Provider" to "google",
            "X-Cost-Estimate-USD" to "0.000125",
            "X-Router-Rationale" to "fast_path",
            "X-Complexity-Score" to "0.35",
        )

        var capturedConn: MockHttpURLConnection? = null
        val client = HttpPrismatixClient(
            config = PrismatixConfig(baseUrl = "https://api.prismatix.ai/functions/v1/router"),
            distributionConfig = playConfig,
            connectionFactory = { url ->
                MockHttpURLConnection(
                    url = url,
                    responseStatusCode = 200,
                    responseBody = sseResponse,
                    responseHeaders = headers,
                ).also { capturedConn = it }
            }
        )

        val request = PrismatixChatRequest(
            conversationId = "11111111-2222-3333-4444-555555555555",
            query = "What is the answer?",
            platform = "mobile",
            history = listOf(PrismatixMessage("user", "Hello")),
        )

        val events = client.streamChat(request, authToken = "test-jwt-token").toList()

        assertNotNull(capturedConn)
        assertEquals("Bearer test-jwt-token", capturedConn?.capturedRequestProperties?.get("Authorization"))
        assertEquals("application/json; charset=UTF-8", capturedConn?.capturedRequestProperties?.get("Content-Type"))
        assertEquals("text/event-stream", capturedConn?.capturedRequestProperties?.get("Accept"))

        // Check request payload content
        val writtenJson = capturedConn?.capturedOutputStream?.toString("UTF-8") ?: ""
        assertTrue(writtenJson.contains("\"platform\":\"mobile\""))
        assertTrue(writtenJson.contains("\"conversationId\":\"11111111-2222-3333-4444-555555555555\""))

        // Verify emitted events
        assertEquals(4, events.size)

        val metadata = events[0] as PrismatixStreamEvent.Metadata
        assertEquals("gemini-2.5-flash", metadata.routerModel)
        assertEquals("google", metadata.provider)
        assertEquals(0.000125, metadata.costEstimateUsd ?: 0.0, 0.000001)

        assertEquals(PrismatixStreamEvent.Delta("The"), events[1])
        assertEquals(PrismatixStreamEvent.Delta(" answer is 42."), events[2])
        assertEquals(PrismatixStreamEvent.Done, events[3])
    }

    @Test
    fun playDistributionEnforcesExactHostPinningAndRejectsWildcards() = runBlocking {
        // 1. Arbitrary / attacker Supabase project on Play -> must reject (P0 guard)
        val attackerClient = HttpPrismatixClient(
            config = PrismatixConfig(baseUrl = "https://attacker-project.supabase.co/functions/v1/router"),
            distributionConfig = playConfig,
        )
        val events1 = attackerClient.streamChat(
            PrismatixChatRequest("11111111-2222-3333-4444-555555555555", "Hi"),
            authToken = "secret-jwt",
        ).toList()

        assertEquals(1, events1.size)
        assertTrue(events1[0] is PrismatixStreamEvent.Error)
        val error1 = events1[0] as PrismatixStreamEvent.Error
        assertEquals(403, error1.statusCode)
        assertTrue(error1.message.contains("rejects unpinned host 'attacker-project.supabase.co'"))

        // 2. Unencrypted HTTP on Play -> must reject
        val cleartextClient = HttpPrismatixClient(
            config = PrismatixConfig(baseUrl = "http://api.prismatix.ai/functions/v1/router"),
            distributionConfig = playConfig,
        )
        val events2 = cleartextClient.streamChat(
            PrismatixChatRequest("11111111-2222-3333-4444-555555555555", "Hi"),
            authToken = "secret-jwt",
        ).toList()

        assertEquals(1, events2.size)
        assertTrue((events2[0] as PrismatixStreamEvent.Error).message.contains("requires secure HTTPS endpoint"))
    }

    @Test
    fun devDistributionPermitsLocalhostHttp() = runBlocking {
        val devLocalClient = HttpPrismatixClient(
            config = PrismatixConfig(baseUrl = "http://127.0.0.1:54321/functions/v1/router"),
            distributionConfig = devConfig,
            connectionFactory = { url ->
                MockHttpURLConnection(
                    url = url,
                    responseStatusCode = 200,
                    responseBody = "data: [DONE]\n\n",
                )
            }
        )

        val events = devLocalClient.streamChat(
            PrismatixChatRequest("11111111-2222-3333-4444-555555555555", "Hi"),
        ).toList()

        assertEquals(2, events.size) // Metadata + Done
        assertEquals(PrismatixStreamEvent.Done, events[1])
    }

    @Test
    fun handles401UnauthorizedError() = runBlocking {
        val errorJson = """{"error":"Unauthorized: Invalid or expired token"}"""

        val client = HttpPrismatixClient(
            config = PrismatixConfig(baseUrl = "https://api.prismatix.ai/functions/v1/router"),
            distributionConfig = playConfig,
            connectionFactory = { url ->
                MockHttpURLConnection(
                    url = url,
                    responseStatusCode = 401,
                    responseBody = errorJson,
                    isError = true,
                )
            }
        )

        val request = PrismatixChatRequest(
            conversationId = "11111111-2222-3333-4444-555555555555",
            query = "Hello",
        )

        val events = client.streamChat(request).toList()
        assertEquals(1, events.size)

        val error = events[0] as PrismatixStreamEvent.Error
        assertEquals(401, error.statusCode)
        assertEquals("Unauthorized: Invalid or expired token", error.message)
    }

    @Test
    fun handles429RateLimitWithRetryAfterHeader() = runBlocking {
        val errorJson = """{"error":"Rate limit exceeded"}"""
        val headers = mapOf("Retry-After" to "12")

        val client = HttpPrismatixClient(
            config = PrismatixConfig(baseUrl = "https://api.prismatix.ai/functions/v1/router"),
            distributionConfig = playConfig,
            connectionFactory = { url ->
                MockHttpURLConnection(
                    url = url,
                    responseStatusCode = 429,
                    responseBody = errorJson,
                    responseHeaders = headers,
                    isError = true,
                )
            }
        )

        val request = PrismatixChatRequest(
            conversationId = "11111111-2222-3333-4444-555555555555",
            query = "Hello",
        )

        val events = client.streamChat(request).toList()
        assertEquals(1, events.size)

        val error = events[0] as PrismatixStreamEvent.Error
        assertEquals(429, error.statusCode)
        assertEquals(12, error.retryAfterSeconds)
    }
}
