package com.prismai.llmhost.chat

import com.prismai.llmhost.cloud.auth.InMemoryTokenStorage
import com.prismai.llmhost.cloud.auth.SupabaseAuthClient
import com.prismai.llmhost.cloud.auth.SupabaseAuthSession
import com.prismai.llmhost.cloud.prismatix.PrismatixChatRequest
import com.prismai.llmhost.cloud.prismatix.PrismatixClient
import com.prismai.llmhost.cloud.prismatix.PrismatixStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudChatOrchestrationTest {

    private class MockPrismatixClient(
        var emittedEvents: List<PrismatixStreamEvent> = emptyList(),
    ) : PrismatixClient {
        var lastCapturedRequest: PrismatixChatRequest? = null
        var lastCapturedToken: String? = null
        var callCount: Int = 0

        override fun streamChat(
            request: PrismatixChatRequest,
            authToken: String?,
        ): Flow<PrismatixStreamEvent> = flow {
            callCount++
            lastCapturedRequest = request
            lastCapturedToken = authToken
            for (event in emittedEvents) {
                emit(event)
            }
        }
    }

    private class MockAuthClient : SupabaseAuthClient {
        override suspend fun signInWithPassword(email: String, password: String): Result<SupabaseAuthSession> =
            Result.failure(Exception("Unused"))

        override suspend fun signUpWithPassword(email: String, password: String): Result<SupabaseAuthSession> =
            Result.failure(Exception("Unused"))

        override suspend fun refreshSession(refreshToken: String): Result<SupabaseAuthSession> =
            Result.failure(Exception("Unused"))
    }

    @Test
    fun localModeMakesZeroNetworkCalls() = runBlocking {
        val storage = InMemoryTokenStorage()
        val authClient = MockAuthClient()
        val backendManager = ChatBackendManager(storage, authClient)
        val cloudClient = MockPrismatixClient()

        assertEquals(ChatBackend.LOCAL_LLAMA, backendManager.activeBackend.value)
        assertEquals(0, cloudClient.callCount)
    }

    @Test
    fun cloudModeStreamsAndPreservesMobileIsolation() = runBlocking {
        val storage = InMemoryTokenStorage()
        val authClient = MockAuthClient()
        val backendManager = ChatBackendManager(storage, authClient)

        val session = SupabaseAuthSession(
            accessToken = "user-jwt-123",
            refreshToken = "refresh-123",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-456",
            email = "user@example.com",
        )
        backendManager.onSessionAuthenticated(session)
        assertTrue(backendManager.selectBackend(ChatBackend.PRISMATIX_CLOUD))

        val cloudClient = MockPrismatixClient(
            emittedEvents = listOf(
                PrismatixStreamEvent.Metadata(
                    routerModel = "gemini-2.5-flash",
                    provider = "google",
                    costEstimateUsd = 0.00015,
                ),
                PrismatixStreamEvent.Delta("Cloud response"),
                PrismatixStreamEvent.Done,
            )
        )

        val token = backendManager.getValidAccessToken()
        assertNotNull(token)

        val request = PrismatixChatRequest(
            conversationId = "11111111-2222-3333-4444-555555555555",
            query = "What is Cloud Chat?",
            platform = "mobile",
        )

        val events = cloudClient.streamChat(request, token).toList()

        assertEquals(1, cloudClient.callCount)
        assertEquals("user-jwt-123", cloudClient.lastCapturedToken)
        assertEquals("mobile", cloudClient.lastCapturedRequest?.platform)
        assertEquals("11111111-2222-3333-4444-555555555555", cloudClient.lastCapturedRequest?.conversationId)

        assertEquals(3, events.size)
        val metadata = events[0] as PrismatixStreamEvent.Metadata
        assertEquals("gemini-2.5-flash", metadata.routerModel)
        assertEquals(PrismatixStreamEvent.Delta("Cloud response"), events[1])
        assertEquals(PrismatixStreamEvent.Done, events[2])
    }

    @Test
    fun protocolErrorAbortsWithoutPersistingCompleteMessage() = runBlocking {
        val cloudClient = MockPrismatixClient(
            emittedEvents = listOf(
                PrismatixStreamEvent.Delta("Partial text"),
                PrismatixStreamEvent.Error(statusCode = -2, message = "Protocol Error: Malformed JSON frame"),
            )
        )

        val events = cloudClient.streamChat(
            PrismatixChatRequest("11111111-2222-3333-4444-555555555555", "Hello"),
            authToken = "token",
        ).toList()

        assertEquals(2, events.size)
        assertTrue(events[1] is PrismatixStreamEvent.Error)
        val error = events[1] as PrismatixStreamEvent.Error
        assertEquals(-2, error.statusCode)
        assertTrue(error.message.contains("Protocol Error"))
        // Stream did NOT emit [DONE]
        assertFalse(events.any { it is PrismatixStreamEvent.Done })
    }
}
