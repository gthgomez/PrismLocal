package com.prismai.llmhost.ui.onboarding

import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.ui.formatBytes

/**
 * Pure first-run decisions shared by the onboarding card and its unit tests.
 *
 * The UI renders whatever this returns; do not duplicate the ordering of
 * "ready / error / downloading / choose" states in the composable.
 */
internal enum class FirstRunStage {
    CHOOSE_MODEL,
    DOWNLOADING,
    READY,
    ERROR,
}

internal data class FirstRunSnapshot(
    val stage: FirstRunStage,
    val title: String,
    val detail: String,
)

internal object FirstRunGuidance {

    /** Keep roughly the model size again for the temporary download plus install. */
    const val STORAGE_HEADROOM_BYTES = 512L * 1024L * 1024L

    /** Runtime overhead floor used only for a first-run expectation, not admission. */
    const val MIN_RAM_HEADROOM_BYTES = 768L * 1024L * 1024L

    fun canStartChat(currentModel: String?): Boolean = !currentModel.isNullOrBlank()

    fun hasStorageFor(expectedBytes: Long, freeBytes: Long?): Boolean {
        if (expectedBytes <= 0L) return true
        val free = freeBytes ?: return true
        return free > expectedBytes * 2L + STORAGE_HEADROOM_BYTES
    }

    fun estimatedRamNeedBytes(modelBytes: Long): Long =
        (modelBytes * 1.35).toLong().coerceAtLeast(MIN_RAM_HEADROOM_BYTES)

    /**
     * A deliberately conservative first-run hint. It never blocks a download:
     * the authoritative fit check still runs in ModelReadinessAssessor.
     */
    fun ramFitLabel(expectedBytes: Long, availableRamBytes: Long?): String {
        val available = availableRamBytes ?: return "RAM fit unknown on this device"
        val need = estimatedRamNeedBytes(expectedBytes)
        return when {
            need <= (available * 0.6).toLong() -> "Comfortable fit for this device"
            need <= available -> "Fits with limited RAM headroom"
            else -> "Needs more free RAM than this device reports right now"
        }
    }

    fun storageLabel(expectedBytes: Long, freeBytes: Long?): String {
        if (expectedBytes <= 0L) return "Size unknown"
        val size = formatBytes(expectedBytes)
        val free = freeBytes ?: return size
        return "$size download · ${formatBytes(free)} free"
    }

    fun snapshot(
        currentModel: String?,
        installedModelCount: Int,
        downloadState: ModelDownloadState,
        importState: ImportState,
    ): FirstRunSnapshot = when {
        canStartChat(currentModel) -> FirstRunSnapshot(
            stage = FirstRunStage.READY,
            title = "Model ready",
            detail = "Type a message below. Prompts and replies stay on this device.",
        )

        downloadState is ModelDownloadState.Running || importState is ImportState.Running ->
            FirstRunSnapshot(
                stage = FirstRunStage.DOWNLOADING,
                title = "Preparing your model",
                detail = "Download, verification, and import can take a few minutes. Keep the app open.",
            )

        // Installed models outrank a stale failure: a failure left over from an earlier
        // download/import must not hide the models the user can already chat with.
        installedModelCount > 0 -> FirstRunSnapshot(
            stage = FirstRunStage.CHOOSE_MODEL,
            title = if (installedModelCount == 1) "1 model installed" else "$installedModelCount models installed",
            detail = "Choose a model to load it into memory, then chat offline.",
        )

        downloadState is ModelDownloadState.Failure -> FirstRunSnapshot(
            stage = FirstRunStage.ERROR,
            title = "Download failed",
            detail = downloadState.message.ifBlank { "Retry the download or import a GGUF file." },
        )

        importState is ImportState.Failure -> FirstRunSnapshot(
            stage = FirstRunStage.ERROR,
            title = "Import failed",
            detail = importState.message.ifBlank { "Choose another GGUF file or retry." },
        )

        else -> FirstRunSnapshot(
            stage = FirstRunStage.CHOOSE_MODEL,
            title = "Choose a model to begin",
            detail = "Download the recommended starter below, or import a GGUF file you already have.",
        )
    }
}
