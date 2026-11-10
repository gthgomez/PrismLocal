package com.prismai.llmhost.ui.onboarding

import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.HuggingFaceModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunGuidanceTest {

    private val idleDownload = ModelDownloadState.Idle
    private val idleImport = ImportState.Idle

    @Test
    fun noModelAndNothingInstalled_asksTheUserToChoose() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 0,
            downloadState = idleDownload,
            importState = idleImport,
        )
        assertEquals(FirstRunStage.CHOOSE_MODEL, snapshot.stage)
        assertTrue(snapshot.detail.contains("GGUF"))
    }

    @Test
    fun installedModelWithoutActiveModel_asksTheUserToLoadIt() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 2,
            downloadState = idleDownload,
            importState = idleImport,
        )
        assertEquals(FirstRunStage.CHOOSE_MODEL, snapshot.stage)
        assertTrue(snapshot.title.contains("2 models"))
    }

    @Test
    fun activeModel_isReady() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = "qwen25_05b_q4km",
            installedModelCount = 1,
            downloadState = idleDownload,
            importState = idleImport,
        )
        assertEquals(FirstRunStage.READY, snapshot.stage)
        assertTrue(snapshot.detail.contains("on this device"))
    }

    @Test
    fun runningDownload_isDownloadingEvenBeforeAModelExists() {
        val entry = HuggingFaceModelCatalog.entries.first()
        val running = ModelDownloadState.Running(
            entry = entry,
            stage = ModelDownloadState.Running.Stage.DOWNLOADING,
            bytesDone = 1024L,
            totalBytes = entry.expectedBytes,
        )
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 0,
            downloadState = running,
            importState = idleImport,
        )
        assertEquals(FirstRunStage.DOWNLOADING, snapshot.stage)
    }

    @Test
    fun failedDownload_isAnErrorStateWithTheProviderMessage() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 0,
            downloadState = ModelDownloadState.Failure(
                entryId = "qwen25_05b_q4km",
                entryName = "Qwen2.5 0.5B Instruct",
                message = "Network unavailable",
            ),
            importState = idleImport,
        )
        assertEquals(FirstRunStage.ERROR, snapshot.stage)
        assertEquals("Network unavailable", snapshot.detail)
    }

    @Test
    fun failedImport_isAnErrorState() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 0,
            downloadState = idleDownload,
            importState = ImportState.Failure(message = "Corrupt GGUF header", code = "IMPORT_FAILED"),
        )
        assertEquals(FirstRunStage.ERROR, snapshot.stage)
        assertEquals("Corrupt GGUF header", snapshot.detail)
    }

    @Test
    fun staleDownloadFailureWithInstalledModels_stillAsksTheUserToChoose() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 2,
            downloadState = ModelDownloadState.Failure(
                entryId = "qwen25_15b_q4km",
                entryName = "Qwen2.5 1.5B Instruct",
                message = "Network unavailable",
            ),
            importState = idleImport,
        )
        assertEquals(FirstRunStage.CHOOSE_MODEL, snapshot.stage)
        assertTrue(snapshot.title.contains("2 models"))
    }

    @Test
    fun staleImportFailureWithInstalledModels_stillAsksTheUserToChoose() {
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 1,
            downloadState = idleDownload,
            importState = ImportState.Failure(message = "Corrupt GGUF header", code = "IMPORT_FAILED"),
        )
        assertEquals(FirstRunStage.CHOOSE_MODEL, snapshot.stage)
        assertTrue(snapshot.title.contains("1 model"))
    }

    @Test
    fun runningDownloadOutranksInstalledModels() {
        val entry = HuggingFaceModelCatalog.entries.first()
        val running = ModelDownloadState.Running(
            entry = entry,
            stage = ModelDownloadState.Running.Stage.DOWNLOADING,
            bytesDone = 1024L,
            totalBytes = entry.expectedBytes,
        )
        val snapshot = FirstRunGuidance.snapshot(
            currentModel = null,
            installedModelCount = 3,
            downloadState = running,
            importState = idleImport,
        )
        assertEquals(FirstRunStage.DOWNLOADING, snapshot.stage)
    }

    @Test
    fun storageNeedsRoughlyTwiceTheModelPlusHeadroom() {
        val model = 500L * 1024L * 1024L
        assertFalse(FirstRunGuidance.hasStorageFor(model, freeBytes = model * 2L))
        assertTrue(FirstRunGuidance.hasStorageFor(model, freeBytes = model * 2L + 600L * 1024L * 1024L))
        assertTrue("unknown storage must not block", FirstRunGuidance.hasStorageFor(model, freeBytes = null))
    }

    @Test
    fun ramHintIsConservativeButNeverBlocks() {
        val model = 500L * 1024L * 1024L
        assertEquals(
            "Comfortable fit for this device",
            FirstRunGuidance.ramFitLabel(model, availableRamBytes = 6L * 1024L * 1024L * 1024L),
        )
        assertEquals(
            "Fits with limited RAM headroom",
            FirstRunGuidance.ramFitLabel(model, availableRamBytes = 1L * 1024L * 1024L * 1024L),
        )
        assertEquals(
            "Needs more free RAM than this device reports right now",
            FirstRunGuidance.ramFitLabel(model, availableRamBytes = 256L * 1024L * 1024L),
        )
    }

    @Test
    fun chatReadinessRequiresANonBlankModel() {
        assertFalse(FirstRunGuidance.canStartChat(null))
        assertFalse(FirstRunGuidance.canStartChat("  "))
        assertTrue(FirstRunGuidance.canStartChat("qwen25_05b_q4km"))
    }
}
