package com.prismai.llmhost.storage
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.prismai.llmhost.util.toHex
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.withLock

internal object ModelStorageLifecycleGate {
    private val lock = ReentrantLock()
    private val revisions = mutableMapOf<String, Long>()

    fun <T> withLock(action: () -> T): T = lock.withLock(action)

    fun <T> tryWithLock(action: () -> T): T? {
        if (!lock.tryLock()) return null
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun revision(modelId: String): Long = lock.withLock { revisions[modelId] ?: 0L }

    fun advanceRevision(modelId: String): Long = lock.withLock {
        val next = (revisions[modelId] ?: 0L) + 1L
        revisions[modelId] = next
        next
    }
}

/**
 * Process-scoped record of model artifacts already hashed during this process.
 *
 * Hashing a multi-gigabyte GGUF is expensive, so activation hashes the whole file once and then
 * reuses that digest while the artifact is unchanged. "Unchanged" is checked against a
 * [fingerprint] — the file length plus a hash of its first and last [FINGERPRINT_BYTES] — together
 * with size and mtime. The fingerprint detects a same-size, same-mtime *substitution of a
 * different artifact* (its head/tail bytes differ) at negligible I/O cost, so "matches" is not
 * merely a (size, mtime) spoof check.
 *
 * Threat model & boundary (PL-F15):
 * The verification cache is an opportunistic change detector for app-private storage, NOT an
 * adversarial tamper-proof guarantee. It detects accidental corruption, truncation, and replacement
 * of the artifact with a different file. It does NOT detect deliberate middle-of-file mutations
 * (>64 KiB from ends) where an entity with write access to the app's private sandbox modifies bytes
 * while preserving exact file length and mtime. Under Android's security architecture, any process
 * with arbitrary write access to the app-private filesDir has already completely compromised the
 * application's sandbox (and could equally rewrite manifest.json itself).
 *
 * Full SHA-256 streaming verification is performed upon initial import/download before writing the
 * manifest. Subsequent activations rely on this cache for performance (avoiding multi-gigabyte re-reads).
 * Callers that must not trust the cache use [ModelStorageManager.resolveActiveModel] with
 * [HashPolicy.FULL] instead of [ModelStorageManager.resolveActiveModelForActivation].
 */
internal object ModelArtifactVerificationCache {
    /** Bytes read from each end of the artifact for the change fingerprint. */
    private const val FINGERPRINT_BYTES = 64 * 1024

