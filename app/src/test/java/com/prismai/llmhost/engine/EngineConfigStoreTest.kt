package com.prismai.llmhost.engine

import android.content.SharedPreferences
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.engine.runtime.AppliedPlan
import com.prismai.llmhost.engine.runtime.BackendPreference
import com.prismai.llmhost.engine.runtime.InferencePlan
import com.prismai.llmhost.engine.runtime.RequestedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for persistence and reload-decision logic. Uses a hand-rolled in-memory
 * [SharedPreferences] double so no Android runtime (Robolectric/device) is required;
 * the only Android reference is the compile-time interface type.
 */
class EngineConfigStoreTest {

    // A fully non-default, canonical (clamp-stable) settings instance.
    private fun sampleSettings(): GenerationSettings = GenerationSettings(
        maxTokens = 512,
        threadCount = 6,
        contextLength = 4096,
        batchSize = 1024,
        temperature = 1.10f,
        topK = 80,
        topP = 0.80f,
        repeatPenalty = 1.30f,
        gpuLayers = 42,
        useVulkan = false,
        agentEnabled = true,
        kvCacheTypeK = "q4_0",
        kvCacheTypeV = "f16",
        enableFlashAttn = false,
    )

    // ---------------------------------------------------------------------
    // EngineConfigStore round-trip
    // ---------------------------------------------------------------------

    @Test
    fun saveThenLoadRoundTripsEveryPersistedFieldIncludingVulkan() {
        val store = EngineConfigStore(FakeSharedPreferences())
        val original = sampleSettings()

        store.save(original)
        val loaded = store.load()

        assertEquals(original.maxTokens, loaded.maxTokens)
        assertEquals(original.threadCount, loaded.threadCount)
        assertEquals(original.contextLength, loaded.contextLength)
        assertEquals(original.batchSize, loaded.batchSize)
        assertEquals(original.temperature, loaded.temperature, 0.0f)
        assertEquals(original.topK, loaded.topK)
        assertEquals(original.topP, loaded.topP, 0.0f)
        assertEquals(original.repeatPenalty, loaded.repeatPenalty, 0.0f)
        assertEquals(original.gpuLayers, loaded.gpuLayers)
        assertEquals(original.useVulkan, loaded.useVulkan)
        assertEquals(original.agentEnabled, loaded.agentEnabled)
        assertEquals(original.kvCacheTypeK, loaded.kvCacheTypeK)
        assertEquals(original.kvCacheTypeV, loaded.kvCacheTypeV)
        assertEquals(original.enableFlashAttn, loaded.enableFlashAttn)
    }

    @Test
    fun loadFallsBackToDefaultsWhenNothingPersisted() {
        val loaded = EngineConfigStore(FakeSharedPreferences()).load()
        assertEquals(GenerationSettings.DEFAULT_MAX_TOKENS, loaded.maxTokens)
        assertEquals(GenerationSettings.DEFAULT_CONTEXT_LENGTH, loaded.contextLength)
        assertEquals(GenerationSettings.DEFAULT_BATCH_SIZE, loaded.batchSize)
        assertEquals(GenerationSettings.DEFAULT_GPU_LAYERS, loaded.gpuLayers)
        assertTrue(loaded.useVulkan)
        assertEquals("q8_0", loaded.kvCacheTypeK)
        assertEquals("q8_0", loaded.kvCacheTypeV)
        assertTrue(loaded.enableFlashAttn)
    }

    @Test
    fun savePersistsVulkanUnderDedicatedKey() {
        val prefs = FakeSharedPreferences()
        EngineConfigStore(prefs).save(sampleSettings())
        assertEquals(false, prefs.getBoolean(EngineConfigStore.KEY_USE_VULKAN, true))
    }

    // ---------------------------------------------------------------------
    // requiresReload
    // ---------------------------------------------------------------------

    @Test
    fun requiresReloadFalseForIdenticalSettings() {
        val base = sampleSettings()
        assertFalse(EngineConfigStore.requiresReload(base, base.copy()))
    }

