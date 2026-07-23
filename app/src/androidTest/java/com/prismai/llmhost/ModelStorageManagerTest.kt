package com.prismai.llmhost
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class ModelStorageManagerTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun validImportStoresHashAndResolves() {
        val manager = ModelStorageManager(context)
        cleanup("valid-model")

        val result = manager.importModelFromStream(
            displayName = "valid-model.gguf",
            reportedSize = validGgufBytes().size.toLong(),
            input = ByteArrayInputStream(validGgufBytes()),
        )

        assertTrue("expected import success, got $result", result is ModelStorageManager.ImportResult.Success)
        val model = (result as ModelStorageManager.ImportResult.Success).model
        assertEquals("valid-model", model.id)
        assertEquals("verified", model.validation.status)
        assertEquals(64L, model.bytes)
        assertTrue(model.file.path.contains("versions"))

        val resolved = manager.resolveActiveModel("valid-model")
        assertTrue("expected resolve success, got $resolved", resolved is ModelStorageManager.ModelResolveResult.Success)
        val resolvedModel = (resolved as ModelStorageManager.ModelResolveResult.Success).model
        assertEquals(model.sha256, resolvedModel.sha256)
    }

    @Test
    fun extensionDoesNotMatterWhenHeaderIsValid() {
        val manager = ModelStorageManager(context)
        cleanup("valid-extension-is-not-required")

        val result = manager.importModelFromStream(
            displayName = "valid-extension-is-not-required.bin",
            reportedSize = validGgufBytes().size.toLong(),
            input = ByteArrayInputStream(validGgufBytes()),
        )

        assertTrue("valid GGUF header should win over extension, got $result", result is ModelStorageManager.ImportResult.Success)
    }

    @Test
    fun invalidGgufHeaderFailsAndCleansStaging() {
        val manager = ModelStorageManager(context)
        cleanup("bad-header")

        val result = manager.importModelFromStream(
            displayName = "bad-header.gguf",
            reportedSize = 64,
            input = ByteArrayInputStream(ByteArray(64) { 'x'.code.toByte() }),
        )

        assertTrue("expected failure, got $result", result is ModelStorageManager.ImportResult.Failure)
        val failure = result as ModelStorageManager.ImportResult.Failure
        assertEquals(ModelStorageManager.ModelStorageError.Code.INVALID_GGUF_HEADER, failure.error.code)
        assertFalse(File(modelsDir(), "bad-header").exists())
        assertStagingEmpty()
    }

    @Test
    fun partialImportFailureCleansStaging() {
        val manager = ModelStorageManager(context)
        cleanup("partial-model")

        val result = manager.importModelFromStream(
            displayName = "partial-model.gguf",
            reportedSize = 128,
            input = ThrowingInputStream(validGgufBytes(), failAfter = 12),
        )

        assertTrue("expected failure, got $result", result is ModelStorageManager.ImportResult.Failure)
        assertFalse(File(modelsDir(), "partial-model").exists())
        assertStagingEmpty()
    }

    @Test
    fun hashMismatchFailsClosedOnResolve() {
        val manager = ModelStorageManager(context)
        cleanup("hash-model")
        val result = manager.importModelFromStream(
            displayName = "hash-model.gguf",
            reportedSize = validGgufBytes().size.toLong(),
            input = ByteArrayInputStream(validGgufBytes()),
        )
        assertTrue(result is ModelStorageManager.ImportResult.Success)
        val file = (result as ModelStorageManager.ImportResult.Success).model.file
        file.writeBytes(validGgufBytes(seed = 9))

        val resolved = manager.resolveActiveModel("hash-model")
        assertTrue("expected hash failure, got $resolved", resolved is ModelStorageManager.ModelResolveResult.Failure)
        val failure = resolved as ModelStorageManager.ModelResolveResult.Failure
        assertEquals(ModelStorageManager.ModelStorageError.Code.HASH_MISMATCH, failure.error.code)
    }

    @Test
    fun corruptManifestDoesNotResolveOrImportOverActiveState() {
        val manager = ModelStorageManager(context)
        cleanup("corrupt-manifest")
        val root = File(modelsDir(), "corrupt-manifest")
        root.mkdirs()
        File(root, "manifest.json").writeText("{not-json")

        val resolved = manager.resolveActiveModel("corrupt-manifest")
        assertTrue(resolved is ModelStorageManager.ModelResolveResult.Failure)
        assertEquals(
            ModelStorageManager.ModelStorageError.Code.CORRUPT_MANIFEST,
            (resolved as ModelStorageManager.ModelResolveResult.Failure).error.code,
        )

        val import = manager.importModelFromStream(
            displayName = "corrupt-manifest.gguf",
            reportedSize = validGgufBytes().size.toLong(),
            input = ByteArrayInputStream(validGgufBytes()),
        )
        assertTrue(import is ModelStorageManager.ImportResult.Failure)
        assertEquals(
            ModelStorageManager.ModelStorageError.Code.CORRUPT_MANIFEST,
            (import as ModelStorageManager.ImportResult.Failure).error.code,
        )
    }

    @Test
    fun duplicateImportCreatesNewActiveVersionWithoutOverwritingOldFile() {
        val manager = ModelStorageManager(context)
        cleanup("duplicate-model")

        val first = manager.importModelFromStream(
            displayName = "duplicate-model.gguf",
            reportedSize = validGgufBytes(seed = 1).size.toLong(),
            input = ByteArrayInputStream(validGgufBytes(seed = 1)),
        )
        Thread.sleep(2)
        val second = manager.importModelFromStream(
            displayName = "duplicate-model.gguf",
            reportedSize = validGgufBytes(seed = 2).size.toLong(),
            input = ByteArrayInputStream(validGgufBytes(seed = 2)),
        )

        assertTrue(first is ModelStorageManager.ImportResult.Success)
        assertTrue(second is ModelStorageManager.ImportResult.Success)
        val firstModel = (first as ModelStorageManager.ImportResult.Success).model
        val secondModel = (second as ModelStorageManager.ImportResult.Success).model
        assertNotEquals(firstModel.versionId, secondModel.versionId)
        assertTrue(firstModel.file.exists())
        assertTrue(secondModel.file.exists())

        val manifest = JSONObject(File(modelsDir(), "duplicate-model/manifest.json").readText())
        assertEquals(secondModel.versionId, manifest.getString("active_version"))
        assertEquals(2, manifest.getJSONObject("versions").length())
    }

    private fun validGgufBytes(seed: Int = 1): ByteArray {
        val bytes = ByteArray(64) { index -> (seed + index).toByte() }
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 3
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        return bytes
    }

    private fun cleanup(modelId: String) {
        File(modelsDir(), modelId).deleteRecursively()
        File(modelsDir(), ".imports").deleteRecursively()
    }

    private fun assertStagingEmpty() {
        val staging = File(modelsDir(), ".imports")
        assertFalse(staging.exists() && staging.walkTopDown().any { it.isFile })
    }

    private fun modelsDir(): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    private class ThrowingInputStream(
        private val bytes: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= failAfter) {
                throw IOException("forced partial read failure")
            }
            if (index >= bytes.size) return -1
            return bytes[index++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= failAfter) {
                throw IOException("forced partial read failure")
            }
            if (index >= bytes.size) return -1
            val count = minOf(length, failAfter - index, bytes.size - index)
            System.arraycopy(bytes, index, buffer, offset, count)
            index += count
            return count
        }
    }
}
