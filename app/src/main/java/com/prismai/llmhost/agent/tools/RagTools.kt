package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class RagTools(
    private val ragManager: RagManager,
    private val vectorStore: VectorStore,
) {
    suspend fun ingestDocument(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return AgentToolResult(call = call, success = false, summary = "Confirmation required for ingest_document", errorCode = AgentToolErrorCode.CONFIRMATION_REQUIRED)
        val documentId = call.arguments.optString("document_id").trim().take(120)
        val title = call.arguments.optString("title", documentId).trim().take(200)
        val text = call.arguments.optString("text").trim()
        if (documentId.isBlank() || text.isBlank()) {
            return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "document_id and text are required")
        }
        val result = try {
            ragManager.ingestDocumentWithResult(documentId, title, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Ingestion failed: ${(e.message ?: e::class.java.simpleName).compactForAgent(160)}")
        }
        if (result.tooLarge) {
            return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT,
                "Document is too large to index in one pass; split it into smaller documents",
                JSONObject().put("stored", false).put("document_id", documentId))
        }
        if (result.encoderChanged) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "The active model changed while embedding; nothing was indexed. Retry with a stable model.",
                JSONObject().put("stored", false).put("document_id", documentId))
        }
        if (result.totalChunks == 0) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "No chunks produced from document",
                JSONObject().put("stored", false).put("document_id", documentId))
        }
        if (!result.committed) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Could not embed all ${result.totalChunks} chunks from '$title' (${result.failedCount} failed); the previous index was preserved",
                JSONObject()
                    .put("stored", false)
                    .put("preserved_previous", result.preservedPrevious)
                    .put("failed_count", result.failedCount)
                    .put("document_id", documentId))
        }
        return toolSuccess(call, "Ingested ${result.embeddedCount} chunks from '$title'",
            JSONObject().put("stored", true).put("chunk_count", result.embeddedCount).put("document_id", documentId))
    }

    suspend fun searchDocuments(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").trim().take(500)
        val topK = call.arguments.optInt("top_k", 5).coerceIn(1, 20)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Query is required")
        val results = try {
            ragManager.query(query, topK = topK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Search failed: ${(e.message ?: e::class.java.simpleName).compactForAgent(160)}")
        }
        val resultsArray = JSONArray()
        results.forEach { (chunk, score) ->
            resultsArray.put(JSONObject()
                .put("document_id", chunk.documentId)
                .put("chunk_index", chunk.chunkIndex)
                .put("text", chunk.text.take(500))
                .put("score", String.format(Locale.US, "%.3f", score).toDouble()))
        }
        return toolSuccess(call, "Found ${results.size} relevant chunks",
            JSONObject().put("results", resultsArray).put("untrusted_data", true))
    }

    suspend fun listDocuments(call: AgentToolCall): AgentToolResult {
        val documents = try {
            vectorStore.getDocumentSummaries()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Failed to list documents: ${(e.message ?: e::class.java.simpleName).compactForAgent(160)}")
        }
        val docsArray = JSONArray()
        documents.forEach { doc ->
            docsArray.put(JSONObject()
                .put("document_id", doc.documentId)
                .put("chunk_count", doc.storedChunkCount)
                .put("searchable_chunk_count", doc.searchableChunkCount))
        }
        return toolSuccess(call, "${documents.size} documents in knowledge base",
            JSONObject().put("count", documents.size).put("documents", docsArray))
    }

    suspend fun deleteDocument(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return AgentToolResult(call = call, success = false, summary = "Confirmation required for delete_document", errorCode = AgentToolErrorCode.CONFIRMATION_REQUIRED)
        val documentId = call.arguments.optString("document_id").trim().take(120)
        if (documentId.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "document_id is required")
        val removed = try {
            ragManager.deleteDocument(documentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Deletion failed: ${(e.message ?: e::class.java.simpleName).compactForAgent(160)}")
        }
        if (removed == 0) {
            return toolFailure(call, AgentToolErrorCode.NOT_FOUND,
                "No chunks found for document '$documentId'",
                JSONObject().put("deleted", false).put("document_id", documentId))
        }
        return toolSuccess(call, "Deleted document '$documentId' ($removed chunks removed)",
            JSONObject().put("deleted", true).put("document_id", documentId).put("chunks_removed", removed))
    }
}
