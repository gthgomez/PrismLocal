package com.prismai.llmhost.model

import com.prismai.llmhost.HuggingFaceModelCatalog
import com.prismai.llmhost.ModelDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadFailureIdentityTest {

    @Test
    fun failure_carriesTheEntryIdSoRetryTargetsTheFailedModel() {
        val state: ModelDownloadState =
            ModelDownloadState.Failure(
                entryId = "model_b",
                entryName = "B",
                message = "Download failed",
            )
        val failure = state as ModelDownloadState.Failure
        assertEquals("model_b", failure.entryId)
        assertEquals("B", failure.entryName)
    }

    @Test
    fun failureResolvesBackToItsCatalogEntry() {
        val entry = HuggingFaceModelCatalog.entries.first()
        val failure = ModelDownloadState.Failure(
            entryId = entry.id,
            entryName = entry.name,
            message = "x",
        )
        assertNotNull(HuggingFaceModelCatalog.find(failure.entryId!!))
    }
}
