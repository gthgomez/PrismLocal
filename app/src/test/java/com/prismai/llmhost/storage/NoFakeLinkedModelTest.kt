package com.prismai.llmhost.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The link-external path wrote uri.toString() as the contents of a file named
 * model.gguf and recorded sha256 = "linked_saf_uri" / status = "saf_linked".
 * Nothing downstream could detect the file was not a GGUF, so selecting the
 * model attempted to load 40 bytes of text as a model.
 *
 * These tests are tripwires on the removed feature rather than behavioral tests,
 * because the honest outcome is simply that the feature no longer exists.
 */
class NoFakeLinkedModelTest {

    @Test
    fun linkExternalIsRemovedFromTheStorageManager() {
        // Name tripwire: catches the removed entry points by name, not semantics.
        val methods = ModelStorageManager::class.java.declaredMethods.map { it.name }
        assertFalse(
            "linkExternalModelUri must not exist: it produced a fake GGUF",
            methods.any { it.contains("linkExternal", ignoreCase = true) },
        )
    }

    /**
     * The sentinels were inline string literals inside the deleted method, never
     * fields, so a declaredFields scan could not catch them. Scan the compiled
     * class instead: a reintroduced literal lands in the constant pool and is
     * present byte-for-byte in the .class file.
     */
    @Test
    fun theFakeLinkedSentinelsAreGoneFromTheCompiledClass() {
        val text = String(modelStorageManagerClassBytes(), Charsets.ISO_8859_1)
        assertFalse(
            "the linked_saf_uri sentinel must not appear in ModelStorageManager.class",
            text.contains("linked_saf_uri"),
        )
        assertFalse(
            "the saf_linked sentinel must not appear in ModelStorageManager.class",
            text.contains("saf_linked"),
        )
    }

    private fun modelStorageManagerClassBytes(): ByteArray {
        val clazz = ModelStorageManager::class.java
        clazz.getResourceAsStream("ModelStorageManager.class")?.use { return it.readBytes() }
        // Fallback: read the class file from the code source location.
        val location = requireNotNull(clazz.protectionDomain?.codeSource?.location) {
            "Could not locate the ModelStorageManager code source"
        }
        val file = File(File(location.toURI()), CLASS_FILE_PATH)
        assertTrue("ModelStorageManager.class not found at $file", file.exists())
        return file.readBytes()
    }

    private companion object {
        const val CLASS_FILE_PATH = "com/prismai/llmhost/storage/ModelStorageManager.class"
    }
}
