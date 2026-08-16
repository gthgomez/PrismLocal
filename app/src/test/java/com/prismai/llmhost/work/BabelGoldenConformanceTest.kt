package com.prismai.llmhost.work

import com.prismai.llmhost.work.portable.PortableWorkflowJson
import com.prismai.llmhost.work.portable.PortableWorkflowValidator
import com.prismai.llmhost.work.portable.ValidationResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class BabelGoldenConformanceTest {

    private fun loadResourceJson(path: String): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Missing golden test resource: $path")
        val content = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        return JSONObject(content)
    }

    @Test
    fun canonicalBabelValidGoldenPassesValidation() {
        val json = loadResourceJson("golden/portable-workflow-v1-valid.json")
        val export = PortableWorkflowJson.parseExport(json)
        val validation = PortableWorkflowValidator.validateRun(export.run)

        assertTrue(
            "Canonical Babel valid golden run MUST pass Prism validator: ${(validation as? ValidationResult.Error)?.errors}",
            validation.isOk,
        )
    }

    @Test
    fun canonicalBabelRevisionMismatchGoldenIsRejected() {
        val json = loadResourceJson("golden/portable-workflow-v1-invalid-revision-mismatch.json")
        val export = PortableWorkflowJson.parseExport(json)
        val validation = PortableWorkflowValidator.validateRun(export.run)

        assertFalse("Canonical Babel revision mismatch golden MUST be rejected", validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("receipt revision mismatch") })
    }

    @Test
    fun canonicalBabelInvalidVersionGoldenIsRejected() {
        val json = loadResourceJson("golden/portable-workflow-v1-invalid-version.json")
        val export = PortableWorkflowJson.parseExport(json)
        val validation = PortableWorkflowValidator.validateRun(export.run)

        assertFalse("Canonical Babel invalid version golden MUST be rejected", validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("unsupported version") })
    }

    @Test
    fun canonicalBabelSchemaArtifactIsValid() {
        val schemaJson = loadResourceJson("golden/portable-workflow-v1.schema.json")
        assertEquals("PortableWorkflowV1", schemaJson.getString("title"))
        assertEquals("portable-workflow-v1", schemaJson.getString("version"))
        assertEquals("object", schemaJson.getString("type"))
        assertTrue(schemaJson.has("properties"))
    }

    @Test
    fun canonicalBabelManifestMatchesExpectedVersionAndHashes() {
        val manifestJson = loadResourceJson("golden/portable-workflow-v1.manifest.json")
        val schemaVersion = manifestJson.getString("schema_version")
        val sourceRevision = manifestJson.getString("babel_source_revision")
        val workflowSourceSha256 = manifestJson.getString("workflow_source_sha256")
        val schemaArtifactSha256 = manifestJson.getString("schema_artifact_sha256")
        val fixtureSetHash = manifestJson.getString("fixture_set_sha256")

        assertEquals("portable-workflow-v1", schemaVersion)
        assertTrue("Source revision must be non-empty", sourceRevision.isNotBlank())
        assertTrue("Workflow source SHA-256 must be valid hash", workflowSourceSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue("Schema artifact SHA-256 must be valid hash", schemaArtifactSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue("Fixture set SHA-256 must be valid hash", fixtureSetHash.matches(Regex("^[0-9a-f]{64}$")))
    }
}
