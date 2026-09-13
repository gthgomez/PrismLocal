package com.prismai.llmhost.cloud.prismatix

import com.prismai.llmhost.work.DistributionConfig
import com.prismai.llmhost.work.DistributionConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

interface PrismatixClient {
    fun streamChat(
        request: PrismatixChatRequest,
        authToken: String? = null,
    ): Flow<PrismatixStreamEvent>
}

class HttpPrismatixClient(
    private val config: PrismatixConfig = PrismatixConfig(),
    private val distributionConfig: DistributionConfig = DistributionConfigProvider,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : PrismatixClient {

    override fun streamChat(
        request: PrismatixChatRequest,
        authToken: String?,
    ): Flow<PrismatixStreamEvent> = flow {
        // Enforce endpoint trust boundary before opening connection or sending auth tokens
        try {
            PrismatixConfig.validateEndpoint(config.baseUrl, distributionConfig)
        } catch (e: EndpointTrustException) {
            emit(
                PrismatixStreamEvent.Error(
                    statusCode = 403,
                    message = "Endpoint Trust Violation: ${e.message}",
                )
            )
            return@flow
        }

        val url = URL(config.baseUrl)
        val connection = connectionFactory(url)

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            // Never auto-follow redirects while carrying an Authorization header: a
            // 3xx target could be an attacker-controlled host that would receive the JWT.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs

            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            if (!authToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }

            // Write request body
            val requestBody = request.toJsonString()
            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Extract Prismatix routing and cost metadata headers
                val routerModel = connection.getHeaderField("X-Router-Model")
                val provider = connection.getHeaderField("X-Provider")
                val costStr = connection.getHeaderField("X-Cost-Estimate-USD")
                val rationale = connection.getHeaderField("X-Router-Rationale")
                val complexityStr = connection.getHeaderField("X-Complexity-Score")

                val costEstimate = costStr?.toDoubleOrNull()
                val complexity = complexityStr?.toDoubleOrNull()

                emit(
                    PrismatixStreamEvent.Metadata(
                        routerModel = routerModel,
                        provider = provider,
                        costEstimateUsd = costEstimate,
                        rationale = rationale,
                        complexityScore = complexity,
                    )
                )

                // Delegate SSE decoding to the shared, unit-tested parser so the
                // streaming and error semantics live in exactly one place. The parser
                // is inline, so `emit` is called incrementally per frame without buffering.
                PrismatixSseParser.parseStream(connection.inputStream) { event ->
                    emit(event)
                }
            } else {
                // Non-200 HTTP response
                val errorStream: InputStream? = connection.errorStream
                val errorMessage = errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: "HTTP $responseCode ${connection.responseMessage}"

                val parsedErrorMessage = try {
                    JSONObject(errorMessage).optString("error", errorMessage)
                } catch (_: Exception) {
                    errorMessage
                }

                val retryAfterSeconds = connection.getHeaderField("Retry-After")?.toIntOrNull()

                emit(
                    PrismatixStreamEvent.Error(
                        statusCode = responseCode,
                        message = parsedErrorMessage,
                        retryAfterSeconds = retryAfterSeconds,
                    )
                )
            }
        } catch (e: Exception) {
            emit(
                PrismatixStreamEvent.Error(
                    statusCode = -1,
                    message = e.message ?: "Network transport error",
                )
            )
        } finally {
            try {
                // Always release the connection, even if the request body write or
                // any setRequestProperty call threw before the connection was "used".
                connection.disconnect()
            } catch (_: Exception) {
                // Ignore disconnect cleanup errors
            }
        }
    }.flowOn(Dispatchers.IO)
}
