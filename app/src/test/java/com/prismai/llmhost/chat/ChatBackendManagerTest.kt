package com.prismai.llmhost.chat

import com.prismai.llmhost.cloud.auth.InMemoryTokenStorage
import com.prismai.llmhost.cloud.auth.SupabaseAuthClient
import com.prismai.llmhost.cloud.auth.SupabaseAuthSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackendManagerTest {

    private class MockAuthClient(
        var refreshResult: Result<SupabaseAuthSession> = Result.failure(Exception("Not mocked")),
    ) : SupabaseAuthClient {
        override suspend fun signInWithPassword(email: String, password: String): Result<SupabaseAuthSession> =
            Result.failure(Exception("Unused"))

        override suspend fun signUpWithPassword(email: String, password: String): Result<SupabaseAuthSession> =
            Result.failure(Exception("Unused"))

        override suspend fun refreshSession(refreshToken: String): Result<SupabaseAuthSession> = refreshResult
    }

    @Test
    fun defaultBackendIsLocalLlamaAndRequiresNoAccount() {
        val storage = InMemoryTokenStorage()
        val authClient = MockAuthClient()
        val manager = ChatBackendManager(storage, authClient)

        assertEquals(ChatBackend.LOCAL_LLAMA, manager.activeBackend.value)
        assertNull(manager.authSession.value)
    }

    @Test
    fun selectingCloudWithoutSessionFails() {
        val storage = InMemoryTokenStorage()
        val authClient = MockAuthClient()
        val manager = ChatBackendManager(storage, authClient)

        val selected = manager.selectBackend(ChatBackend.PRISMATIX_CLOUD)
        assertFalse("Cannot select PRISMATIX_CLOUD without an active session", selected)
        assertEquals(ChatBackend.LOCAL_LLAMA, manager.activeBackend.value)
    }

    @Test
    fun selectingCloudWithSessionSucceedsAndSignOutResetsToLocal() {
        val storage = InMemoryTokenStorage()
        val authClient = MockAuthClient()
        val manager = ChatBackendManager(storage, authClient)

        val session = SupabaseAuthSession(
            accessToken = "valid-token",
            refreshToken = "refresh-token",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-123",
            email = "user@example.com",
        )

        manager.onSessionAuthenticated(session)
        assertTrue(manager.selectBackend(ChatBackend.PRISMATIX_CLOUD))
        assertEquals(ChatBackend.PRISMATIX_CLOUD, manager.activeBackend.value)

        // Sign out must purge session and reset backend to LOCAL_LLAMA
        manager.signOut()
        assertEquals(ChatBackend.LOCAL_LLAMA, manager.activeBackend.value)
        assertNull(manager.authSession.value)
        assertNull(storage.loadSession())
    }

    @Test
    fun tokenAutoRefreshOnExpiry() = runBlocking {
        val storage = InMemoryTokenStorage()
        val authClient = MockAuthClient()
        val manager = ChatBackendManager(storage, authClient)

        // Expired session
        val expiredSession = SupabaseAuthSession(
            accessToken = "expired-token",
            refreshToken = "refresh-token-123",
            expiresAtEpochMs = System.currentTimeMillis() - 10_000L,
            userId = "user-123",
        )
        manager.onSessionAuthenticated(expiredSession)

        val refreshedSession = SupabaseAuthSession(
            accessToken = "new-fresh-token",
            refreshToken = "new-refresh-token",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-123",
        )
        authClient.refreshResult = Result.success(refreshedSession)

        val token = manager.getValidAccessToken()
        assertEquals("new-fresh-token", token)
        assertEquals("new-fresh-token", manager.authSession.value?.accessToken)
    }
}
