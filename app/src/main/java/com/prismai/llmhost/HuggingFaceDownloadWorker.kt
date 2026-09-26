package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*
import com.prismai.llmhost.util.MAX_REMOTE_RESPONSE_CHARS
import com.prismai.llmhost.util.readTextBounded

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object HuggingFaceDownloadWork {
    const val UNIQUE_WORK_NAME = "hugging-face-model-download"
    const val KEY_ENTRY_ID = "entry_id"
    const val KEY_ENTRY_NAME = "entry_name"
    const val KEY_STAGE = "stage"
    const val KEY_BYTES_DONE = "bytes_done"
    const val KEY_TOTAL_BYTES = "total_bytes"
    const val KEY_MESSAGE = "message"
    const val KEY_MODEL_ID = "model_id"
    const val KEY_MODEL_BYTES = "model_bytes"
    const val KEY_MODEL_SHA256 = "model_sha256"
    const val MODEL_OWNER_TAG_PREFIX = "model_storage_owner:"

    /** Download integrity outcome: [INTEGRITY_VERIFIED] or [INTEGRITY_UNVERIFIED]. */
    const val KEY_INTEGRITY = "integrity"
    const val INTEGRITY_VERIFIED = "verified"
    const val INTEGRITY_UNVERIFIED = "unverified"

    fun request(entryId: String): OneTimeWorkRequest {
        val ownerTag = HuggingFaceModelCatalog.find(entryId)?.let { entry ->
            MODEL_OWNER_TAG_PREFIX + ModelStorageManager.modelIdFromDisplayName(entry.fileName)
        }
        return OneTimeWorkRequestBuilder<HuggingFaceDownloadWorker>()
            .setInputData(workDataOf(KEY_ENTRY_ID to entryId))
            .apply { ownerTag?.let { tag -> addTag(tag) } }
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}

class HuggingFaceDownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private data class RemoteFileMetadata(
        val sizeBytes: Long?,
        val sha256: String?,
    )

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(HuggingFaceDownloadWork.KEY_ENTRY_ID)
            ?: return Result.failure(workDataOf(HuggingFaceDownloadWork.KEY_MESSAGE to "Missing catalog entry id"))
        val entry = HuggingFaceModelCatalog.find(entryId)
            ?: return Result.failure(workDataOf(HuggingFaceDownloadWork.KEY_MESSAGE to "Model catalog entry not found"))
        val storage = ModelStorageManager(appContext)
        val storageRevision = storage.captureLifecycleRevisionForDisplayName(entry.fileName)

        setForeground(downloadForegroundInfo(entry, "Queued"))
        setDownloadProgress(entry, ModelDownloadState.Running.Stage.QUEUED, 0L, entry.expectedBytes, "Waiting for network")

        val downloadsDir = File(appContext.filesDir, "hf-downloads").also { it.mkdirs() }
        cleanupStalePartialFiles(downloadsDir)
        val partialFile = File(downloadsDir, "${entry.id}.part")

        return try {
            setDownloadProgress(
                entry = entry,
                stage = ModelDownloadState.Running.Stage.VERIFYING_METADATA,
                bytesDone = partialFile.length(),
                totalBytes = entry.expectedBytes,
                message = "Checking Hugging Face file metadata",
            )
            val remoteMetadata = fetchRemoteMetadata(entry)
            val expectedSize = remoteMetadata.sizeBytes ?: entry.expectedBytes.takeIf { it > 0L }
            val expectedSha = remoteMetadata.sha256 ?: entry.expectedSha256
            val integrityDecision = decideDownloadIntegrity(expectedSha, entry.curated)
            if (integrityDecision == DownloadIntegrityDecision.FAIL_CLOSED) {
                throw IllegalStateException(
                    "No trusted SHA-256 available for ${entry.name}; refusing to import an unverified model."
                )
            }
            val shaToVerify = if (integrityDecision == DownloadIntegrityDecision.VERIFY_SHA256) expectedSha else null

            downloadResumable(entry, partialFile, expectedSize)

            verifyCompletedDownload(entry, partialFile, expectedSize, shaToVerify)

            setDownloadProgress(
                entry = entry,
                stage = ModelDownloadState.Running.Stage.IMPORTING,
                bytesDone = 0L,
                totalBytes = partialFile.length(),
                message = if (integrityDecision == DownloadIntegrityDecision.VERIFY_SHA256) "Installing verified GGUF" else "Installing GGUF (unverified)",
            )
            val importResult = partialFile.inputStream().use { input ->
                storage.importModelFromStream(
                    displayName = entry.fileName,
                    reportedSize = partialFile.length(),
                    input = input,
                    onProgress = { progress ->
                        // importModelFromStream is synchronous, so the suspend progress call is
                        // wrapped here. copyStream throttles emissions to ~500 ms (plus one final
                        // 100% emission), keeping these runBlocking commits rare.
                        runBlocking {
                            setDownloadProgress(
                                entry = entry,
                                stage = ModelDownloadState.Running.Stage.IMPORTING,
                                bytesDone = progress.bytesCopied,
                                totalBytes = progress.totalBytes,
                                message = if (integrityDecision == DownloadIntegrityDecision.VERIFY_SHA256) "Installing verified GGUF" else "Installing GGUF (unverified)",
                            )
                        }
                    },
                    expectedLifecycleRevision = storageRevision,
                )
            }
            when (importResult) {
                is ModelStorageManager.ImportResult.Failure -> {
                    partialFile.delete()
                    Result.failure(
                        workDataOf(
                            HuggingFaceDownloadWork.KEY_ENTRY_NAME to entry.name,
                            HuggingFaceDownloadWork.KEY_MESSAGE to importResult.error.userMessage,
                        )
                    )
                }
                is ModelStorageManager.ImportResult.Success -> {
                    partialFile.delete()
                    Result.success(
                        workDataOf(
                            HuggingFaceDownloadWork.KEY_ENTRY_ID to entry.id,
                            HuggingFaceDownloadWork.KEY_ENTRY_NAME to entry.name,
                            HuggingFaceDownloadWork.KEY_MODEL_ID to importResult.model.id,
                            HuggingFaceDownloadWork.KEY_MODEL_BYTES to importResult.model.bytes,
                            HuggingFaceDownloadWork.KEY_MODEL_SHA256 to importResult.model.sha256,
                            HuggingFaceDownloadWork.KEY_INTEGRITY to if (integrityDecision == DownloadIntegrityDecision.VERIFY_SHA256) {
                                HuggingFaceDownloadWork.INTEGRITY_VERIFIED
                            } else {
                                HuggingFaceDownloadWork.INTEGRITY_UNVERIFIED
                            },
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            partialFile.delete()
            Result.failure(
                workDataOf(
                    HuggingFaceDownloadWork.KEY_ENTRY_ID to entry.id,
                    HuggingFaceDownloadWork.KEY_ENTRY_NAME to entry.name,
                    HuggingFaceDownloadWork.KEY_MESSAGE to (e.message ?: e::class.java.simpleName),
                )
            )
        }
    }

    private suspend fun fetchRemoteMetadata(entry: HuggingFaceModelEntry): RemoteFileMetadata {
        val apiMetadata = runCatching { fetchApiMetadata(entry) }.getOrNull()
        if (apiMetadata?.sizeBytes != null || apiMetadata?.sha256 != null) {
            return apiMetadata
        }
        val pointerMetadata = runCatching { fetchRawPointerMetadata(entry) }.getOrNull()
        if (pointerMetadata?.sizeBytes != null || pointerMetadata?.sha256 != null) {
            return pointerMetadata
        }
        return RemoteFileMetadata(entry.expectedBytes, entry.expectedSha256)
    }

    private fun fetchApiMetadata(entry: HuggingFaceModelEntry): RemoteFileMetadata {
        val response = openTextConnection(entry.apiUrl)
        val root = JSONObject(response)
        val siblings = root.optJSONArray("siblings") ?: return RemoteFileMetadata(null, null)
        for (index in 0 until siblings.length()) {
            val file = siblings.optJSONObject(index) ?: continue
            if (file.optString("rfilename") != entry.fileName) continue
            val lfs = file.optJSONObject("lfs")
            val size = lfs?.optLong("size", -1L)?.takeIf { it > 0L }
                ?: file.optLong("size", -1L).takeIf { it > 0L }
            // The HF API exposes the LFS SHA-256 as `lfs.sha256`; older payloads used `lfs.oid`.
            val sha256 = lfs?.optString("sha256")?.takeIf { it.length == 64 }
                ?: lfs?.optString("oid")?.takeIf { it.length == 64 }
                ?: file.optString("sha256").takeIf { it.length == 64 }
                ?: file.optString("oid").takeIf { it.length == 64 }
            return RemoteFileMetadata(size, sha256?.lowercase(Locale.US))
        }
        return RemoteFileMetadata(null, null)
    }

    private fun fetchRawPointerMetadata(entry: HuggingFaceModelEntry): RemoteFileMetadata {
        val pointer = openTextConnection(entry.rawPointerUrl, maxChars = 4096)
        var size: Long? = null
        var sha256: String? = null
        pointer.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("oid sha256:")) {
                sha256 = trimmed.removePrefix("oid sha256:").lowercase(Locale.US).takeIf { it.length == 64 }
            }
            if (trimmed.startsWith("size ")) {
                size = trimmed.removePrefix("size ").toLongOrNull()
            }
        }
        return RemoteFileMetadata(size, sha256)
    }

    private fun openTextConnection(url: String, maxChars: Int = MAX_REMOTE_RESPONSE_CHARS): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PrismLocalAndroid/1.0")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from Hugging Face metadata")
            }
            connection.inputStream.bufferedReader().use { reader ->
                reader.readTextBounded(maxChars)
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadResumable(entry: HuggingFaceModelEntry, target: File, expectedSize: Long?) {
        var existing = target.length().coerceAtLeast(0L)
        val connection = (URL(entry.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PrismLocalAndroid/1.0")
            if (existing > 0L) {
                setRequestProperty("Range", "bytes=$existing-")
            }
        }
        try {
            val code = connection.responseCode
            if (code == HTTP_REQUESTED_RANGE_NOT_SATISFIABLE) {
                if (expectedSize != null && existing == expectedSize) return
                target.delete()
                if (existing == 0L) throw IllegalStateException("HTTP 416: Invalid range requested for empty file")
                return downloadResumable(entry, target, expectedSize)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from Hugging Face download")
            }
            val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                target.delete()
                existing = 0L
            }
            val total = expectedSize ?: parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                ?: connection.contentLengthLong.takeIf { it > 0L }
            var copied = existing
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var lastProgressAt = 0L
            FileOutputStream(target, append).use { output ->
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        copied += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt > 500L || copied == total) {
                            lastProgressAt = now
                            setDownloadProgress(
                                entry = entry,
                                stage = ModelDownloadState.Running.Stage.DOWNLOADING,
                                bytesDone = copied,
                                totalBytes = total,
                                message = if (existing > 0L && append) "Resumed at ${formatWorkerBytes(existing)}" else "Downloading",
                            )
                            setForeground(downloadForegroundInfo(entry, "${formatWorkerBytes(copied)} / ${total?.let(::formatWorkerBytes) ?: "unknown"}"))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verifyCompletedDownload(
        entry: HuggingFaceModelEntry,
        file: File,
        expectedSize: Long?,
        expectedSha256: String?,
    ) {
        val size = file.length()
        if (expectedSize != null && size != expectedSize) {
            throw IllegalStateException("Downloaded size mismatch: got ${formatWorkerBytes(size)}, expected ${formatWorkerBytes(expectedSize)}")
        }
        setDownloadProgress(
            entry = entry,
            stage = ModelDownloadState.Running.Stage.VERIFYING_FILE,
            bytesDone = 0L,
            totalBytes = size,
            message = if (expectedSha256 == null) "Verifying file size (no SHA-256 available)" else "Verifying SHA-256",
        )
        expectedSha256 ?: return
        val actual = sha256(file) { bytesDone ->
            setDownloadProgress(
                entry = entry,
                stage = ModelDownloadState.Running.Stage.VERIFYING_FILE,
                bytesDone = bytesDone,
                totalBytes = size,
                message = "Verifying SHA-256",
            )
        }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("Downloaded SHA-256 mismatch")
        }
    }

    private suspend fun sha256(file: File, onProgress: suspend (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var lastProgressAt = 0L
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                currentCoroutineContext().ensureActive()
                digest.update(buffer, 0, read)
                total += read
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt > 500L) {
                    lastProgressAt = now
                    onProgress(total)
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun parseContentRangeTotal(value: String?): Long? =
        value?.substringAfter('/', missingDelimiterValue = "")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    private suspend fun setDownloadProgress(
        entry: HuggingFaceModelEntry,
        stage: ModelDownloadState.Running.Stage,
        bytesDone: Long,
        totalBytes: Long?,
        message: String?,
    ) {
        setProgress(
            workDataOf(
                HuggingFaceDownloadWork.KEY_ENTRY_ID to entry.id,
                HuggingFaceDownloadWork.KEY_ENTRY_NAME to entry.name,
                HuggingFaceDownloadWork.KEY_STAGE to stage.name,
                HuggingFaceDownloadWork.KEY_BYTES_DONE to bytesDone,
                HuggingFaceDownloadWork.KEY_TOTAL_BYTES to (totalBytes ?: -1L),
                HuggingFaceDownloadWork.KEY_MESSAGE to message.orEmpty(),
            )
        )
    }

    private fun downloadForegroundInfo(entry: HuggingFaceModelEntry, status: String): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${entry.name}")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun formatWorkerBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private companion object {
        private const val CHANNEL_ID = "llm_model_downloads"
        private const val NOTIFICATION_ID = 2001
        private const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416
    }
}

fun enqueueHuggingFaceDownload(context: Context, entryId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        HuggingFaceDownloadWork.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        HuggingFaceDownloadWork.request(entryId),
    )
}

private fun cleanupStalePartialFiles(downloadsDir: File, maxAgeMs: Long = 24 * 3600 * 1000L) {
    runCatching {
        val now = System.currentTimeMillis()
        downloadsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") && (now - file.lastModified() > maxAgeMs)) {
                file.delete()
            }
        }
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun ByteArray.toHex(): String {
    val result = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        result[i * 2] = HEX_CHARS[b ushr 4]
        result[i * 2 + 1] = HEX_CHARS[b and 0x0F]
    }
    return String(result)
}
