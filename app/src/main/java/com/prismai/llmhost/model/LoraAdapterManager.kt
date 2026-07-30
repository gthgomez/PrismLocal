package com.prismai.llmhost.model

import android.content.Context
import com.prismai.llmhost.bridge.NativeLlmBridge
import java.io.File

data class LoraAdapterInfo(
    val id: String,
    val name: String,
    val file: File,
    val scale: Float = 1.0f,
    val isEnabled: Boolean = false,
)

class LoraAdapterManager(private val context: Context) {

    private val lorasDir: File by lazy {
        File(context.filesDir, "models/loras").also { it.mkdirs() }
    }

    /**
     * Scans the app's `models/loras` directory for valid `.gguf` and `.bin` adapter files.
     */
    fun discoverAdapters(): List<LoraAdapterInfo> {
        val files = lorasDir.listFiles { file ->
            file.isFile && (file.extension.equals("gguf", ignoreCase = true) || file.extension.equals("bin", ignoreCase = true))
        } ?: emptyArray()

        return files.map { file ->
            LoraAdapterInfo(
                id = file.nameWithoutExtension,
                name = file.nameWithoutExtension.replace('_', ' ').replace('-', ' '),
                file = file,
                scale = 1.0f,
                isEnabled = false,
            )
        }
    }

    /**
     * Applies a list of active LoRA adapters and scaling factors to the native engine bridge.
     */
    suspend fun applyAdapters(bridge: NativeLlmBridge, activeAdapters: List<LoraAdapterInfo>): Boolean {
        val enabled = activeAdapters.filter { it.isEnabled && it.file.exists() }
        if (enabled.isEmpty()) {
            bridge.clearLoraAdapters()
            return true
        }

        val adapterPairs = enabled.map { it.file.absolutePath to it.scale }
        return bridge.applyLoraAdapters(adapterPairs)
    }

    /**
     * Clears all applied LoRA adapters from the native engine.
     */
    suspend fun clearAdapters(bridge: NativeLlmBridge) {
        bridge.clearLoraAdapters()
    }
}
