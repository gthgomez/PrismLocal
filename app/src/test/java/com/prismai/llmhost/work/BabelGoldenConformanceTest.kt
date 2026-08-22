package com.prismai.llmhost.work

import com.prismai.llmhost.work.portable.PortableWorkflowJson
import com.prismai.llmhost.work.portable.PortableWorkflowValidator
import com.prismai.llmhost.work.portable.ValidationResult
import org.json.JSONArray
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
    fun canonicalBabelSchemaArtifactIsValidDraft2020Schema() {
        val schemaJson = loadResourceJson("golden/portable-workflow-v1.schema.json")
        assertEquals("PortableExportV1", schemaJson.getString("title"))
        assertEquals("object", schemaJson.getString("type"))
        assertEquals("https://json-schema.org/draft/2020-12/schema", schemaJson.getString("\$schema"))
        assertTrue("Schema must have properties", schemaJson.has("properties"))
        assertTrue("Schema must have definitions", schemaJson.has("\$defs"))
        val defs = schemaJson.getJSONObject("\$defs")
        assertTrue(defs.has("WorkflowRunV1"))
        assertTrue(defs.has("VerifierReceiptV1"))
        assertTrue(defs.has("TaskRefV1"))
        assertTrue(defs.has("StageInputV1"))
        assertTrue(defs.has("StageResultV1"))
        assertTrue(defs.has("TerminalOutcomeV1"))

        // Assert discriminated unions have oneOf with const kinds and additionalProperties: false
        val stageInputSchema = defs.getJSONObject("StageInputV1")
        assertTrue("StageInputV1 must use oneOf for discriminated union", stageInputSchema.has("oneOf"))
        val stageInputVariants = stageInputSchema.getJSONArray("oneOf")
        assertEquals(4, stageInputVariants.length())

        val stageResultSchema = defs.getJSONObject("StageResultV1")
        assertTrue("StageResultV1 must use oneOf for discriminated union", stageResultSchema.has("oneOf"))
        val stageResultVariants = stageResultSchema.getJSONArray("oneOf")
        assertEquals(4, stageResultVariants.length())

        val terminalSchema = defs.getJSONObject("TerminalOutcomeV1")
        assertTrue("TerminalOutcomeV1 must use oneOf for discriminated union", terminalSchema.has("oneOf"))

        // Validate valid fixture against the full JSON schema
        val validFixture = loadResourceJson("golden/portable-workflow-v1-valid.json")
        val errors = validateAgainstSchema(validFixture, schemaJson, schemaJson)
        assertTrue("Valid fixture must conform to Draft 2020-12 JSON Schema: $errors", errors.isEmpty())
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

    private fun validateAgainstSchema(
        instance: Any?,
        schemaNode: JSONObject,
        rootSchema: JSONObject,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (schemaNode.has("\$ref")) {
            val ref = schemaNode.getString("\$ref")
            val target = resolveRef(ref, rootSchema)
            return validateAgainstSchema(instance, target, rootSchema)
        }

        if (schemaNode.has("oneOf")) {
            val variants = schemaNode.getJSONArray("oneOf")
            var matchCount = 0
            val variantErrorsList = mutableListOf<String>()
            for (i in 0 until variants.length()) {
                val variantSchema = variants.getJSONObject(i)
                val variantErrors = validateAgainstSchema(instance, variantSchema, rootSchema)
                if (variantErrors.isEmpty()) {
                    matchCount++
                } else {
                    variantErrorsList.add("Variant $i rejected: ${variantErrors.joinToString(", ")}")
                }
            }
            if (matchCount == 0) {
                errors.add("Instance does not match any variant in oneOf [${variantErrorsList.joinToString("; ")}]")
            } else if (matchCount > 1) {
                errors.add("Instance matches $matchCount variants in oneOf (must match exactly 1)")
            }
            return errors
        }

        if (schemaNode.has("type")) {
            val type = schemaNode.getString("type")
            when (type) {
                "object" -> {
                    if (instance !is JSONObject) {
                        errors.add("Expected object, got $instance")
                        return errors
                    }
                    if (schemaNode.has("required")) {
                        val req = schemaNode.getJSONArray("required")
                        for (i in 0 until req.length()) {
                            val key = req.getString(i)
                            if (!instance.has(key)) {
                                errors.add("Missing required property: $key")
                            }
                        }
                    }
                    val props = schemaNode.optJSONObject("properties") ?: JSONObject()
                    val additionalProperties = schemaNode.optBoolean("additionalProperties", true)
                    for (key in instance.keys()) {
                        if (props.has(key)) {
                            val propSchema = props.getJSONObject(key)
                            errors.addAll(validateAgainstSchema(instance.opt(key), propSchema, rootSchema))
                        } else if (!additionalProperties) {
                            errors.add("Forbidden additional property: $key")
                        }
                    }
                }
                "array" -> {
                    if (instance !is JSONArray) {
                        errors.add("Expected array, got $instance")
                        return errors
                    }
                    if (schemaNode.has("minItems") && instance.length() < schemaNode.getInt("minItems")) {
                        errors.add("Array length ${instance.length()} is less than minItems ${schemaNode.getInt("minItems")}")
                    }
                    if (schemaNode.has("maxItems") && instance.length() > schemaNode.getInt("maxItems")) {
                        errors.add("Array length ${instance.length()} is greater than maxItems ${schemaNode.getInt("maxItems")}")
                    }
                    val itemsSchema = schemaNode.optJSONObject("items")
                    if (itemsSchema != null) {
                        for (i in 0 until instance.length()) {
                            errors.addAll(validateAgainstSchema(instance.get(i), itemsSchema, rootSchema))
                        }
                    }
                }
                "string" -> {
                    if (instance !is String) {
                        errors.add("Expected string, got $instance")
                    } else {
                        if (schemaNode.has("minLength") && instance.length < schemaNode.getInt("minLength")) {
                            errors.add("String length ${instance.length} is less than minLength ${schemaNode.getInt("minLength")}")
                        }
                        if (schemaNode.has("maxLength") && instance.length > schemaNode.getInt("maxLength")) {
                            errors.add("String length ${instance.length} is greater than maxLength ${schemaNode.getInt("maxLength")}")
                        }
                        if (schemaNode.has("const")) {
                            val constVal = schemaNode.getString("const")
                            if (instance != constVal) errors.add("Expected const '$constVal', got '$instance'")
                        }
                        if (schemaNode.has("enum")) {
                            val enumArr = schemaNode.getJSONArray("enum")
                            val allowed = (0 until enumArr.length()).map { enumArr.getString(it) }
                            if (instance !in allowed) errors.add("Value '$instance' not in enum $allowed")
                        }
                        if (schemaNode.has("pattern")) {
                            val pat = Regex(schemaNode.getString("pattern"))
                            if (!pat.matches(instance)) errors.add("Value '$instance' does not match pattern '${schemaNode.getString("pattern")}'")
                        }
                        if (schemaNode.has("format") && schemaNode.getString("format") == "date-time") {
                            try {
                                java.time.Instant.parse(instance)
                            } catch (e: Exception) {
                                errors.add("Value '$instance' is not a valid ISO-8601 date-time: ${e.message}")
                            }
                        }
                    }
                }
                "boolean" -> {
                    if (instance !is Boolean) errors.add("Expected boolean, got $instance")
                }
                "integer" -> {
                    if (instance !is Int && instance !is Long) errors.add("Expected integer, got $instance")
                }
            }
        }

        return errors
    }

    private fun resolveRef(ref: String, rootSchema: JSONObject): JSONObject {
        val prefix = "#/\$defs/"
        if (ref.startsWith(prefix)) {
            val defName = ref.substring(prefix.length)
            return rootSchema.getJSONObject("\$defs").getJSONObject(defName)
        }
        throw IllegalArgumentException("Unsupported \$ref: $ref")
    }
}
