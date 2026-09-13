package com.prismai.llmhost.cloud.auth

import org.json.JSONObject

/**
 * An authenticated Supabase session for Prismatix Cloud Chat.
 */
data class SupabaseAuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
    val userId: String,
    val email: String? = null,
) {
    fun isExpired(bufferSeconds: Long = 60): Boolean {
        return System.currentTimeMillis() >= (expiresAtEpochMs - bufferSeconds * 1000L)
    }

    fun toJsonString(): String {
        return JSONObject().apply {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_at", expiresAtEpochMs)
            put("user_id", userId)
            put("email", email)
        }.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): SupabaseAuthSession? {
            return try {
                val obj = JSONObject(jsonStr)
                SupabaseAuthSession(
                    accessToken = obj.getString("access_token"),
                    refreshToken = obj.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresAtEpochMs = obj.getLong("expires_at"),
                    userId = obj.getString("user_id"),
                    email = obj.optString("email").takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }

        fun fromAuthResponse(jsonObj: JSONObject): SupabaseAuthOutcome {
            val accessToken = jsonObj.optString("access_token").takeIf { it.isNotBlank() }
                ?: return SupabaseAuthOutcome.ConfirmationRequired(
                    jsonObj.optJSONObject("user")?.optString("email"),
                )
            val refreshToken = jsonObj.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiresInSeconds = jsonObj.optLong("expires_in", 3600L)
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
            val userObj = jsonObj.optJSONObject("user")
            val userId = userObj?.optString("id") ?: "unknown-user"
            val email = userObj?.optString("email")

            return SupabaseAuthOutcome.SessionCreated(
                SupabaseAuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtEpochMs = expiresAt,
                    userId = userId,
                    email = email,
                )
            )
        }
    }
}

/**
 * Outcome of parsing a Supabase auth response.
 *
 * Supabase returns a user without a session when email confirmation is enabled,
 * so a created-but-unconfirmed account must be distinguished from a session.
 */
sealed class SupabaseAuthOutcome {
    data class SessionCreated(val session: SupabaseAuthSession) : SupabaseAuthOutcome()

    data class ConfirmationRequired(val email: String?) : SupabaseAuthOutcome()
}