    @Test
    fun requiresReloadTrueForEachLoadAffectingField() {
        val base = sampleSettings()
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(useVulkan = true)))
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(contextLength = 8192)))
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(batchSize = 512)))
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(gpuLayers = 7)))
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(kvCacheTypeK = "q8_0")))
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(kvCacheTypeV = "q4_0")))
        assertTrue(EngineConfigStore.requiresReload(base, base.copy(enableFlashAttn = true)))
    }

    @Test
    fun requiresReloadFalseForNonLoadFields() {
        val base = sampleSettings()
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(maxTokens = 256)))
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(threadCount = 2)))
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(temperature = 0.5f)))
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(topK = 10)))
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(topP = 0.5f)))
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(repeatPenalty = 1.0f)))
        assertFalse(EngineConfigStore.requiresReload(base, base.copy(agentEnabled = false)))
    }

    // ---------------------------------------------------------------------
    // InferencePlan.loadKey
    // ---------------------------------------------------------------------

    private fun plan(
        settings: GenerationSettings = sampleSettings(),
        modelId: String = "model-a",
        sha256: String = "sha-a",
        adapters: String = InferencePlan.NO_ADAPTERS,
    ): RequestedPlan = InferencePlan.fromSettings(settings, modelId, sha256, adapters)

    @Test
    fun requestedPlanDerivesBackendFromVulkan() {
        assertEquals(BackendPreference.VULKAN, plan(sampleSettings().copy(useVulkan = true)).backend)
        assertEquals(BackendPreference.CPU, plan(sampleSettings().copy(useVulkan = false)).backend)
    }

    @Test
    fun loadKeyDiffersForEveryLoadKeyField() {
        val base = plan()
        val baseKey = base.loadKey

        assertNotEquals(baseKey, plan(modelId = "model-b").loadKey)
        assertNotEquals(baseKey, plan(sha256 = "sha-b").loadKey)
        assertNotEquals(baseKey, plan(adapters = "lora:adapter.bin@1.0").loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(useVulkan = true)).loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(contextLength = 8192)).loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(batchSize = 256)).loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(gpuLayers = 0)).loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(kvCacheTypeK = "f16")).loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(kvCacheTypeV = "q4_0")).loadKey)
        assertNotEquals(baseKey, plan(sampleSettings().copy(enableFlashAttn = true)).loadKey)
    }

    @Test
    fun loadKeyStableAcrossNonLoadFields() {
        val baseKey = plan().loadKey
        val base = sampleSettings()

        assertEquals(baseKey, plan(base.copy(maxTokens = 128)).loadKey)
        assertEquals(baseKey, plan(base.copy(threadCount = 1)).loadKey)
        assertEquals(baseKey, plan(base.copy(temperature = 0.2f)).loadKey)
        assertEquals(baseKey, plan(base.copy(topK = 1)).loadKey)
        assertEquals(baseKey, plan(base.copy(topP = 0.1f)).loadKey)
        assertEquals(baseKey, plan(base.copy(repeatPenalty = 1.5f)).loadKey)
        assertEquals(baseKey, plan(base.copy(agentEnabled = false)).loadKey)
    }

    @Test
    fun settingsLoadKeyMatchesRequestedPlanWithoutIdentity() {
        val settings = sampleSettings()
        val withIdentity = InferencePlan.loadKey(plan(settings, modelId = "x", sha256 = "y"))
        // Identity is excluded from the settings-only key used by requiresReload.
        assertNotEquals(withIdentity, InferencePlan.settingsLoadKey(settings))
        assertEquals(InferencePlan.settingsLoadKey(settings), InferencePlan.settingsLoadKey(settings.copy()))
    }

    @Test
    fun appliedPlanFromRequestedNeverEchoesRequestedValues() {
        val requested = plan(sampleSettings().copy(contextLength = 8192, batchSize = 256))
        val applied = AppliedPlan.fromRequested(requested)

        assertEquals(InferencePlan.SCHEMA_VERSION, applied.version)
        assertNull(applied.modelId)
        assertNull(applied.useVulkan)
        assertNull(applied.backendName)
        assertNull(applied.gpuLayersOffloaded)
        assertNull(applied.contextLength)
        assertNull(applied.batchSize)
        assertNull(applied.gpuLayers)
        assertNull(applied.kvCacheTypeK)
        assertNull(applied.kvCacheTypeV)
        assertNull(applied.enableFlashAttn)
        assertNull(applied.maxTokens)
    }

    @Test
    fun appliedPlanNativeReadbackFillsOnlyObservableFields() {
        val applied = AppliedPlan.fromNativeReadback(
            version = InferencePlan.SCHEMA_VERSION,
            modelId = "model-a",
            modelSha256 = "sha-a",
            backendName = "Vulkan",
            useVulkan = true,
            gpuLayersOffloaded = 99,
            kleidiAiEnabled = false,
        )

        assertEquals("Vulkan", applied.backendName)
        assertEquals(true, applied.useVulkan)
        assertEquals(99, applied.gpuLayersOffloaded)
        assertEquals(false, applied.kleidiAiEnabled)
        // Not observable through the bridge: must stay unknown, not the request.
        assertNull(applied.contextLength)
        assertNull(applied.batchSize)
        assertNull(applied.kvCacheTypeK)
        assertNull(applied.kvCacheTypeV)
        assertNull(applied.enableFlashAttn)
    }
}

/**
 * Minimal in-memory [SharedPreferences] test double. No Android method is invoked; the
 * interface is only used as a compile-time contract.
 */
private class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
