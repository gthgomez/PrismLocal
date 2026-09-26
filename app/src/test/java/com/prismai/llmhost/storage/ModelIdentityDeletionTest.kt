package com.prismai.llmhost.storage

import com.prismai.llmhost.FakeTestContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelIdentityDeletionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deleteRemovesOnlyTheConfirmedVersionHashAndPath() {
        val modelsDir = tempFolder.newFolder("models")
        val manager = ModelStorageManager(FakeTestContext(), modelsDir)
        val modelId = "reviewed-model"
        val modelFile = File(modelsDir, "$modelId/versions/v1/model.gguf")
        modelFile.parentFile!!.mkdirs()
        modelFile.writeText("gguf")
        File(modelsDir, "$modelId/manifest.json").writeText(manifest(modelId).toString())

        val installed = checkNotNull(manager.activeModelInfo(modelId))
        val identity = checkNotNull(ModelIdentity.from(installed))
        assertEquals(modelFile.canonicalPath, identity.path)

        assertFalse(manager.deleteModel(modelId, identity.copy(versionId = "v2")))
        assertFalse(manager.deleteModel(modelId, identity.copy(sha256 = "f".repeat(64))))
        assertFalse(manager.deleteModel(modelId, identity.copy(path = identity.path + "-other")))
        assertTrue(modelFile.exists())
        assertTrue(manager.deleteModel(modelId, identity))
        assertFalse(File(modelsDir, modelId).exists())
    }

    private fun manifest(modelId: String): JSONObject = JSONObject()
        .put("model_id", modelId)
        .put("active_version", "v1")
        .put(
            "versions",
            JSONObject().put(
                "v1",
                JSONObject()
                    .put("file", "versions/v1/model.gguf")
                    .put("original_file_name", "model.gguf")
                    .put("sha256", "abc123")
                    .put("bytes", 4)
                    .put("imported_at", "2026-09-26T00:00:00Z")
                    .put(
                        "validation",
                        JSONObject()
                            .put("format", "GGUF")
                            .put("gguf_version", 3)
                            .put("status", "imported")
                            .put("validated_at", "2026-09-26T00:00:00Z"),
                    ),
            ),
        )
}
