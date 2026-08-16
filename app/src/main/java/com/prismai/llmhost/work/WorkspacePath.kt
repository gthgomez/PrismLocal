package com.prismai.llmhost.work

import java.io.File
import java.nio.file.Path
import java.text.Normalizer

/**
 * Validated relative path within an app-owned workspace boundary.
 *
 * Enforces strict jail properties:
 * - Rejects directory traversal tokens (`..`, `/..`, `../`)
 * - Rejects absolute paths (`/foo`, `C:\foo`)
 * - Rejects null bytes (`\0`) and invalid control characters
 * - Normalizes Unicode and path separators
 */
@JvmInline
value class WorkspacePath(val rawRelativePath: String) {

    init {
        val sanitized = validate(rawRelativePath)
        require(sanitized != null) { "Invalid workspace path: '$rawRelativePath'" }
    }

    val normalizedPath: String
        get() = normalize(rawRelativePath)

    companion object {
        private const val MAX_PATH_LENGTH = 1024

        fun isValid(raw: String): Boolean = validate(raw) != null

        fun createOrNull(raw: String): WorkspacePath? {
            return if (isValid(raw)) WorkspacePath(raw) else null
        }

        private fun normalize(raw: String): String {
            val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace('\\', '/')
                .trim()
            return normalized.trimStart('/')
        }

        private fun validate(raw: String): String? {
            if (raw.length > MAX_PATH_LENGTH) return null
            if (raw.contains('\u0000')) return null

            // Check for control characters
            if (raw.any { it.code in 1..31 }) return null

            val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace('\\', '/')
                .trim()

            // Disallow drive letters like C:
            if (normalized.matches(Regex("^[a-zA-Z]:.*"))) return null

            // Disallow absolute unix paths
            if (normalized.startsWith("/")) return null

            val segments = normalized.split('/').filter { it.isNotEmpty() }
            for (segment in segments) {
                if (segment == "." || segment == "..") return null
            }

            return normalized
        }

        /**
         * Resolves [workspacePath] under [workspaceRoot] and verifies that the canonical file
         * resides strictly within [workspaceRoot.canonicalFile].
         *
         * @return The resolved [File] if safely inside workspace, or null if escape/invalid.
         */
        fun resolveSafely(workspaceRoot: File, workspacePath: WorkspacePath): File? {
            return try {
                val rootCanonical = workspaceRoot.canonicalFile.toPath().normalize()
                val target = File(workspaceRoot, workspacePath.normalizedPath)
                val targetCanonical = target.canonicalFile.toPath().normalize()

                if (targetCanonical.startsWith(rootCanonical)) {
                    target
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
