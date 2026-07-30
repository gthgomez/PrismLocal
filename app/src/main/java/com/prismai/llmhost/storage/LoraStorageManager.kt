package com.prismai.llmhost.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class LoraStorageManager(private val context: Context) {
    companion object {
        private const val TAG = "LoraStorageManager"
        private const val LORA_DIR_NAME = "lora"
    }

    private val loraDir: File by lazy {
        File(context.filesDir, LORA_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun getAvailableAdapters(): List<File> {
        return loraDir.listFiles { file ->
            file.isFile && (file.extension.equals("gguf", ignoreCase = true) || file.extension.equals("bin", ignoreCase = true))
        }?.toList() ?: emptyList()
    }

    fun importLoraAdapter(uri: Uri): Result<File> {
        return runCatching {
            val fileName = queryFileName(uri) ?: "adapter_${System.currentTimeMillis()}.gguf"
            val destinationFile = File(loraDir, fileName)
            val tempFile = File(loraDir, "$fileName.tmp")

            Log.d(TAG, "Importing LoRA adapter from SAF URI: $uri to ${destinationFile.absolutePath}")

            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("Failed to open input stream for URI: $uri")

                if (!tempFile.renameTo(destinationFile)) {
                    // Fallback to manual move if renameTo fails across file systems
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                throw e
            }

            Log.i(TAG, "Successfully imported LoRA adapter: ${destinationFile.name} (${destinationFile.length()} bytes)")
            destinationFile
        }.onFailure { ex ->
            Log.e(TAG, "Failed to import LoRA adapter from URI $uri", ex)
        }
    }

    fun deleteAdapter(file: File): Boolean {
        return if (file.exists() && file.parentFile == loraDir) {
            val deleted = file.delete()
            Log.d(TAG, "Deleted LoRA adapter ${file.name}: success=$deleted")
            deleted
        } else {
            false
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        return cursor.getString(index)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
