package com.prismai.llmhost.cloud.auth

import com.prismai.llmhost.work.DistributionConfig
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

class SupabaseAuthClientTest {

    private val playConfig = object : DistributionConfig {
        override val developerWorkMode: Boolean = false
        override val localProcessExecutionAvailable: Boolean = false
        override val babelHostExecutionAvailable: Boolean = false
    }

    private class MockHttpURLConnection(
        url: URL,
        private val responseStatusCode: Int,
        private val responseBody: String,
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
        override fun getResponseMessage(): String = if (responseStatusCode in 200..299) "OK" else "Error"

        override fun setRequestProperty(key: String, value: String) {
            capturedRequestProperties[key] = value
        }

        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
    }

    @Test
    fun signInWithPasswordSuccessReturnsSession() = runBlocking {
        val authSuccessResponse = """
            {
              "access_token": "mock-jwt-access-token",
              "token_type": "bearer",
              "expires_in": 3600,
              "refresh_token": "mock-refresh-token",
              "user": {
                "id": "11111111-2222-3333-4444-555555555555",
                "email": "user@example.com"
              }
            }
        """.trimIndent()

        var capturedConn: MockHttpURLConnection? = null
        val client = HttpSupabaseAuthClient(
            authBaseUrl = "https://api.prismatix.ai/auth/v1",
            apiKey = "test-anon-key",
            distributionConfig = playConfig,
            connectionFactory = { url ->
                MockHttpURLConnection(
                    url = url,
                    responseStatusCode = 200,
                    responseBody = authSuccessResponse,
                ).also { capturedConn = it }
            }
        )

        val result = client.signInWithPassword("user@example.com", "password123")
        assertTrue(result.isSuccess)

        val session = result.getOrThrow()
        assertEquals("mock-jwt-access-token", session.accessToken)
        assertEquals("mock-refresh-token", session.refreshToken)
        assertEquals("11111111-2222-3333-4444-555555555555", session.userId)
        assertEquals("user@example.com", session.email)
        assertTrue(session.expiresAtEpochMs > System.currentTimeMillis())

        val writtenPayload = capturedConn?.capturedOutputStream?.toString("UTF-8") ?: ""
        assertTrue(writtenPayload.contains("\"email\":\"user@example.com\""))
    }

    @Test
    fun rejectsUntrustedAuthEndpointOnPlayDistribution() = runBlocking {
        val client = HttpSupabaseAuthClient(
            authBaseUrl = "https://untrusted-host.com/auth/v1",
            apiKey = "test-anon-key",
            distributionConfig = playConfig,
        )

        val result = client.signInWithPassword("user@example.com", "password123")
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception?.message?.contains("Play distribution rejects unpinned host") == true)
    }

    @Test
    fun handlesInvalidCredentialsError() = runBlocking {
        val errorResponse = """
            {
              "error": "invalid_grant",
              "error_description": "Invalid login credentials"
            }
        """.trimIndent()

        val client = HttpSupabaseAuthClient(
            authBaseUrl = "https://api.prismatix.ai/auth/v1",
            apiKey = "test-anon-key",
            distributionConfig = playConfig,
            connectionFactory = { url ->
                MockHttpURLConnection(
                    url = url,
                    responseStatusCode = 400,
                    responseBody = errorResponse,
                    isError = true,
                )
            }
        )

        val result = client.signInWithPassword("user@example.com", "wrongpassword")
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception?.message?.contains("Invalid login credentials") == true)
    }
}
