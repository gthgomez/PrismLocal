package com.prismai.llmhost.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSpaceAccountingTest {

    /**
     * The post-copy gate must not require room for the model a second time.
     * Model is 4 GiB, reserve is 512 MiB, and only 600 MiB is free *after*
     * the 4 GiB copy landed: the model fits, so the import must be allowed to
     * finish. The old bytes+reserve check rejected this.
     */
    @Test
    fun postCopy_allowsModelThatFitsWhenOnlyReserveRemains() {
        val modelBytes = 4L * 1024 * 1024 * 1024
        val freeAfterCopy = 600L * 1024 * 1024
        assertTrue(
            "post-copy gate must only require the reserve",
            ModelStorageManager.hasReserveAfterCopy(freeAfterCopy),
        )
        assertFalse(
            "the pre-copy check must still require the full model size",
            ModelStorageManager.hasUsableSpaceFor(freeAfterCopy, modelBytes),
        )
    }

    @Test
    fun postCopy_rejectsWhenReserveIsConsumed() {
        val free = 100L * 1024 * 1024
        assertFalse(ModelStorageManager.hasReserveAfterCopy(free))
    }

    @Test
    fun preCopy_requiresModelSizePlusReserve() {
        val model = 4L * 1024 * 1024 * 1024
        val reserve = ModelStorageManager.MIN_FREE_SPACE_AFTER_IMPORT
        assertFalse(
            "size alone is not enough; reserve must also be free",
            ModelStorageManager.hasUsableSpaceFor(model, model),
        )
        assertTrue(
            ModelStorageManager.hasUsableSpaceFor(model + reserve + 1L, model),
        )
    }
}
