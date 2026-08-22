package com.prismai.llmhost.cloud.auth

import com.prismai.llmhost.BuildConfig

object SupabaseAuthConfig {
    const val DEFAULT_AUTH_URL = "https://api.prismatix.ai/auth/v1"

    val authBaseUrl: String
        get() = BuildConfig.SUPABASE_AUTH_URL.ifBlank { DEFAULT_AUTH_URL }

    val anonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY

    fun requireAnonKey(): String {
        val key = anonKey
        if (key.isBlank()) {
            throw IllegalStateException(
                "Supabase anon key is not configured. Add supabase.anon.key to local.properties " +
                    "or set LLMHOST_SUPABASE_ANON_KEY as a Gradle property / environment variable.",
            )
        }
        return key
    }
}
