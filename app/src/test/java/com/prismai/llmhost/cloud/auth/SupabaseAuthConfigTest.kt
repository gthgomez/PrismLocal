package com.prismai.llmhost.cloud.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseAuthConfigTest {

    @Test
    fun requireAnonKeyFailsClosedWhenBuildConfigKeyMissing() {
        if (SupabaseAuthConfig.anonKey.isNotBlank()) {
            // Local developer machine may have local.properties configured.
            return
        }

        val failure = runCatching { SupabaseAuthConfig.requireAnonKey() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("not configured") == true)
    }

    @Test
    fun authBaseUrlFallsBackToDefaultWhenBlank() {
        val url = SupabaseAuthConfig.authBaseUrl
        assertTrue(url.isNotBlank())
        assertEquals(SupabaseAuthConfig.DEFAULT_AUTH_URL, url)
    }
}
