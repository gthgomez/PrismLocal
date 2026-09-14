package com.prismai.llmhost.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorStoreTopKTest {

    private fun scored(vararg values: Pair<String, Float>): Iterator<Pair<String, Float>> =
        values.toList().iterator()

    @Test
    fun selectTopK_ordersByDescendingScore() {
        val result = selectTopK(scored("a" to 0.1f, "b" to 0.9f, "c" to 0.5f), topK = 3, minScore = 0f)
        assertEquals(listOf("b", "c", "a"), result.map { it.first })
    }

    @Test
    fun selectTopK_keepsOnlyHighestWhenOverCapacity() {
        val result = selectTopK(scored("a" to 0.1f, "b" to 0.9f, "c" to 0.5f), topK = 2, minScore = 0f)
        assertEquals(listOf("b", "c"), result.map { it.first })
    }

    @Test
    fun selectTopK_filtersBelowMinScore() {
        val result = selectTopK(scored("a" to 0.1f, "b" to 0.9f, "c" to 0.5f), topK = 5, minScore = 0.4f)
        assertEquals(listOf("b", "c"), result.map { it.first })
        assertEquals(listOf(0.9f, 0.5f), result.map { it.second })
    }

    @Test
    fun selectTopK_emptyInputReturnsEmpty() {
        assertEquals(emptyList<Pair<String, Float>>(), selectTopK(scored(), topK = 3, minScore = 0f))
    }

    @Test
    fun selectTopK_nonPositiveTopKReturnsEmpty() {
        assertEquals(emptyList<Pair<String, Float>>(), selectTopK(scored("a" to 1f), topK = 0, minScore = 0f))
    }
}
