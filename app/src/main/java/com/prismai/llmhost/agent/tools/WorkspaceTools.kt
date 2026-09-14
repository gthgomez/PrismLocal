package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import com.prismai.llmhost.tools.AgentToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Agent tools for safe, bounded workspace file system operations.
 * Enforces canonical path checking to prevent path traversal attacks.
 */
class WorkspaceTools(
    private val rootDir: File,
) {
    init {
        // The root must exist before any list/read/search call. The production
        // root is created by [defaultRoot]; this also covers direct construction
        // (e.g. unit tests) with a not-yet-created directory.
        if (!rootDir.isDirectory) {
            rootDir.mkdirs()
        }
    }

    companion object {
        /** Dedicated subdirectory of `filesDir` that the agent workspace tools may read. */
        const val WORKSPACE_DIR_NAME = "agent-workspace"

        /** Boundary note written once into a freshly created workspace root. */
        const val WORKSPACE_README_NAME = "README.md"

        internal val WORKSPACE_README = """
            # Agent workspace

            This directory is the only file tree the agent workspace tools
            (`list_workspace_files`, `read_workspace_file`, `search_workspace_files`)
            are allowed to read. It is intentionally isolated from the rest of the
            app's private storage.

            Chats, agent traces, benchmark history, model files, exports, and the
            security audit log live outside this directory and are not reachable
            from here; dedicated chat/model/benchmark tools cover that data.

            Only files inside this directory are visible to the agent workspace
            tools.
        """.trimIndent().trim()

        private const val MAX_READ_CHARS = 16_000
        private const val MAX_SEARCH_RESULTS = 25

        /**
         * Resolve the dedicated agent workspace root under [filesDir], creating it
         * (and its boundary README) when absent.
         *
         * Migration (D6): the legacy workspace root was [filesDir] itself, which
         * exposed `chats/`, `agent_traces/`, `agent_exports/`, `models/`,
         * `hf-downloads/` and `security_audit.jsonl` to SAFE agent reads.
         * [WorkspaceTools] is read-only and has never had a write tool, so no
         * workspace-authored files exist to migrate. This method therefore does
         * **not** move or copy existing files: app-internal data stays in place
         * (still reachable through dedicated tools such as `search_chats`) and the
         * new root starts empty apart from the README. Idempotent and safe to call
         * on every service start.
         */
        fun defaultRoot(filesDir: File): File {
            val root = File(filesDir, WORKSPACE_DIR_NAME)
            if (!root.isDirectory) {
                root.mkdirs()
            }
            val readme = File(root, WORKSPACE_README_NAME)
            if (!readme.exists()) {
                runCatching { readme.writeText(WORKSPACE_README, Charsets.UTF_8) }
            }
            return root
        }
    }

    /**
     * Verify that [file] resolves inside [rootDir] using canonical paths.
     */
    fun isPathInsideWorkspace(file: File): Boolean {
        return try {
            file.canonicalFile.toPath().normalize().startsWith(rootDir.canonicalFile.toPath().normalize())
        } catch (_: Exception) {
            false
        }
    }

    /**
     * List files and directories under a workspace relative path.
     */
    fun listWorkspaceFiles(call: AgentToolCall): AgentToolResult {
        val relativePath = call.arguments.optString("path", "").trim()
        val targetFile = if (relativePath.isBlank()) rootDir else File(rootDir, relativePath)

        if (!isPathInsideWorkspace(targetFile)) {
            return toolFailure(
                call,
                AgentToolErrorCode.INVALID_ARGUMENT,
                "Access denied: path '${relativePath}' resolves outside workspace boundary.",
            )
        }

        if (!targetFile.exists()) {
            return toolFailure(
                call,
                AgentToolErrorCode.INVALID_ARGUMENT,
                "Directory does not exist: $relativePath",
            )
        }

        if (!targetFile.isDirectory) {
            return toolFailure(
                call,
                AgentToolErrorCode.INVALID_ARGUMENT,
                "Path is a file, not a directory: $relativePath",
            )
        }

        val entries = targetFile.listFiles() ?: emptyArray()
        val filesArray = JSONArray()

        entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) })).forEach { entry ->
            val rel = entry.relativeTo(rootDir).path.replace('\\', '/')
            filesArray.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("relative_path", rel)
                    .put("is_directory", entry.isDirectory)
                    .put("size_bytes", if (entry.isFile) entry.length() else JSONObject.NULL)
                    .put("last_modified", entry.lastModified())
            )
        }

        return toolSuccess(
            call,
            "Listed ${entries.size} items in '${relativePath.ifBlank { "/" }}'",
            JSONObject()
                .put("path", relativePath.ifBlank { "/" })
                .put("count", entries.size)
                .put("items", filesArray),
        )
    }

    /**
     * Read the text contents of a file up to [MAX_READ_CHARS].
     */
    fun readWorkspaceFile(call: AgentToolCall): AgentToolResult {
        val relativePath = call.arguments.optString("path").trim()
        if (relativePath.isBlank()) {
            return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "path parameter is required")
        }

        val targetFile = File(rootDir, relativePath)
        if (!isPathInsideWorkspace(targetFile)) {
            return toolFailure(
                call,
                AgentToolErrorCode.INVALID_ARGUMENT,
                "Access denied: path '$relativePath' resolves outside workspace boundary.",
            )
        }

        if (!targetFile.exists() || !targetFile.isFile) {
            return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "File not found: $relativePath")
        }

        return runCatching {
            val content = targetFile.readText(Charsets.UTF_8)

            // Binary file check (presence of null bytes)
            if (content.take(1000).contains('\u0000')) {
                return toolFailure(
                    call,
                    AgentToolErrorCode.INVALID_ARGUMENT,
                    "Cannot read binary file '$relativePath'",
                )
            }

            val isTruncated = content.length > MAX_READ_CHARS
            val safeContent = if (isTruncated) {
                content.take(MAX_READ_CHARS) + "\n\n[Truncated: file exceeds $MAX_READ_CHARS character limit]"
            } else {
                content
            }

            toolSuccess(
                call,
                "Read file '$relativePath' (${safeContent.length} chars)",
                JSONObject()
                    .put("path", relativePath)
                    .put("content", safeContent)
                    .put("is_truncated", isTruncated)
                    .put("file_size_bytes", targetFile.length())
                    .put("untrusted_data", true),
            )
        }.getOrElse { e ->
            toolFailure(call, AgentToolErrorCode.FAILED, "Failed to read file '$relativePath': ${e.message}")
        }
    }

    /**
     * Perform keyword search across text files inside workspace.
     */
    fun searchWorkspaceFiles(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").trim()
        if (query.isBlank()) {
            return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "query parameter is required")
        }

        val resultsArray = JSONArray()
        var matchCount = 0

        rootDir.walkTopDown()
            .filter { it.isFile && it.length() <= 1_000_000L } // Skip files larger than 1MB
            .take(200)
            .forEach { file ->
                if (matchCount >= MAX_SEARCH_RESULTS) return@forEach
                runCatching {
                    val text = file.readText(Charsets.UTF_8)
                    if (!text.take(500).contains('\u0000') && text.contains(query, ignoreCase = true)) {
                        val relPath = file.relativeTo(rootDir).path.replace('\\', '/')
                        val matchingLines = text.lines()
                            .mapIndexedNotNull { index, line ->
                                if (line.contains(query, ignoreCase = true)) {
                                    "${index + 1}: ${line.trim().take(120)}"
                                } else null
                            }
                            .take(3)

                        resultsArray.put(
                            JSONObject()
                                .put("path", relPath)
                                .put("matches", JSONArray(matchingLines))
                        )
                        matchCount++
                    }
                }
            }

        return toolSuccess(
            call,
            "Found matches in $matchCount file(s) for '$query'",
            JSONObject()
                .put("query", query)
                .put("file_count", matchCount)
                .put("results", resultsArray),
        )
    }
}
