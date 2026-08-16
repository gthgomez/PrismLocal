package com.prismai.llmhost.work

enum class ExecutionTargetId {
    ANDROID_LOCAL,
    BABEL_HOST,
}

enum class ExecutionCapability {
    FILE_READ,
    FILE_WRITE,
    FILE_LIST,
    FILE_SEARCH,
    PROCESS_EXEC,
    GIT,
    VERIFIER_EXECUTION,
    SWE_COMPLETION_AUTHORITY,
}

data class WorkspaceFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long = 0L,
)

data class WorkspaceSearchResult(
    val relativePath: String,
    val matchingLines: List<String>,
)

interface ExecutionTarget {
    val id: ExecutionTargetId
    val supportedCapabilities: Set<ExecutionCapability>

    suspend fun readFile(path: WorkspacePath): Result<ByteArray>
    suspend fun writeFile(path: WorkspacePath, data: ByteArray): Result<Unit>
    suspend fun listFiles(path: WorkspacePath): Result<List<WorkspaceFileEntry>>
    suspend fun searchFiles(query: String, maxResults: Int = 25): Result<List<WorkspaceSearchResult>>
}
