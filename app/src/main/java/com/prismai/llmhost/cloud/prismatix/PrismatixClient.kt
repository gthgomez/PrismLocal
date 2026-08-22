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
        var isConnected = false

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
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

            isConnected = true
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

                // Parse stream line-by-line with protocol error on malformed data
                val reader = connection.inputStream.bufferedReader(StandardCharsets.UTF_8)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line?.trim() ?: continue
                    if (currentLine.isEmpty() || currentLine.startsWith(":")) continue

                    if (currentLine.startsWith("data:")) {
                        val dataContent = currentLine.substring(5).trim()
                        if (dataContent == "[DONE]") {
                            emit(PrismatixStreamEvent.Done)
                            break
                        }

                        val json = try {
                            JSONObject(dataContent)
                        } catch (e: Exception) {
                            emit(
                                PrismatixStreamEvent.Error(
                                    statusCode = -2,
                                    message = "Protocol Error: Malformed SSE data frame '$dataContent'",
                                )
                            )
                            break
                        }

                        val type = json.optString("type")
                        if (type == "content_block_delta") {
                            val delta = json.optJSONObject("delta")?.optString("text")
                            if (!delta.isNullOrEmpty()) {
                                emit(PrismatixStreamEvent.Delta(delta))
                            }
                        } else if (json.has("error")) {
                            emit(
                                PrismatixStreamEvent.Error(
                                    statusCode = responseCode,
                                    message = json.optString("error", "Server error"),
                                )
                            )
                            break
                        }
                    }
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
            if (isConnected) {
                try {
                    connection.disconnect()
                } catch (_: Exception) {
                    // Ignore disconnect cleanup errors
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
