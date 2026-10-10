package com.prismai.llmhost

import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable download engine with byte range validation, ETag artifact change detection,
 * storage reserve enforcement, and maximum size bounds.
 */
class ResumableDownloadEngine(
    private val maxModelBytes: Long = ModelStorageManager.MAX_MODEL_BYTES,
    private val minStorageReserveBytes: Long = 100L * 1024L * 1024L,
    private val getUsableSpace: (File) -> Long = { it.usableSpace },
    private val getElapsedRealtime: () -> Long = { System.currentTimeMillis() },
) {
    data class ContentRangeInfo(val start: Long, val end: Long, val total: Long?)

    companion object {
        const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416
        private const val DEFAULT_BUFFER_SIZE = 8192

        fun parseContentRange(value: String?): ContentRangeInfo? {
            if (value == null) return null
            val match = Regex("""bytes\s+(\d+)-(\d+)/(?:(\d+)|\*)""", RegexOption.IGNORE_CASE).find(value.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            // Reject malformed ranges rather than trusting them: an end before the
            // start, or an end at/beyond a declared total, cannot describe a real
            // byte range and would corrupt an append.
            if (end < start) return null
            if (total != null && end >= total) return null
            return ContentRangeInfo(start, end, total)
        }

        fun parseContentRangeTotal(value: String?): Long? =
            parseContentRange(value)?.total
                ?: value?.substringAfter('/', missingDelimiterValue = "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
    }

    suspend fun downloadResumable(
        downloadUrl: String,
        target: File,
        expectedSize: Long?,
        onProgress: (suspend (bytesDone: Long, total: Long?, resumedFrom: Long?) -> Unit)? = null,
    ) {
        val targetDir = target.parentFile ?: target
        targetDir.mkdirs()
        val metaFile = File(targetDir, "${target.name}.meta")
        var existing = target.length().coerceAtLeast(0L)

        if (expectedSize != null && existing > expectedSize) {
            target.delete()
            metaFile.delete()
            existing = 0L
        }

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
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
                metaFile.delete()
                if (existing == 0L) throw IllegalStateException("HTTP 416: Invalid range requested for empty file")
                return downloadResumable(downloadUrl, target, expectedSize, onProgress)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from Hugging Face download")
            }

            val currentEtag = connection.getHeaderField("ETag")?.trim('"', ' ')
            val currentLastModified = connection.getHeaderField("Last-Modified")
            val metaJson = if (existing > 0L && metaFile.exists()) {
                runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            } else {
                null
            }
            val savedEtag = metaJson?.optString("etag")?.takeIf { it.isNotBlank() }
            val savedLastModified = metaJson?.optString("last_modified")?.takeIf { it.isNotBlank() }
            val savedExpectedSize = metaJson?.optLong("expected_size", -1L)?.takeIf { it > 0L }
            if ((savedEtag != null && currentEtag != null && savedEtag != currentEtag) ||
                (savedLastModified != null && currentLastModified != null && savedLastModified != currentLastModified) ||
                (savedExpectedSize != null && expectedSize != null && savedExpectedSize != expectedSize)) {
                target.delete()
                metaFile.delete()
                return downloadResumable(downloadUrl, target, expectedSize, onProgress)
            }
            // An append may only proceed when the already-downloaded prefix can be
            // tied to the same remote artifact. A saved validator that the new
            // response does not corroborate — a missing ETag/Last-Modified, or no
            // validator at all — cannot establish continuity, so the partial file
            // is discarded and re-fetched rather than silently concatenating
            // bytes from a possibly different artifact.
            val continuityEstablished = metaJson != null &&
                (savedEtag != null || savedLastModified != null) &&
                (savedEtag == null || currentEtag == savedEtag) &&
                (savedLastModified == null || currentLastModified == savedLastModified) &&
                (savedExpectedSize == null || expectedSize == null || savedExpectedSize == expectedSize)

            var append = false
            var contentRange: ContentRangeInfo? = null
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                contentRange = parseContentRange(connection.getHeaderField("Content-Range"))
                    ?: throw IllegalStateException("HTTP 206 without a valid Content-Range header")
                if (existing > 0L) {
                    if (contentRange.start == existing && continuityEstablished) {
                        append = true
                    } else {
                        target.delete()
                        metaFile.delete()
                        return downloadResumable(downloadUrl, target, expectedSize, onProgress)
                    }
                } else {
                    if (contentRange.start != 0L) {
                        target.delete()
                        metaFile.delete()
                        throw IllegalStateException("HTTP 206 began at byte ${contentRange.start} for a fresh download")
                    }
                    append = false
                    existing = 0L
                }
            } else {
                target.delete()
                metaFile.delete()
                existing = 0L
            }

            val rangeTotal = contentRange?.total ?: parseContentRangeTotal(connection.getHeaderField("Content-Range"))
            if (rangeTotal != null && expectedSize != null && rangeTotal != expectedSize) {
                target.delete()
                metaFile.delete()
                throw IllegalStateException("Remote model size changed: expected $expectedSize, but server reported total $rangeTotal")
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            if (contentLength != null && expectedSize != null && code == HttpURLConnection.HTTP_OK && contentLength != expectedSize) {
                target.delete()
                metaFile.delete()
                throw IllegalStateException("Remote model size changed: expected $expectedSize, but server reported Content-Length $contentLength")
            }

            val total = expectedSize ?: rangeTotal ?: connection.contentLengthLong.takeIf { it > 0L }
            if (total != null && total > maxModelBytes) {
                target.delete()
                metaFile.delete()
                throw IllegalStateException("Model exceeds maximum size limit ($total > $maxModelBytes)")
            }

            val neededBytes = (total?.let { it - existing } ?: connection.contentLengthLong.takeIf { it > 0L } ?: 0L).coerceAtLeast(0L)
            val usableSpace = getUsableSpace(targetDir)
            if (usableSpace < neededBytes + minStorageReserveBytes) {
                target.delete()
                metaFile.delete()
                throw IllegalStateException(
                    "Insufficient storage space: usable $usableSpace bytes, needed $neededBytes bytes + $minStorageReserveBytes bytes reserve"
                )
            }

            runCatching {
                val meta = JSONObject()
                    .put("etag", currentEtag ?: "")
                    .put("last_modified", currentLastModified ?: "")
                    .put("expected_size", total ?: -1L)
                metaFile.writeText(meta.toString())
            }

            var copied = existing
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var lastProgressAt = 0L
            var lastDiskCheckAt = 0L
            FileOutputStream(target, append).use { output ->
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        copied += read

                        if (copied > maxModelBytes) {
                            target.delete()
                            metaFile.delete()
                            throw IllegalStateException("Download exceeds maximum model size ($copied > $maxModelBytes)")
                        }
                        if (total != null && copied > total) {
                            target.delete()
                            metaFile.delete()
                            throw IllegalStateException("Downloaded bytes ($copied) exceeded announced total ($total)")
                        }

                        val now = getElapsedRealtime()
                        if (now - lastDiskCheckAt > 5_000L) {
                            lastDiskCheckAt = now
                            if (getUsableSpace(targetDir) < minStorageReserveBytes) {
                                target.delete()
                                metaFile.delete()
                                throw IllegalStateException("Device storage critically low (< $minStorageReserveBytes bytes); aborting download")
                            }
                        }

                        if (now - lastProgressAt > 500L || copied == total) {
                            lastProgressAt = now
                            onProgress?.invoke(copied, total, if (existing > 0L && append) existing else null)
                        }
                    }
                }
            }

            // Completion postconditions. A stream that ends early (a truncated
            // 206 or a short 200) must fail rather than leave a partial file that
            // looks complete. Enforce the exact declared range when the server
            // sent one, and the announced total when known.
            val declaredRangeEnd = contentRange?.end
            if (declaredRangeEnd != null && copied != declaredRangeEnd + 1) {
                target.delete()
                metaFile.delete()
                throw IllegalStateException(
                    "Incomplete download: received $copied bytes but the server declared content through byte $declaredRangeEnd"
                )
            }
            if (total != null && copied != total) {
                target.delete()
                metaFile.delete()
                throw IllegalStateException("Incomplete download: received $copied bytes of $total")
            }
        } finally {
            connection.disconnect()
        }
    }
}
