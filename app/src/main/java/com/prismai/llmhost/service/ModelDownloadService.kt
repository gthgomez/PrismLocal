package com.prismai.llmhost.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.prismai.llmhost.HuggingFaceModelCatalog
import com.prismai.llmhost.HuggingFaceModelEntry
import com.prismai.llmhost.MainActivity
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ModelDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var activeDownloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)

        when (action) {
            ACTION_START_DOWNLOAD -> {
                if (!entryId.isNullOrBlank()) {
                    val entry = HuggingFaceModelCatalog.find(entryId)
                    if (entry != null) {
                        startDownload(entry)
                    }
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload()
            }
        }
        return START_STICKY
    }

    private fun startDownload(entry: HuggingFaceModelEntry) {
        activeDownloadJob?.cancel()
        createNotificationChannel()

        val notification = buildNotification(entry, 0L, entry.expectedBytes, "Preparing download...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        activeDownloadJob = serviceScope.launch {
            val downloadsDir = File(filesDir, "hf-downloads").also { it.mkdirs() }
            val partialFile = File(downloadsDir, "${entry.id}.part")

            try {
                updateProgress(entry, ModelDownloadState.Running.Stage.VERIFYING_METADATA, partialFile.length(), entry.expectedBytes, "Checking metadata")
                val remoteMetadata = fetchRemoteMetadata(entry)
                val expectedSize = remoteMetadata.sizeBytes ?: entry.expectedBytes

                downloadResumable(entry, partialFile, expectedSize)

                updateProgress(entry, ModelDownloadState.Running.Stage.IMPORTING, 0L, partialFile.length(), "Installing GGUF model")
                val storage = ModelStorageManager(applicationContext)
                val importResult = partialFile.inputStream().use { input ->
                    storage.importModelFromStream(entry.fileName, partialFile.length(), input) { progress ->
                        runBlocking {
                            updateProgress(entry, ModelDownloadState.Running.Stage.IMPORTING, progress.bytesCopied, progress.totalBytes, "Installing GGUF model")
                        }
                    }
                }

                partialFile.delete()
                when (importResult) {
                    is ModelStorageManager.ImportResult.Success -> {
                        _downloadState.value = ModelDownloadState.Success(importResult.model.id, entry.name)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    is ModelStorageManager.ImportResult.Failure -> {
                        _downloadState.value = ModelDownloadState.Failure(entry.name, importResult.error.userMessage)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _downloadState.value = ModelDownloadState.Cancelled
                } else {
                    _downloadState.value = ModelDownloadState.Failure(entry.name, e.message ?: "Download failed")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _downloadState.value = ModelDownloadState.Cancelled
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            if (code == 416 /* HTTP 416 Range Not Satisfiable */) {
                if (expectedSize != null && existing == expectedSize) return
                target.delete()
                if (existing == 0L) throw IllegalStateException("HTTP 416: Invalid range for empty file")
                return downloadResumable(entry, target, expectedSize)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code downloading GGUF model")
            }

            val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                target.delete()
                existing = 0L
            }

            val total = expectedSize ?: parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                ?: connection.contentLengthLong.takeIf { it > 0L }
            var copied = existing
            val buffer = ByteArray(8192)
            var lastProgressAt = 0L

            FileOutputStream(target, append).use { output ->
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt > 500L || copied == total) {
                            lastProgressAt = now
                            updateProgress(entry, ModelDownloadState.Running.Stage.DOWNLOADING, copied, total, "Downloading GGUF model")
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun updateProgress(
        entry: HuggingFaceModelEntry,
        stage: ModelDownloadState.Running.Stage,
        bytesDone: Long,
        totalBytes: Long?,
        message: String
    ) {
        _downloadState.value = ModelDownloadState.Running(entry, stage, bytesDone, totalBytes, message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(entry, bytesDone, totalBytes, message))
    }

    private fun fetchRemoteMetadata(entry: HuggingFaceModelEntry): RemoteFileMetadata {
        val apiMetadata = runCatching { fetchApiMetadata(entry) }.getOrNull()
        if (apiMetadata?.sizeBytes != null || apiMetadata?.sha256 != null) {
            return apiMetadata
        }
        return RemoteFileMetadata(entry.expectedBytes, entry.expectedSha256)
    }

    private fun fetchApiMetadata(entry: HuggingFaceModelEntry): RemoteFileMetadata {
        val connection = (URL(entry.apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "PrismLocalAndroid/1.0")
        }
        return try {
            if (connection.responseCode in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val siblings = root.optJSONArray("siblings") ?: return RemoteFileMetadata(null, null)
                for (i in 0 until siblings.length()) {
                    val file = siblings.optJSONObject(i) ?: continue
                    if (file.optString("rfilename") != entry.fileName) continue
                    val lfs = file.optJSONObject("lfs")
                    val size = lfs?.optLong("size", -1L)?.takeIf { it > 0L } ?: file.optLong("size", -1L).takeIf { it > 0L }
                    val oid = lfs?.optString("oid")?.takeIf { it.length == 64 } ?: file.optString("oid").takeIf { it.length == 64 }
                    return RemoteFileMetadata(size, oid?.lowercase(Locale.US))
                }
            }
            RemoteFileMetadata(null, null)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrEmpty()) return null
        val match = Regex("""bytes \d+-\d+/(\d+)""", RegexOption.IGNORE_CASE).find(header)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground GGUF Model Download Progress"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        entry: HuggingFaceModelEntry,
        bytesDone: Long,
        totalBytes: Long?,
        message: String
    ): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressCurrent = if (totalBytes != null && totalBytes > 0) ((bytesDone * 100) / totalBytes).toInt() else 0
        val isIndeterminate = totalBytes == null || totalBytes <= 0L

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading ${entry.name}")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progressCurrent, isIndeterminate)
            .build()
    }

    private data class RemoteFileMetadata(val sizeBytes: Long?, val sha256: String?)

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "model_download_channel"
        const val ACTION_START_DOWNLOAD = "com.prismai.llmhost.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.prismai.llmhost.action.CANCEL_DOWNLOAD"
        const val EXTRA_ENTRY_ID = "extra_entry_id"

        private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
        val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

        fun start(context: Context, entryId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_ENTRY_ID, entryId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }
    }
}
