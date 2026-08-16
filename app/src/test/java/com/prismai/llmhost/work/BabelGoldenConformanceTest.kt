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
import java.security.MessageDigest

class BabelGoldenConformanceTest {

    private fun loadResourceText(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Missing golden test resource: $path")
        return InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
    }

    private fun loadResourceJson(path: String): JSONObject {
        return JSONObject(loadResourceText(path))
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
    fun canonicalBabelInvalidStageLinkageGoldenIsRejected() {
        val json = loadResourceJson("golden/portable-workflow-v1-invalid-stage-linkage.json")
        val export = PortableWorkflowJson.parseExport(json)
        val validation = PortableWorkflowValidator.validateRun(export.run)

        assertFalse("Canonical Babel invalid stage linkage golden MUST be rejected", validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("worker references missing stage") })
    }

    @Test
    fun canonicalBabelInvalidUnknownPropertyGoldenIsRejected() {
        val json = loadResourceJson("golden/portable-workflow-v1-invalid-unknown-property.json")
        var caughtException = false
        try {
            PortableWorkflowJson.parseExport(json)
        } catch (e: IllegalArgumentException) {
            caughtException = true
            assertTrue("Exception must mention unknown property", e.message?.contains("unauthorized_injected_field") == true)
        }
        assertTrue("Parsing unknown injected property MUST fail with IllegalArgumentException", caughtException)
    }

    @Test
    fun canonicalBabelSchemaArtifactIsValid() {
        val schemaJson = loadResourceJson("golden/portable-workflow-v1.schema.json")
        assertEquals("PortableExportV1", schemaJson.getString("title"))
        assertEquals("object", schemaJson.getString("type"))
        assertTrue("Schema must have properties", schemaJson.has("properties"))
        assertTrue("Schema must have definitions", schemaJson.has("\$defs"))
        val defs = schemaJson.getJSONObject("\$defs")
        assertTrue(defs.has("WorkflowRunV1"))
        assertTrue(defs.has("VerifierReceiptV1"))
        assertTrue(defs.has("TaskRefV1"))
    }

    @Test
    fun canonicalBabelManifestRecomputedHashesMatchExactly() {
        val manifestJson = loadResourceJson("golden/portable-workflow-v1.manifest.json")
        val schemaVersion = manifestJson.getString("schema_version")
        val schemaArtifactSha256 = manifestJson.getString("schema_artifact_sha256")
        val fixtureSetHash = manifestJson.getString("fixture_set_sha256")

        assertEquals("portable-workflow-v1", schemaVersion)

        val schemaContent = loadResourceText("golden/portable-workflow-v1.schema.json")
        val computedSchemaSha256 = sha256(schemaContent)
        assertEquals(
            "Schema artifact SHA-256 MUST match recomputed SHA-256 of portable-workflow-v1.schema.json",
            schemaArtifactSha256,
            computedSchemaSha256,
        )

        val validContent = loadResourceText("golden/portable-workflow-v1-valid.json")
        val revMismatchContent = loadResourceText("golden/portable-workflow-v1-invalid-revision-mismatch.json")
        val invalidVerContent = loadResourceText("golden/portable-workflow-v1-invalid-version.json")
        val invalidLinkageContent = loadResourceText("golden/portable-workflow-v1-invalid-stage-linkage.json")
        val invalidUnknownContent = loadResourceText("golden/portable-workflow-v1-invalid-unknown-property.json")

        val computedFixtureSetSha256 = sha256(
            validContent + revMismatchContent + invalidVerContent + invalidLinkageContent + invalidUnknownContent,
        )

        assertEquals(
            "Fixture set SHA-256 MUST match recomputed composite hash of all golden fixtures",
            fixtureSetHash,
            computedFixtureSetSha256,
        )
    }
}
