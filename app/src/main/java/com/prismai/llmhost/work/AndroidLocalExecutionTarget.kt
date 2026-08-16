package com.prismai.llmhost.work

import java.io.File
import java.util.Locale

/**
 * Execution target for app-private local workspaces on Android.
 *
 * Enforces strict jail properties via [WorkspacePath.resolveSafely].
 * Explicitly does not support process execution or Git in this foundation phase.
 */
class AndroidLocalExecutionTarget(
    val workspaceRoot: File,
) : ExecutionTarget {

    override val id: ExecutionTargetId = ExecutionTargetId.ANDROID_LOCAL

    override val supportedCapabilities: Set<ExecutionCapability> = setOf(
        ExecutionCapability.FILE_READ,
        ExecutionCapability.FILE_WRITE,
        ExecutionCapability.FILE_LIST,
        ExecutionCapability.FILE_SEARCH,
    )

    init {
        if (!workspaceRoot.exists()) {
            workspaceRoot.mkdirs()
        }
    }

    override suspend fun readFile(path: WorkspacePath): Result<ByteArray> = runCatching {
        val target = WorkspacePath.resolveSafely(workspaceRoot, path)
            ?: throw SecurityException("Path resolves outside workspace boundary: '${path.rawRelativePath}'")

        if (!target.exists() || !target.isFile) {
            throw NoSuchFileException(target, reason = "File not found")
        }

        if (target.length() > MAX_READ_BYTES) {
            throw IllegalStateException("File size (${target.length()} bytes) exceeds max read bound ($MAX_READ_BYTES bytes)")
        }

        target.readBytes()
    }

    override suspend fun writeFile(path: WorkspacePath, data: ByteArray): Result<Unit> = runCatching {
        val target = WorkspacePath.resolveSafely(workspaceRoot, path)
            ?: throw SecurityException("Path resolves outside workspace boundary: '${path.rawRelativePath}'")

        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            val created = parent.mkdirs()
            if (!created && !parent.exists()) {
                throw IllegalStateException("Failed to create parent directory for '${path.rawRelativePath}'")
            }
        }

        target.writeBytes(data)
    }

    override suspend fun listFiles(path: WorkspacePath): Result<List<WorkspaceFileEntry>> = runCatching {
        val target = if (path.normalizedPath.isEmpty()) {
            workspaceRoot
        } else {
            WorkspacePath.resolveSafely(workspaceRoot, path)
                ?: throw SecurityException("Path resolves outside workspace boundary: '${path.rawRelativePath}'")
        }

        if (!target.exists()) {
            throw NoSuchFileException(target, reason = "Directory not found")
        }

        if (!target.isDirectory) {
            throw IllegalArgumentException("Path is not a directory: '${path.rawRelativePath}'")
        }

        val entries = target.listFiles() ?: emptyArray()
        entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) })).map { entry ->
            val rel = entry.relativeTo(workspaceRoot).path.replace('\\', '/')
            WorkspaceFileEntry(
                name = entry.name,
                relativePath = rel,
                isDirectory = entry.isDirectory,
                sizeBytes = if (entry.isFile) entry.length() else null,
                lastModified = entry.lastModified(),
            )
        }
    }

    override suspend fun searchFiles(query: String, maxResults: Int): Result<List<WorkspaceSearchResult>> = runCatching {
        require(query.isNotBlank()) { "Query must not be blank" }
        val results = mutableListOf<WorkspaceSearchResult>()

        workspaceRoot.walkTopDown()
            .filter { it.isFile && it.length() <= MAX_SEARCH_FILE_SIZE }
            .take(200)
            .forEach { file ->
                if (results.size >= maxResults) return@forEach
                try {
                    val text = file.readText(Charsets.UTF_8)
                    if (!text.take(500).contains('\u0000') && text.contains(query, ignoreCase = true)) {
                        val relPath = file.relativeTo(workspaceRoot).path.replace('\\', '/')
                        val matchingLines = text.lines()
                            .mapIndexedNotNull { index, line ->
                                if (line.contains(query, ignoreCase = true)) {
                                    "${index + 1}: ${line.trim().take(120)}"
                                } else null
                            }
                            .take(3)

                        results.add(WorkspaceSearchResult(relativePath = relPath, matchingLines = matchingLines))
                    }
                } catch (_: Exception) {
                    // Ignore unreadable / binary files
                }
            }

        results
    }

    companion object {
        private const val MAX_READ_BYTES = 10 * 1024 * 1024L // 10MB
        private const val MAX_SEARCH_FILE_SIZE = 1_000_000L // 1MB
    }
}