    private data class Entry(
        val sha256: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val fingerprint: String,
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun matches(file: File, expectedSha256: String): Boolean {
        val entry = entries[key(file)] ?: return false
        if (entry.sha256 != expectedSha256.lowercase(Locale.US)) return false
        if (entry.sizeBytes != file.length()) return false
        if (entry.lastModifiedMs != file.lastModified()) return false
        // A failed/empty fingerprint must never count as a match (re-hash instead).
        if (entry.fingerprint.isEmpty()) return false
        return entry.fingerprint == fingerprint(file)
    }

    @Synchronized
    fun remember(file: File, sha256: String) {
        entries[key(file)] = Entry(
            sha256 = sha256.lowercase(Locale.US),
            sizeBytes = file.length(),
            lastModifiedMs = file.lastModified(),
            fingerprint = fingerprint(file),
        )
    }

    /**
     * Change fingerprint that is stable for identical bytes and different for a different artifact:
     * SHA-256 over the length and the first and last [FINGERPRINT_BYTES]. Returns "" if the file
     * cannot be read, which can only cause a cache miss (a re-hash), never a false match.
     */
    private fun fingerprint(file: File): String = try {
        val length = file.length()
        val headLen = minOf(FINGERPRINT_BYTES.toLong(), length).toInt()
        val tailStart = maxOf(0L, length - FINGERPRINT_BYTES)
        val tailLen = (length - tailStart).toInt()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(length).array())
        file.inputStream().use { input ->
            val head = ByteArray(headLen)
            var read = 0
            while (read < headLen) {
                val n = input.read(head, read, headLen - read)
                if (n < 0) break
                read += n
            }
            md.update(head, 0, read)
        }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(tailStart)
            val tail = ByteArray(tailLen)
            var read = 0
            while (read < tailLen) {
                val n = raf.read(tail, read, tailLen - read)
                if (n < 0) break
                read += n
            }
            md.update(tail, 0, read)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun forgetUnder(directory: File) {
        val prefix = key(directory)
        entries.keys.removeAll { it == prefix || it.startsWith("$prefix${File.separator}") }
    }

    @Synchronized
    @VisibleForTesting
    fun clear() = entries.clear()

    private fun key(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}

class ModelStorageManager(
    private val context: Context,
    private val modelsDirectoryOverride: File? = null,
) {
    private val modelsDir: File
        get() = modelsDirectoryOverride
            ?: context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models")

    data class ActiveModelInfo(
        val id: String,
        val versionId: String,
        val file: File,
        val fileName: String,
        val sha256: String,
        val bytes: Long,
        val importedAt: String,
        val validation: ModelValidation,
        val integrity: DownloadIntegrity = DownloadIntegrity.UNKNOWN_LEGACY,
    )

    data class ModelValidation(
        val format: String,
        val ggufVersion: Int,
        val status: String,
        val validatedAt: String,
        val metadata: GgufMetadataSummary? = null,
    ) {
        val metadataChecked: Boolean get() = status == METADATA_CHECKED
    }

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
        cleanOrphanedDownloads()
        val dir = modelsDir
        if (!dir.exists()) return emptyList()
        val models = dir.listFiles()
            ?.filter { it.isDirectory && it.name != IMPORT_STAGING_DIR && File(it, MANIFEST_FILE).exists() }
            ?.mapNotNull { modelRoot ->
                when (parseManifest(modelRoot, HashPolicy.NONE)) {
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
                (parseManifest(modelRoot, HashPolicy.NONE) as? ModelResolveResult.Success)?.model
            }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    fun importModel(
        uri: Uri,
        checkCancelled: () -> Unit = {},
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult {
        val displayName = displayNameFor(uri)
        val expectedRevision = ModelStorageLifecycleGate.revision(modelIdFromDisplayName(displayName))
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
            return importModelFromStream(
                displayName = displayName,
                reportedSize = reportedSize,
                input = it,
                expectedLifecycleRevision = expectedRevision,
                onProgress = onProgress,
                checkCancelled = checkCancelled,
            )
        }
    }

    internal fun captureLifecycleRevisionForDisplayName(displayName: String): Long =
        ModelStorageLifecycleGate.revision(modelIdFromDisplayName(displayName))

    @VisibleForTesting
    fun importModelFromStream(
        displayName: String,
        reportedSize: Long,
        input: InputStream,
        expectedLifecycleRevision: Long? = null,
        onProgress: (ImportProgress) -> Unit = {},
        integrity: DownloadIntegrity = DownloadIntegrity.UNKNOWN_LEGACY,
        checkCancelled: () -> Unit = {},
    ): ImportResult = ModelStorageLifecycleGate.withLock {
        val modelId = modelIdFromDisplayName(displayName)
        val expected = expectedLifecycleRevision ?: ModelStorageLifecycleGate.revision(modelId)
        if (ModelStorageLifecycleGate.revision(modelId) != expected) {
            ImportResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.PROMOTION_FAILED,
                    "Model installation was cancelled because its storage owner changed",
                    "stale lifecycle revision for modelId=$modelId",
                ),
            )
        } else {
            importModelFromStreamLocked(displayName, reportedSize, input, onProgress, integrity, checkCancelled)
        }
    }

    private fun importModelFromStreamLocked(
        displayName: String,
        reportedSize: Long,
        input: InputStream,
        onProgress: (ImportProgress) -> Unit,
        integrity: DownloadIntegrity,
        checkCancelled: () -> Unit,
    ): ImportResult {
        val modelId = modelIdFrom(displayName)
        val versionId = newVersionId()
        val now = Instant.now().toString()
        val stagingDir = File(modelsDir, "$IMPORT_STAGING_DIR/$modelId-$versionId-${UUID.randomUUID()}")
        val stagedModel = File(stagingDir, MODEL_FILE)

        val modelRoot = File(modelsDir, modelId)
        val existing = if (File(modelRoot, MANIFEST_FILE).exists()) {
            when (val result = parseManifest(modelRoot, HashPolicy.NONE)) {
                is ModelResolveResult.Failure -> return ImportResult.Failure(result.error)
                is ModelResolveResult.Success -> result.model
            }
        } else null

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
            checkCancelled()
            stagingDir.mkdirs()
            copyStream(input, stagedModel, reportedSize, onProgress, checkCancelled)
            val bytes = stagedModel.length()
            checkCancelled()
            validateSize(bytes)?.let { return ImportResult.Failure(it) }
            // The model bytes are already on disk at this point and promotion is a
            // rename, so only the reserve must remain. Requiring the full size again
            // made a fitting model fail after the entire copy completed.
            if (!hasReserveSpace()) {
                return ImportResult.Failure(insufficientSpaceError(bytes))
            }
            val validation = validateGguf(stagedModel, checkCancelled)
                ?: return ImportResult.Failure(
                    ModelStorageError(
                        ModelStorageError.Code.INVALID_GGUF_HEADER,
                        "Selected file has an unsupported GGUF version or invalid GGUF metadata",
                    )
                )
            checkCancelled()
            if (!NativeLlmBridge.ggufTensorDataFits(stagedModel.absolutePath, bytes)) {
                return ImportResult.Failure(ModelStorageError(
                    ModelStorageError.Code.INVALID_GGUF_HEADER,
                    "Selected GGUF has missing or invalid tensor data",
                ))
            }
            val sha256 = sha256(stagedModel, checkCancelled)
            // A retry of the same content and provenance should not consume another model's
            // worth of disk, but do not reuse an artifact that has lost integrity.
            if (existing?.sha256 == sha256 && existing.integrity == integrity) {
                val resolved = resolveActiveModelForActivation(modelId)
                if (resolved is ModelResolveResult.Success) return ImportResult.Success(resolved.model)
            }
            val targetVersionDir = File(modelRoot, "versions/$versionId")
            modelRoot.mkdirs()
            val versionFile = "versions/$versionId/$MODEL_FILE"
            val manifest = mergedManifest(
                modelRoot = modelRoot,
                modelId = modelId,
                versionId = versionId,
                versionFile = versionFile,
                displayName = displayName,
                sha256 = sha256,
                bytes = bytes,
                importedAt = now,
                validation = validation,
                integrity = integrity,
            )
            checkCancelled()
            promoteDirectory(stagingDir, targetVersionDir)
            val activeFile = File(targetVersionDir, MODEL_FILE)
            try {
                if (!activeFile.isFile || activeFile.length() != bytes) {
                    throw IOException("The promoted model does not match the staged artifact")
                }
                writeManifestAtomically(modelRoot, manifest)
            } catch (error: Exception) {
                cleanup(targetVersionDir)
                throw error
            }

            // The staged bytes were just hashed and promotion is a rename that preserves content, so
            // record the verified artifact instead of hashing the multi-gigabyte file a second time.
            ModelArtifactVerificationCache.remember(activeFile, sha256)
            ImportResult.Success(
                ActiveModelInfo(modelId, versionId, activeFile, displayName, sha256, bytes, now, validation, integrity)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Import failed for displayName=$displayName", e)
            ImportResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.COPY_FAILED,
                    "Import failed while copying the model",
                    e.message,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed for displayName=$displayName", e)
            ImportResult.Failure(
                ModelStorageError(
                    ModelStorageError.Code.PROMOTION_FAILED,
                    "Import failed before the model could be installed",
                    e.message,
                )
            )
        } finally {
            cleanup(stagingDir)
        }
    }

    fun deleteModel(modelId: String, confirmedIdentity: ModelIdentity? = null): Boolean =
        ModelStorageLifecycleGate.withLock {
            val modelRoot = File(modelsDir, modelId)
            val isSafeModelDir = runCatching {
                modelRoot.canonicalFile != modelsDir.canonicalFile && isInside(modelsDir, modelRoot)
            }.getOrDefault(false)
            if (!isSafeModelDir) {
                runCatching { Log.w(TAG, "Refusing to delete model outside models dir modelId=$modelId") }
                return@withLock false
            }
            if (confirmedIdentity != null && !confirmedIdentityStillInstalled(modelId, modelRoot, confirmedIdentity)) {
                return@withLock false
            }
            ModelStorageLifecycleGate.advanceRevision(modelId)
            if (!modelRoot.exists()) return@withLock false
            val deleted = runCatching { modelRoot.deleteRecursively() }.getOrDefault(false)
            if (deleted) ModelArtifactVerificationCache.forgetUnder(modelRoot)
            runCatching { Log.d(TAG, "deleteModel modelId=$modelId deleted=$deleted") }
            deleted
        }

    private fun confirmedIdentityStillInstalled(
        modelId: String,
        modelRoot: File,
        confirmedIdentity: ModelIdentity,
    ): Boolean {
        if (confirmedIdentity.modelId != modelId || !modelRoot.exists()) return false
        val resolved = parseManifest(modelRoot, HashPolicy.NONE)
        val installed = (resolved as? ModelResolveResult.Success)?.model ?: return false
        return confirmedIdentity.matches(installed)
    }

    fun resolveActiveModel(modelId: String, verifyHash: Boolean = true): ModelResolveResult =
        parseManifest(File(modelsDir, modelId), if (verifyHash) HashPolicy.ALWAYS else HashPolicy.NONE)

    /**
     * Resolves the active artifact for a native load, verifying its SHA-256 against the recorded
     * manifest identity. A digest hashed earlier in this process is reused only when the artifact
     * still fingerprints the same (length, head/tail bytes, size, mtime); see
     * [ModelArtifactVerificationCache]. An artifact whose size, mtime or content fingerprint
     * changed, or whose digest no longer matches, is rejected with [HASH_MISMATCH] and never
     * handed to the native loader.
     */
    fun resolveActiveModelForActivation(modelId: String): ModelResolveResult =
        parseManifest(File(modelsDir, modelId), HashPolicy.CACHED)

    fun activeModelInfo(modelId: String): ActiveModelInfo? =
        (resolveActiveModel(modelId, verifyHash = false) as? ModelResolveResult.Success)?.model

    data class StorageBreakdown(
        val installedModelsBytes: Long,
        val downloadsCacheBytes: Long,
        val freeStorageBytes: Long,
        val totalStorageBytes: Long,
    )

    fun getStorageBreakdown(): StorageBreakdown {
        val directory = modelsDir.also { it.mkdirs() }
        val installedBytes = directory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != IMPORT_STAGING_DIR }
            .sumOf { root ->
                root.walkTopDown().filter { it.isFile && it.extension.equals("gguf", true) }.sumOf { it.length() }
            }
        val downloadsDir = File(context.filesDir, "hf-downloads")
        val cacheBytes = if (downloadsDir.exists()) downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val stat = StatFs(directory.absolutePath)
        val freeBytes = stat.availableBytes
        val totalBytes = stat.totalBytes
        return StorageBreakdown(
            installedModelsBytes = installedBytes,
            downloadsCacheBytes = cacheBytes,
            freeStorageBytes = freeBytes,
            totalStorageBytes = totalBytes,
        )
    }

