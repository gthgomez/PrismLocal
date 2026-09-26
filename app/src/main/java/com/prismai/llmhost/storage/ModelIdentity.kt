package com.prismai.llmhost.storage

import java.util.Locale

/** Version, hash, and path reviewed before a destructive model delete. */
data class ModelIdentity(
    val modelId: String,
    val versionId: String,
    val sha256: String,
    val path: String,
) {
    fun matches(info: ModelStorageManager.ActiveModelInfo): Boolean {
        if (modelId != info.id || versionId != info.versionId) return false
        if (!sha256.equals(info.sha256, ignoreCase = true)) return false
        val actualPath = runCatching { info.file.canonicalPath }.getOrNull() ?: return false
        return actualPath == path
    }

    companion object {
        fun from(info: ModelStorageManager.ActiveModelInfo): ModelIdentity? {
            if (info.id.isBlank() || info.versionId.isBlank() || info.sha256.isBlank()) return null
            val path = runCatching { info.file.canonicalPath }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return null
            return ModelIdentity(
                modelId = info.id,
                versionId = info.versionId,
                sha256 = info.sha256.lowercase(Locale.US),
                path = path,
            )
        }
    }
}
