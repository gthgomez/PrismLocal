package com.example.llmhost

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ModelStorageManager(private val context: Context) {
    private val modelsDir: File
        get() = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    data class ActiveModelInfo(
        val id: String,
        val versionId: String,
        val file: File,
        val fileName: String,
        val sha256: String,
        val bytes: Long,
        val importedAt: String,
        val validation: ModelValidation,
    )

    data class ModelValidation(
        val format: String,
        val ggufVersion: Int,
        val status: String,
        val validatedAt: String,
        val metadata: GgufMetadataSummary? = null,
    )

    data class GgufMetadataSummary(
        val architecture: String? = null,
        val name: String? = null,
        val sizeLabel: String? = null,
        val fileType: Int? = null,
        val contextLength: Int? = null,
        val blockCount: Int? = null,
        val embeddingLength: Int? = null,
        val attentionHeadCount: Int? = null,
        val attentionHeadCountKv: Int? = null,
        val hasChatTemplate: Boolean = false,
    )

    data class ImportProgress(
        val bytesCopied: Long,
        val totalBytes: Long?,
    )

    data class ModelStorageError(
        val code: Code,
        val userMessage: String,
        val technicalMessage: String? = null,
    ) {
        enum class Code {
            OPEN_FAILED,
            COPY_FAILED,
            INSUFFICIENT_SPACE,
            FILE_TOO_SMALL,
            FILE_TOO_LARGE,
            INVALID_GGUF_HEADER,
            HASH_MISMATCH,
            CORRUPT_MANIFEST,
            MISSING_MODEL_FILE,
            PROMOTION_FAILED,
        }
    }

    sealed class ImportResult {
        data class Success(val model: ActiveModelInfo) : ImportResult()
        data class Failure(val error: ModelStorageError) : ImportResult()
    }

    sealed class ModelResolveResult {
        data class Success(val model: ActiveModelInfo) : ModelResolveResult()
        data class Failure(val error: ModelStorageError) : ModelResolveResult()
    }

    fun listInstalledModels(): List<String> {
        val dir = modelsDir
        if (!dir.exists()) return emptyList()
        val models = dir.listFiles()
            ?.filter { it.isDirectory && it.name != IMPORT_STAGING_DIR && File(it, MANIFEST_FILE).exists() }
            ?.mapNotNull { modelRoot ->
                when (parseManifest(modelRoot, verifyHash = false)) {
                    is ModelResolveResult.Success -> modelRoot.name
                    is ModelResolveResult.Failure -> null
                }
            }
            ?.sorted()
            ?: emptyList()
        Log.d(TAG, "listInstalledModels=$models")
        return models
    }

    fun listInstalledModelInfos(): List<ActiveModelInfo> {
        val dir = modelsDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && it.name != IMPORT_STAGING_DIR && File(it, MANIFEST_FILE).exists() }
            ?.mapNotNull { modelRoot ->
                (parseManifest(modelRoot, verifyHash = false) as? ModelResolveResult.Success)?.model
            }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    fun importModel(
        uri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult {
        val displayName = displayNameFor(uri)
        val reportedSize = sizeFor(uri)
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Import open failed for $uri", e)
            null
        } ?: return ImportResult.Failure(
            ModelStorageError(
                ModelStorageError.Code.OPEN_FAILED,
                "Could not open selected model file",
            )
        )

        input.use {
            return importModelFromStream(displayName, reportedSize, it, onProgress)
        }
    }

    @VisibleForTesting
    fun importModelFromStream(
        displayName: String,
        reportedSize: Long,
        input: InputStream,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult {
        val modelId = modelIdFrom(displayName)
        val versionId = newVersionId()
        val now = Instant.now().toString()
        val stagingDir = File(modelsDir, "$IMPORT_STAGING_DIR/$modelId-$versionId-${UUID.randomUUID()}")
        val stagedModel = File(stagingDir, MODEL_FILE)

        val existingManifest = File(modelsDir, "$modelId/$MANIFEST_FILE")
        if (existingManifest.exists()) {
            val existing = parseManifest(File(modelsDir, modelId), verifyHash = false)
            if (existing is ModelResolveResult.Failure) {
                return ImportResult.Failure(existing.error)
            }
        }

        if (reportedSize > MAX_MODEL_BYTES) {
            return ImportResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.FILE_TOO_LARGE,
                    "Model is too large for this build",
                    "reportedSize=$reportedSize max=$MAX_MODEL_BYTES",
                )
            )
        }
        if (reportedSize > 0 && !hasUsableSpaceFor(reportedSize)) {
            return ImportResult.Failure(insufficientSpaceError(reportedSize))
        }

        return try {
            stagingDir.mkdirs()
            copyStream(input, stagedModel, reportedSize, onProgress)
            val bytes = stagedModel.length()
            validateSize(bytes)?.let { return ImportResult.Failure(it).also { cleanup(stagingDir) } }
            if (!hasUsableSpaceFor(bytes)) {
                return ImportResult.Failure(insufficientSpaceError(bytes)).also { cleanup(stagingDir) }
            }
            val validation = validateGguf(stagedModel)
                ?: return ImportResult.Failure(
                    ModelStorageError(
                        ModelStorageError.Code.INVALID_GGUF_HEADER,
                        "Selected file is not a valid GGUF model",
                    )
                ).also { cleanup(stagingDir) }
            val sha256 = sha256(stagedModel)
            val modelRoot = File(modelsDir, modelId)
            val targetVersionDir = File(modelRoot, "versions/$versionId")
            modelRoot.mkdirs()
            promoteDirectory(stagingDir, targetVersionDir)

            val activeFile = File(targetVersionDir, MODEL_FILE)
            val versionFile = "versions/$versionId/$MODEL_FILE"
            val manifest = mergedManifest(
                modelRoot = modelRoot,
                modelId = modelId,
                versionId = versionId,
                versionFile = versionFile,
                displayName = displayName,
                sha256 = sha256,
                bytes = activeFile.length(),
                importedAt = now,
                validation = validation,
            )
            writeManifestAtomically(modelRoot, manifest)

            val resolved = resolveActiveModel(modelId)
            if (resolved is ModelResolveResult.Success) {
                ImportResult.Success(resolved.model)
            } else {
                val failure = resolved as ModelResolveResult.Failure
                ImportResult.Failure(failure.error)
            }
        } catch (e: CancellationException) {
            cleanup(stagingDir)
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Import failed for displayName=$displayName", e)
            cleanup(stagingDir)
            ImportResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.COPY_FAILED,
                    "Import failed while copying the model",
                    e.message,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed for displayName=$displayName", e)
            cleanup(stagingDir)
            ImportResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.PROMOTION_FAILED,
                    "Import failed before the model could be installed",
                    e.message,
                )
            )
        }
    }

    fun resolveActiveModel(modelId: String): ModelResolveResult =
        parseManifest(File(modelsDir, modelId), verifyHash = true)

    fun activeModelInfo(modelId: String): ActiveModelInfo? =
        (resolveActiveModel(modelId) as? ModelResolveResult.Success)?.model

    private fun parseManifest(modelRoot: File, verifyHash: Boolean): ModelResolveResult {
        val modelId = modelRoot.name
        val manifestFile = File(modelRoot, MANIFEST_FILE)
        if (!manifestFile.exists()) {
            return ModelResolveResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.CORRUPT_MANIFEST,
                    "Model $modelId does not have a manifest",
                )
            )
        }

        return try {
            val manifest = JSONObject(manifestFile.readText())
            val activeVersion = manifest.getString("active_version")
            val activeObj = manifest.getJSONObject("versions").getJSONObject(activeVersion)
            val relativeFile = activeObj.getString("file")
            val modelFile = File(modelRoot, relativeFile)
            if (!isInside(modelRoot, modelFile)) {
                return ModelResolveResult.Failure(
                    ModelStorageError(
                        ModelStorageError.Code.CORRUPT_MANIFEST,
                        "Model manifest points outside the model directory",
                    )
                )
            }
            if (!modelFile.exists()) {
                return ModelResolveResult.Failure(
                    ModelStorageError(
                        ModelStorageError.Code.MISSING_MODEL_FILE,
                        "Model $modelId is missing its active GGUF file",
                        modelFile.absolutePath,
                    )
                )
            }
            val validation = parseValidation(activeObj)
                ?: validateGguf(modelFile)
                ?: return ModelResolveResult.Failure(
                    ModelStorageError(
                        ModelStorageError.Code.INVALID_GGUF_HEADER,
                        "Model $modelId is not a valid GGUF model",
                    )
                )
            val expectedSha = activeObj.getString("sha256")
            if (verifyHash) {
                val actualSha = sha256(modelFile)
                if (!expectedSha.equals(actualSha, ignoreCase = true)) {
                    return ModelResolveResult.Failure(
                        ModelStorageError(
                            ModelStorageError.Code.HASH_MISMATCH,
                            "Model $modelId failed integrity verification",
                            "expected=$expectedSha actual=$actualSha",
                        )
                    )
                }
            }
            ModelResolveResult.Success(
                ActiveModelInfo(
                    id = modelId,
                    versionId = activeVersion,
                    file = modelFile,
                    fileName = activeObj.optString("original_file_name", modelFile.name),
                    sha256 = expectedSha.lowercase(Locale.US),
                    bytes = activeObj.optLong("bytes", modelFile.length()),
                    importedAt = activeObj.optString("imported_at", ""),
                    validation = validation,
                )
            )
        } catch (e: JSONException) {
            Log.e(TAG, "Manifest parsing failed for $modelId", e)
            ModelResolveResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.CORRUPT_MANIFEST,
                    "Model $modelId has a corrupt manifest",
                    e.message,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Manifest validation failed for $modelId", e)
            ModelResolveResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.CORRUPT_MANIFEST,
                    "Model $modelId could not be validated",
                    e.message,
                )
            )
        }
    }

    private fun mergedManifest(
        modelRoot: File,
        modelId: String,
        versionId: String,
        versionFile: String,
        displayName: String,
        sha256: String,
        bytes: Long,
        importedAt: String,
        validation: ModelValidation,
    ): JSONObject {
        val existing = File(modelRoot, MANIFEST_FILE)
        val versions = if (existing.exists()) {
            JSONObject(existing.readText()).getJSONObject("versions")
        } else {
            JSONObject()
        }
        versions.put(
            versionId,
            JSONObject()
                .put("file", versionFile)
                .put("original_file_name", displayName)
                .put("sha256", sha256)
                .put("bytes", bytes)
                .put("imported_at", importedAt)
                .put(
                    "validation",
                    JSONObject()
                        .put("format", validation.format)
                        .put("gguf_version", validation.ggufVersion)
                        .put("status", validation.status)
                        .put("validated_at", validation.validatedAt)
                        .apply {
                            validation.metadata?.let { metadata ->
                                put(
                                    "metadata",
                                    JSONObject()
                                        .put("architecture", metadata.architecture)
                                        .put("name", metadata.name)
                                        .put("size_label", metadata.sizeLabel)
                                        .put("file_type", metadata.fileType)
                                        .put("context_length", metadata.contextLength)
                                        .put("block_count", metadata.blockCount)
                                        .put("embedding_length", metadata.embeddingLength)
                                        .put("attention_head_count", metadata.attentionHeadCount)
                                        .put("attention_head_count_kv", metadata.attentionHeadCountKv)
                                        .put("has_chat_template", metadata.hasChatTemplate)
                                )
                            }
                        }
                )
        )
        return JSONObject()
            .put("schema_version", 1)
            .put("model_id", modelId)
            .put("active_version", versionId)
            .put("versions", versions)
    }

    private fun writeManifestAtomically(modelRoot: File, manifest: JSONObject) {
        val tmp = File(modelRoot, "$MANIFEST_FILE.tmp-${UUID.randomUUID()}")
        tmp.writeText(manifest.toString(2))
        moveAtomically(tmp, File(modelRoot, MANIFEST_FILE))
    }

    private fun copyStream(
        input: InputStream,
        destination: File,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        var copied = 0L
        val total = totalBytes.takeIf { it > 0 }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        destination.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                copied += read
                onProgress(ImportProgress(copied, total))
                if (copied > MAX_MODEL_BYTES) {
                    throw IOException("Model exceeds maximum size $MAX_MODEL_BYTES")
                }
            }
        }
    }

    private fun validateSize(bytes: Long): ModelStorageError? = when {
        bytes < MIN_GGUF_BYTES -> ModelStorageError(
            ModelStorageError.Code.FILE_TOO_SMALL,
            "Selected file is too small to be a GGUF model",
            "bytes=$bytes",
        )
        bytes > MAX_MODEL_BYTES -> ModelStorageError(
            ModelStorageError.Code.FILE_TOO_LARGE,
            "Model is too large for this build",
            "bytes=$bytes",
        )
        else -> null
    }

    private fun validateGguf(file: File): ModelValidation? {
        file.inputStream().use { input ->
            val reader = GgufReader(input)
            if (reader.readAscii(4) != "GGUF") {
                return null
            }
            val version = reader.readU32()
            if (version !in 1..4) return null
            val metadata = runCatching { parseGgufMetadata(reader, version) }
                .onFailure { error -> Log.w(TAG, "GGUF metadata parse failed file=${file.name}", error) }
                .getOrNull()
            return ModelValidation(
                format = "GGUF",
                ggufVersion = version,
                status = "verified",
                validatedAt = Instant.now().toString(),
                metadata = metadata,
            )
        }
    }

    private fun parseValidation(activeObj: JSONObject): ModelValidation? {
        val validation = activeObj.optJSONObject("validation") ?: return null
        return ModelValidation(
            format = validation.optString("format", "GGUF"),
            ggufVersion = validation.optInt("gguf_version", -1),
            status = validation.optString("status", "unknown"),
            validatedAt = validation.optString("validated_at", ""),
            metadata = parseMetadataSummary(validation.optJSONObject("metadata")),
        ).takeIf { it.format == "GGUF" && it.ggufVersion in 1..4 }
    }

    private fun parseMetadataSummary(metadata: JSONObject?): GgufMetadataSummary? {
        if (metadata == null) return null
        return GgufMetadataSummary(
            architecture = metadata.optString("architecture").takeIf { it.isNotBlank() },
            name = metadata.optString("name").takeIf { it.isNotBlank() },
            sizeLabel = metadata.optString("size_label").takeIf { it.isNotBlank() },
            fileType = metadata.optIntOrNull("file_type"),
            contextLength = metadata.optIntOrNull("context_length"),
            blockCount = metadata.optIntOrNull("block_count"),
            embeddingLength = metadata.optIntOrNull("embedding_length"),
            attentionHeadCount = metadata.optIntOrNull("attention_head_count"),
            attentionHeadCountKv = metadata.optIntOrNull("attention_head_count_kv"),
            hasChatTemplate = metadata.optBoolean("has_chat_template", false),
        )
    }

    private fun parseGgufMetadata(reader: GgufReader, version: Int): GgufMetadataSummary? {
        if (version == 1) {
            return null
        }
        reader.readU64() // tensor_count
        val kvCount = reader.readU64().coerceAtMost(4096L)
        var architecture: String? = null
        var name: String? = null
        var sizeLabel: String? = null
        var fileType: Int? = null
        var contextLength: Int? = null
        var blockCount: Int? = null
        var embeddingLength: Int? = null
        var attentionHeadCount: Int? = null
        var attentionHeadCountKv: Int? = null
        var hasChatTemplate = false

        repeat(kvCount.toInt()) {
            val key = reader.readGgufString()
            val type = reader.readU32()
            val value = reader.readMetadataValue(type)
            when (key) {
                "general.architecture" -> architecture = value as? String
                "general.name" -> name = value as? String
                "general.size_label" -> sizeLabel = value as? String
                "general.file_type" -> fileType = (value as? Number)?.toInt()
                "tokenizer.chat_template" -> hasChatTemplate = (value as? String)?.isNotBlank() == true
                else -> {
                    val prefix = architecture?.let { "$it." }
                    if (prefix != null && key.startsWith(prefix)) {
                        when (key.removePrefix(prefix)) {
                            "context_length" -> contextLength = (value as? Number)?.toInt()
                            "block_count" -> blockCount = (value as? Number)?.toInt()
                            "embedding_length" -> embeddingLength = (value as? Number)?.toInt()
                            "attention.head_count" -> attentionHeadCount = (value as? Number)?.toInt()
                            "attention.head_count_kv" -> attentionHeadCountKv = (value as? Number)?.toInt()
                        }
                    }
                }
            }
        }

        return GgufMetadataSummary(
            architecture = architecture,
            name = name,
            sizeLabel = sizeLabel,
            fileType = fileType,
            contextLength = contextLength,
            blockCount = blockCount,
            embeddingLength = embeddingLength,
            attentionHeadCount = attentionHeadCount,
            attentionHeadCountKv = attentionHeadCountKv,
            hasChatTemplate = hasChatTemplate,
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private class GgufReader(private val input: InputStream) {
        private val scratch = ByteArray(8)

        fun readAscii(length: Int): String {
            val bytes = ByteArray(length)
            readFully(bytes)
            return bytes.toString(Charsets.US_ASCII)
        }

        fun readU32(): Int {
            readFully(scratch, 4)
            return (scratch[0].toInt() and 0xff) or
                ((scratch[1].toInt() and 0xff) shl 8) or
                ((scratch[2].toInt() and 0xff) shl 16) or
                ((scratch[3].toInt() and 0xff) shl 24)
        }

        fun readU64(): Long {
            readFully(scratch, 8)
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((scratch[index].toLong() and 0xffL) shl (8 * index))
            }
            return value
        }

        fun readGgufString(): String {
            val length = readU64().coerceAtMost(1_000_000L).toInt()
            val bytes = ByteArray(length)
            readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        fun readMetadataValue(type: Int): Any? =
            when (type) {
                0, 1 -> readScalar(1)
                2, 3 -> readScalar(2)
                4, 5, 6 -> readScalar(4)
                7 -> readScalar(1) != 0L
                8 -> readGgufString()
                9 -> {
                    val elementType = readU32()
                    val count = readU64().coerceAtMost(1_000_000L).toInt()
                    repeat(count) { skipMetadataValue(elementType) }
                    null
                }
                10, 11, 12 -> readScalar(8)
                else -> null
            }

        private fun skipMetadataValue(type: Int) {
            when (type) {
                0, 1, 7 -> skipFully(1)
                2, 3 -> skipFully(2)
                4, 5, 6 -> skipFully(4)
                8 -> skipFully(readU64().coerceAtMost(1_000_000L))
                10, 11, 12 -> skipFully(8)
                else -> Unit
            }
        }

        private fun readScalar(bytes: Int): Long {
            readFully(scratch, bytes)
            var value = 0L
            for (index in 0 until bytes) {
                value = value or ((scratch[index].toLong() and 0xffL) shl (8 * index))
            }
            return value
        }

        private fun readFully(buffer: ByteArray, length: Int = buffer.size) {
            var offset = 0
            while (offset < length) {
                val read = input.read(buffer, offset, length - offset)
                if (read < 0) throw IOException("Unexpected EOF")
                offset += read
            }
        }

        private fun skipFully(bytes: Long) {
            var remaining = bytes
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) {
                    if (input.read() == -1) throw IOException("Unexpected EOF")
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasUsableSpaceFor(bytes: Long): Boolean {
        val dir = modelsDir.also { it.mkdirs() }
        val stat = StatFs(dir.absolutePath)
        val usable = stat.availableBytes
        return usable > bytes + MIN_FREE_SPACE_AFTER_IMPORT
    }

    private fun insufficientSpaceError(bytes: Long): ModelStorageError =
        ModelStorageError(
            ModelStorageError.Code.INSUFFICIENT_SPACE,
            "Not enough free space to import this model",
            "required=$bytes reserve=$MIN_FREE_SPACE_AFTER_IMPORT",
        )

    private fun displayNameFor(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment ?: MODEL_FILE
    }

    private fun sizeFor(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) {
                    return cursor.getLong(index)
                }
            }
        }
        return -1L
    }

    private fun modelIdFrom(displayName: String): String {
        val withoutExtension = displayName.removeSuffix(".gguf")
        val cleaned = withoutExtension.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_')
        return cleaned.ifBlank { "imported-model" }
    }

    private fun newVersionId(): String = "v${System.currentTimeMillis()}"

    private fun promoteDirectory(stagingDir: File, targetVersionDir: File) {
        if (targetVersionDir.exists()) {
            throw IOException("Target version already exists: ${targetVersionDir.absolutePath}")
        }
        targetVersionDir.parentFile?.mkdirs()
        moveAtomically(stagingDir, targetVersionDir)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath.startsWith(rootPath)
    }

    private fun cleanup(dir: File) {
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to clean import staging dir ${dir.absolutePath}", error)
        }
    }

    private companion object {
        const val TAG = "ModelStorageManager"
        const val MANIFEST_FILE = "manifest.json"
        const val MODEL_FILE = "model.gguf"
        const val IMPORT_STAGING_DIR = ".imports"
        const val MIN_GGUF_BYTES = 32L
        const val MAX_MODEL_BYTES = 32L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_AFTER_IMPORT = 512L * 1024L * 1024L
    }
}