    fun clearCacheAndTempFiles(): Long = ModelStorageLifecycleGate.tryWithLock {
        var freed = 0L
        runCatching {
            val downloadsDir = File(context.filesDir, "hf-downloads")
            if (downloadsDir.exists()) {
                // A worker can legitimately own recent resumable files outside the import lock.
                val expiry = System.currentTimeMillis() - 24 * 3600 * 1000L
                downloadsDir.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { file ->
                    val bytes = file.length()
                    if (file.delete()) freed += bytes
                }
            }
            val stagingDir = File(modelsDir, IMPORT_STAGING_DIR)
            if (stagingDir.exists()) {
                val bytes = stagingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (stagingDir.deleteRecursively()) freed += bytes
            }
        }
        Log.d(TAG, "clearCacheAndTempFiles freed=$freed bytes")
        freed
    } ?: 0L

    private enum class HashPolicy {
        /** Never hash; used by listing and identity-snapshot paths. */
        NONE,

        /** Always recompute the full-file digest; used where the caller must not trust the cache. */
        ALWAYS,

        /**
         * Reuse a digest already computed for this artifact earlier in the process when it still
         * fingerprints the same (length + head/tail hash + size + mtime), else hash and cache.
         */
        CACHED,
    }

    private fun parseManifest(modelRoot: File, hashPolicy: HashPolicy): ModelResolveResult {
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
            val expectedSha = activeObj.getString("sha256")
            if (hashPolicy != HashPolicy.NONE) {
                val cachedMatch = hashPolicy == HashPolicy.CACHED &&
                    ModelArtifactVerificationCache.matches(modelFile, expectedSha)
                if (!cachedMatch) {
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
                    if (hashPolicy == HashPolicy.CACHED) {
                        ModelArtifactVerificationCache.remember(modelFile, actualSha)
                    }
                }
            }
            val recordedValidation = parseValidation(activeObj)
            val validation = recordedValidation
                ?.takeIf { hashPolicy == HashPolicy.NONE || it.status == METADATA_CHECKED }
                ?: validateGguf(modelFile)
                ?: return ModelResolveResult.Failure(
                    ModelStorageError(
                        ModelStorageError.Code.INVALID_GGUF_HEADER,
                        "Model $modelId has an unsupported GGUF version or invalid GGUF metadata",
                    )
                )
            val integrity = when (activeObj.optString("integrity", "")) {
                DownloadIntegrity.VERIFIED_PINNED.name -> DownloadIntegrity.VERIFIED_PINNED
                DownloadIntegrity.VERIFIED_PROVIDER_METADATA.name -> DownloadIntegrity.VERIFIED_PROVIDER_METADATA
                DownloadIntegrity.UNVERIFIED.name -> DownloadIntegrity.UNVERIFIED
                DownloadIntegrity.UNKNOWN_LEGACY.name -> DownloadIntegrity.UNKNOWN_LEGACY
                else -> DownloadIntegrity.UNKNOWN_LEGACY
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
                    integrity = integrity,
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
        integrity: DownloadIntegrity = DownloadIntegrity.UNKNOWN_LEGACY,
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
                .put("integrity", integrity.name)
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
        try {
            tmp.writeText(manifest.toString(2))
            moveAtomically(tmp, File(modelRoot, MANIFEST_FILE))
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun copyStream(
        input: InputStream,
        destination: File,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        var copied = 0L
        var lastEmittedBytes = 0L
        var lastProgressAt = 0L
        val total = totalBytes.takeIf { it > 0 }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        destination.outputStream().use { output ->
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                copied += read
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt > 500L) {
                    lastProgressAt = now
                    lastEmittedBytes = copied
                    onProgress(ImportProgress(copied, total))
                }
                if (copied > MAX_MODEL_BYTES) {
                    throw IOException("Model exceeds maximum size $MAX_MODEL_BYTES")
                }
                if (copied % (10L * 1024L * 1024L) < DEFAULT_BUFFER_SIZE && !hasUsableSpaceFor(0L)) {
                    throw IOException("Insufficient storage space during import")
                }
            }
        }
        if (copied != lastEmittedBytes) {
            onProgress(ImportProgress(copied, total))
        }
    }

    fun cleanOrphanedDownloads(maxAgeMs: Long = 24 * 3600 * 1000L) {
        runCatching {
            val downloadsDir = File(context.filesDir, "hf-downloads")
            if (!downloadsDir.exists()) return
            val now = System.currentTimeMillis()
            downloadsDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part") && (now - file.lastModified() > maxAgeMs)) {
                    file.delete()
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

    private fun validateGguf(file: File, checkCancelled: () -> Unit = {}): ModelValidation? = try {
        DataInputStream(file.inputStream().buffered(64 * 1024)).use { input ->
            val reader = GgufReader(input, file.length())
            if (reader.readAscii(4) != "GGUF") return@use null
            val version = reader.readU32()
            // The vendored gguf reader accepts little-endian GGUF v2 and v3.
            if (version !in 2..3) return@use null
            val metadata = parseGgufMetadata(reader, checkCancelled)
            ModelValidation(
                format = "GGUF",
                ggufVersion = version,
                status = METADATA_CHECKED,
                validatedAt = Instant.now().toString(),
                metadata = metadata,
            )
        }
    } catch (error: IOException) {
        Log.w(TAG, "GGUF metadata parse failed file=${file.name}", error)
        null
    }

    private fun parseValidation(activeObj: JSONObject): ModelValidation? {
        val validation = activeObj.optJSONObject("validation") ?: return null
        return ModelValidation(
            format = validation.optString("format", "GGUF"),
            ggufVersion = validation.optInt("gguf_version", -1),
            status = if (validation.optString("status") == METADATA_CHECKED) METADATA_CHECKED else "legacy_header_only",
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

    private fun parseGgufMetadata(reader: GgufReader, checkCancelled: () -> Unit): GgufMetadataSummary {
        reader.readCount(reader.remaining / 24, "tensor count")
        val kvCount = reader.readCount(minOf(4096L, reader.remaining / 13), "metadata count")
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
            checkCancelled()
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

    private class GgufReader(private val input: DataInputStream, size: Long) {
        private val scratch = ByteArray(8)
        private var metadataRead = 0L
        var remaining: Long = size
            private set

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
            val length = readCount(minOf(1_000_000L, remaining), "string length").toInt()
            val bytes = ByteArray(length)
            readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        fun readCount(maximum: Long, label: String): Long = readU64().also {
            if (it !in 0..maximum) throw IOException("Invalid GGUF $label")
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
                    val elementBytes = metadataElementBytes(elementType)
                    val count = readCount(minOf(1_000_000L, remaining / elementBytes), "array length")
                    if (elementType == 8) {
                        repeat(count.toInt()) { skipFully(readU64()) }
                    } else {
                        skipFully(count * elementBytes)
                    }
                    null
                }
                10, 11, 12 -> readScalar(8)
                else -> throw IOException("Invalid GGUF metadata type")
            }

        private fun metadataElementBytes(type: Int): Long = when (type) {
            0, 1, 7 -> 1
            2, 3 -> 2
            4, 5, 6 -> 4
            8, 10, 11, 12 -> 8
            else -> throw IOException("Invalid GGUF array element type")
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
            checkMetadataBudget(length.toLong())
            input.readFully(buffer, 0, length)
            remaining -= length
        }

        private fun skipFully(bytes: Long) {
            if (bytes !in 0..remaining) throw IOException("Truncated GGUF metadata")
            checkMetadataBudget(bytes)
            var pending = bytes
            while (pending > 0) {
                val skipped = input.skip(pending)
                if (skipped == 0L) {
                    if (input.read() == -1) throw IOException("Truncated GGUF metadata")
                    pending--
                } else {
                    pending -= skipped
                }
            }
            remaining -= bytes
        }

        private fun checkMetadataBudget(bytes: Long) {
            metadataRead += bytes
            if (metadataRead > 128L * 1024 * 1024) throw IOException("GGUF metadata exceeds the import limit")
        }
    }

    private fun sha256(file: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun freeSpaceBytes(): Long {
        val dir = modelsDir.also { it.mkdirs() }
        return StatFs(dir.absolutePath).availableBytes
    }

    private fun hasUsableSpaceFor(bytes: Long): Boolean =
        hasUsableSpaceFor(freeSpaceBytes(), bytes)

    private fun hasReserveSpace(): Boolean =
        hasReserveAfterCopy(freeSpaceBytes())

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
        return modelIdFromDisplayName(displayName)
    }

    private fun newVersionId(): String = "v${UUID.randomUUID()}"

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

    companion object {
        internal fun modelIdFromDisplayName(displayName: String): String {
            val withoutExtension = displayName.removeSuffix(".gguf")
            val cleaned = withoutExtension.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_')
            return cleaned.ifBlank { "imported-model" }
        }

        /**
         * True when [freeBytes] covers a model of [modelBytes] plus the post-import
         * reserve. Used before the copy, where the model's bytes are still to be
         * written.
         */
        fun hasUsableSpaceFor(freeBytes: Long, modelBytes: Long): Boolean =
            freeBytes > modelBytes + MIN_FREE_SPACE_AFTER_IMPORT

        /**
         * True when [freeBytes] still covers the post-import reserve. Used after the
         * staged copy has landed: those bytes are already on disk, and
         * promoteDirectory is a rename that consumes no additional space, so
         * requiring the model size again rejected models that genuinely fit.
         */
        fun hasReserveAfterCopy(freeBytes: Long): Boolean =
            freeBytes > MIN_FREE_SPACE_AFTER_IMPORT

        const val TAG = "ModelStorageManager"
        private const val METADATA_CHECKED = "metadata_checked"
        const val MANIFEST_FILE = "manifest.json"
        const val MODEL_FILE = "model.gguf"
        const val IMPORT_STAGING_DIR = ".imports"
        const val MIN_GGUF_BYTES = 32L
        const val MAX_MODEL_BYTES = 32L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_AFTER_IMPORT = 512L * 1024L * 1024L
    }
}
