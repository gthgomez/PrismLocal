package com.prismai.llmhost.storage

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The link-external path wrote uri.toString() as the contents of a file named
 * model.gguf and recorded sha256 = "linked_saf_uri" / status = "saf_linked".
 * Nothing downstream could detect the file was not a GGUF, so selecting the
 * model attempted to load 40 bytes of text as a model.
 *
 * This test is a tripwire on the sentinels rather than on behavior, because the
 * honest outcome is simply that the feature no longer exists.
 */
class NoFakeLinkedModelTest {

    @Test
    fun linkExternalIsRemovedFromTheStorageManager() {
        val methods = ModelStorageManager::class.java.declaredMethods.map { it.name }
        assertFalse(
            "linkExternalModelUri must not exist: it produced a fake GGUF",
            methods.any { it.contains("linkExternal", ignoreCase = true) },
        )
    }

    @Test
    fun noSentinelLinkedStatusRemains() {
        val fields = ModelStorageManager::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("LINKED", ignoreCase = true) })
    }
}
