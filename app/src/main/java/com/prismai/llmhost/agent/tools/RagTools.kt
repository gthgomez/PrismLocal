package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

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
        val chunkCount = try {
            ragManager.ingestDocument(documentId, title, text)
        } catch (e: Exception) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Ingestion failed: ${(e.message ?: e::class.java.simpleName).compactForAgent(160)}")
        }
        if (chunkCount == 0) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "No chunks produced from document")
        }
        return toolSuccess(call, "Ingested $chunkCount chunks from '$title'",
            JSONObject().put("stored", true).put("chunk_count", chunkCount).put("document_id", documentId))
    }

    suspend fun searchDocuments(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").trim().take(500)
        val topK = call.arguments.optInt("top_k", 5).coerceIn(1, 20)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Query is required")
        val results = try {
            ragManager.query(query, topK = topK)
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
        val allChunks = try {
            vectorStore.getAllChunks()
        } catch (e: Exception) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "Failed to list documents: ${(e.message ?: e::class.java.simpleName).compactForAgent(160)}")
        }
        val docMap = mutableMapOf<String, Int>()
        allChunks.forEach { chunk -> docMap[chunk.documentId] = (docMap[chunk.documentId] ?: 0) + 1 }
        val docsArray = JSONArray()
        docMap.forEach { (docId, count) ->
            docsArray.put(JSONObject().put("document_id", docId).put("chunk_count", count))
        }
        return toolSuccess(call, "${docMap.size} documents in knowledge base",
            JSONObject().put("count", docMap.size).put("documents", docsArray))
    }

    suspend fun deleteDocument(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return AgentToolResult(call = call, success = false, summary = "Confirmation required for delete_document", errorCode = AgentToolErrorCode.CONFIRMATION_REQUIRED)
        val documentId = call.arguments.optString("document_id").trim().take(120)
        if (documentId.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "document_id is required")
        val removed = try {
            ragManager.deleteDocument(documentId)
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
