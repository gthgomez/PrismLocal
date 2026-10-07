package com.prismai.llmhost.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledModelsStateTest {

    /**
     * The cached list was a remember snapshot refreshed only on
     * ImportState.Success, so a deleted model stayed visible until something
     * else forced a reload. One observable state must reflect every mutation.
     */
    @Test
    fun deletionRemovesTheModelFromTheObservableList() = runBlocking {
        val store = InstalledModelsStore()
        store.set(listOf("a", "b"))
        store.set(listOf("a"))
        assertEquals(listOf("a"), store.value.value)
    }

    @Test
    fun identicalListDoesNotEmit() = runBlocking {
        val store = InstalledModelsStore()
        store.set(listOf("a"))
        val before = store.emissionCount
        store.set(listOf("a"))
        assertEquals("a redundant refresh must not re-render the picker", before, store.emissionCount)
    }

    @Test
    fun reorderingDoesEmit() = runBlocking {
        val store = InstalledModelsStore()
        store.set(listOf("a", "b"))
        val before = store.emissionCount
        store.set(listOf("b", "a"))
        assertEquals(before + 1, store.emissionCount)
    }

    @Test
    fun emptyListIsAValidValue() = runBlocking {
        val store = InstalledModelsStore()
        store.set(listOf("a"))
        store.set(emptyList())
        assertEquals(emptyList<String>(), store.value.value)
    }
}
