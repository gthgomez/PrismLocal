package com.prismai.llmhost.storage

import com.prismai.llmhost.DownloadIntegrity
import com.prismai.llmhost.FakeTestContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PL-F16: Integrity verification provenance must be persisted in manifest and round-trip
 * across process/manager restarts without being lost or mutated on cache hits.
 * Existing locally imported or legacy artifacts without explicit integrity field must
 * deserialize as UNKNOWN_LEGACY rather than falsely upgraded to VERIFIED_PINNED.
 */
class ModelProvenancePersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun provenanceRoundTripsThroughManifestAndAcrossManagerInstances() {
        val modelsDir = tempFolder.newFolder("models")
        val manager = ModelStorageManager(FakeTestContext(), modelsDir)

        // Create 4 models: PINNED, PROVIDER_METADATA, UNVERIFIED, and LEGACY (no integrity field)
        createModelWithManifest(modelsDir, "pinned-model", DownloadIntegrity.VERIFIED_PINNED.name)
        createModelWithManifest(modelsDir, "provider-meta-model", DownloadIntegrity.VERIFIED_PROVIDER_METADATA.name)
        createModelWithManifest(modelsDir, "unverified-model", DownloadIntegrity.UNVERIFIED.name)
        createModelWithManifest(modelsDir, "legacy-model", integrity = null) // legacy manifest

        // Verify first manager instance reads exact provenance
        val pinned = checkNotNull(manager.activeModelInfo("pinned-model"))
        assertEquals(DownloadIntegrity.VERIFIED_PINNED, pinned.integrity)

        val providerMeta = checkNotNull(manager.activeModelInfo("provider-meta-model"))
        assertEquals(DownloadIntegrity.VERIFIED_PROVIDER_METADATA, providerMeta.integrity)

        val unverified = checkNotNull(manager.activeModelInfo("unverified-model"))
        assertEquals(DownloadIntegrity.UNVERIFIED, unverified.integrity)

        val legacy = checkNotNull(manager.activeModelInfo("legacy-model"))
        assertEquals("legacy manifest without integrity field must be UNKNOWN_LEGACY", DownloadIntegrity.UNKNOWN_LEGACY, legacy.integrity)

        // Simulate process death / new manager instance:
        val freshManager = ModelStorageManager(FakeTestContext(), modelsDir)

        assertEquals(DownloadIntegrity.VERIFIED_PINNED, freshManager.activeModelInfo("pinned-model")?.integrity)
        assertEquals(DownloadIntegrity.VERIFIED_PROVIDER_METADATA, freshManager.activeModelInfo("provider-meta-model")?.integrity)
        assertEquals(DownloadIntegrity.UNVERIFIED, freshManager.activeModelInfo("unverified-model")?.integrity)
        assertEquals(DownloadIntegrity.UNKNOWN_LEGACY, freshManager.activeModelInfo("legacy-model")?.integrity)

        // Cache hit must not alter provenance
        val cachedPinned = freshManager.resolveActiveModelForActivation("pinned-model")
        assertNotNull(cachedPinned)
        assertEquals(DownloadIntegrity.VERIFIED_PINNED, (cachedPinned as ModelStorageManager.ModelResolveResult.Success).model.integrity)
    }

    private fun createModelWithManifest(modelsDir: File, modelId: String, integrity: String?) {
        val modelFile = File(modelsDir, "$modelId/versions/v1/model.gguf")
        modelFile.parentFile!!.mkdirs()
        modelFile.writeText("gguf-bytes-for-$modelId")

        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(modelFile.readBytes())
            .joinToString("") { "%02x".format(it) }

        val versionObj = JSONObject()
            .put("file", "versions/v1/model.gguf")
            .put("original_file_name", "$modelId.gguf")
            .put("sha256", sha)
            .put("bytes", modelFile.length())
            .put("imported_at", "2026-10-09T00:00:00Z")
            .put(
                "validation",
                JSONObject()
                    .put("format", "GGUF")
                    .put("gguf_version", 3)
                    .put("status", "imported")
                    .put("validated_at", "2026-10-09T00:00:00Z"),
            )
        if (integrity != null) {
            versionObj.put("integrity", integrity)
        }

        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("model_id", modelId)
            .put("active_version", "v1")
            .put("versions", JSONObject().put("v1", versionObj))

        File(modelsDir, "$modelId/manifest.json").writeText(manifest.toString(2))
    }
}
