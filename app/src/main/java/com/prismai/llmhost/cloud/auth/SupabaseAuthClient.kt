package com.prismai.llmhost.cloud.auth

import com.prismai.llmhost.cloud.prismatix.EndpointTrustException
import com.prismai.llmhost.cloud.prismatix.PrismatixConfig
import com.prismai.llmhost.work.DistributionConfig
import com.prismai.llmhost.work.DistributionConfigProvider
import com.prismai.llmhost.util.readTextBounded
import com.prismai.llmhost.util.readTextTruncated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Raised when Supabase creates a user but returns no session because the account
 * must confirm its email address before it can sign in.
 */
class EmailConfirmationRequiredException(email: String?) : Exception(
    "Email confirmation required" +
        (email?.takeIf { it.isNotBlank() }?.let { " for $it" } ?: "") +
        ": confirm your email before signing in",
)

interface SupabaseAuthClient {
    suspend fun signInWithPassword(email: String, password: String): Result<SupabaseAuthSession>
    suspend fun signUpWithPassword(email: String, password: String): Result<SupabaseAuthSession>
    suspend fun refreshSession(refreshToken: String): Result<SupabaseAuthSession>
}

class HttpSupabaseAuthClient(
    private val authBaseUrl: String = SupabaseAuthConfig.authBaseUrl,
    private val apiKey: String,
    private val distributionConfig: DistributionConfig = DistributionConfigProvider,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : SupabaseAuthClient {

    companion object {
        fun createDefault(
            distributionConfig: DistributionConfig = DistributionConfigProvider,
            connectionFactory: (URL) -> HttpURLConnection = { url ->
                url.openConnection() as HttpURLConnection
            },
        ): HttpSupabaseAuthClient = HttpSupabaseAuthClient(
            apiKey = SupabaseAuthConfig.requireAnonKey(),
            distributionConfig = distributionConfig,
            connectionFactory = connectionFactory,
        )
    }

    override suspend fun signInWithPassword(email: String, password: String): Result<SupabaseAuthSession> =
        withContext(Dispatchers.IO) {
            postAuthRequest(
                endpointUrl = "$authBaseUrl/token?grant_type=password",
                requestBody = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }.toString(),
            )
        }

    override suspend fun signUpWithPassword(email: String, password: String): Result<SupabaseAuthSession> =
        withContext(Dispatchers.IO) {
            postAuthRequest(
                endpointUrl = "$authBaseUrl/signup",
                requestBody = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }.toString(),
            )
        }

    override suspend fun refreshSession(refreshToken: String): Result<SupabaseAuthSession> =
        withContext(Dispatchers.IO) {
            postAuthRequest(
                endpointUrl = "$authBaseUrl/token?grant_type=refresh_token",
                requestBody = JSONObject().apply {
                    put("refresh_token", refreshToken)
                }.toString(),
            )
        }

    private fun postAuthRequest(endpointUrl: String, requestBody: String): Result<SupabaseAuthSession> {
        try {
            PrismatixConfig.validateEndpoint(endpointUrl, distributionConfig)
        } catch (e: EndpointTrustException) {
            return Result.failure(e)
        }

        val url = URL(endpointUrl)
        val connection = connectionFactory(url)

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            // Auth requests carry credentials/tokens; never follow redirects that
            // could exfiltrate them to an unvalidated host. 3xx is treated as an error.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000

            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("apikey", apiKey)

            connection.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val responseText = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readTextBounded() }
                val json = JSONObject(responseText)
                return when (val outcome = SupabaseAuthSession.fromAuthResponse(json)) {
                    is SupabaseAuthOutcome.SessionCreated -> Result.success(outcome.session)
                    is SupabaseAuthOutcome.ConfirmationRequired ->
                        Result.failure(EmailConfirmationRequiredException(outcome.email))
                }
            } else {
                val errorText = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readTextTruncated() }
                    ?: "HTTP $code ${connection.responseMessage}"
                val errorMsg = try {
                    val errJson = JSONObject(errorText)
                    errJson.optString("error_description", errJson.optString("msg", errorText))
                } catch (_: Exception) {
                    errorText
                }
                return Result.failure(Exception("Auth failed ($code): $errorMsg"))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}
