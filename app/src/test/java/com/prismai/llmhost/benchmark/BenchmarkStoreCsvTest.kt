package com.prismai.llmhost.benchmark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV/JSON export contract checks without constructing a full [BenchmarkStore]
 * (which needs Android file/profiler wiring).
 */
class BenchmarkStoreCsvTest {

    @Test
    fun terminalDetailColumnIsPartOfExportContract() {
        val header = BenchmarkStore.csvHeader()
        assertTrue(header.endsWith("terminal_reason,terminal_detail"))
        assertTrue(BenchmarkStore.CSV_COLUMNS.contains("terminal_detail"))
        assertEquals(BenchmarkStore.CSV_COLUMNS.joinToString(","), header)
    }

    @Test
    fun jsonSchemaIsV4AfterTerminalDetail() {
        assertEquals("prism-local-benchmarks-v4", BenchmarkStore.JSON_SCHEMA)
    }

    @Test
    fun legacyJsonWithoutTerminalDetailLoadsAsNull() {
        val item = JSONObject()
            .put("id", "bench_legacy")
            .put("created_at_ms", 1L)
            .put("model_id", "m")
            .put("source", "preset")
            .put("prompt_chars", 10)
            .put("output_chars", 20)
            .put("prompt_eval_ms", 5L)
            .put("decode_ms", 50L)
            .put("total_ms", 55L)
            .put("generated_tokens", 10)
            .put("tokens_per_second", 20.0)
            .put("terminal_reason", "EOF")
        // intentionally no terminal_detail
        val run = BenchmarkStore.parseRun(item)
        assertEquals("EOF", run.terminalReason)
        assertNull(run.terminalDetail)
    }

    @Test
    fun jsonWithTerminalDetailIsPreserved() {
        val item = JSONObject()
            .put("id", "bench_v4")
            .put("created_at_ms", 2L)
            .put("terminal_reason", "QUALITY_ABORT")
            .put("terminal_detail", "REPETITION_LOOP: phrase_repeats=6")
        val run = BenchmarkStore.parseRun(item)
        assertEquals("QUALITY_ABORT", run.terminalReason)
        assertEquals("REPETITION_LOOP: phrase_repeats=6", run.terminalDetail)
    }
}
