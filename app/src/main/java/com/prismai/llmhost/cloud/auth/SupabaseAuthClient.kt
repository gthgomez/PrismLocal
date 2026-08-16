package com.prismai.llmhost.cloud.auth

import com.prismai.llmhost.cloud.prismatix.EndpointTrustException
import com.prismai.llmhost.cloud.prismatix.PrismatixConfig
import com.prismai.llmhost.work.DistributionConfig
import com.prismai.llmhost.work.DistributionConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

interface SupabaseAuthClient {
    suspend fun signInWithPassword(email: String, password: String): Result<SupabaseAuthSession>
    suspend fun signUpWithPassword(email: String, password: String): Result<SupabaseAuthSession>
    suspend fun refreshSession(refreshToken: String): Result<SupabaseAuthSession>
}

class HttpSupabaseAuthClient(
    private val authBaseUrl: String = DEFAULT_AUTH_URL,
    private val apiKey: String = DEFAULT_ANON_KEY,
    private val distributionConfig: DistributionConfig = DistributionConfigProvider,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : SupabaseAuthClient {

    companion object {
        const val DEFAULT_AUTH_URL = "https://api.prismatix.ai/auth/v1"
        const val DEFAULT_ANON_KEY = "anon-key-placeholder"
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
                val responseText = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(responseText)
                val session = SupabaseAuthSession.fromAuthResponse(json)
                return Result.success(session)
            } else {
                val errorText = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
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
