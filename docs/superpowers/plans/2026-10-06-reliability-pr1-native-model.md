# Reliability Sprint PR 1 — Native and Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make local inference embedding, memory-pressure recovery, model installation accounting, and custom downloads dependable — without disabling any feature.

**Architecture:** `Engine::encode` moves from `llama_encode` to the upstream-correct `llama_decode` + `llama_get_embeddings_ith` path used by `examples/embedding/embedding.cpp`. A single `MemoryPressureReconciler` owns the level written to native, merging the push and poll signals by max severity with an explicit recovery transition. Import space accounting drops the redundant post-copy requirement. Custom download specs become durable so WorkManager survives process death.

**Tech Stack:** Kotlin 2.x / coroutines / Flow, C++20, llama.cpp (vendored `app/src/main/cpp/third_party/llama.cpp`), JNI, Gradle (AGP, flavors `dev`/`play`), JUnit4, WorkManager.

**Spec:** `docs/superpowers/specs/2026-10-06-reliability-sprint-design.md`

## Global Constraints

- Baseline `f13545f`. All Kotlin paths are relative to `app/src/main/java/com/prismai/llmhost/`; C++ paths to `app/src/main/cpp/`.
- **This PR touches `Engine.cpp`, so it runs the FULL gate `scripts/verify.ps1`** (unit tests + `assembleDevBenchmark` + `assemblePlayRelease`), not the debug loop. Debug builds never compile the RelWithDebInfo native config, R8/ProGuard rules, or the vulkan-shaders-gen host tool.
- Quick inner-loop check during development: `./gradlew --no-daemon :app:testDevDebugUnitTest`.
- Do NOT edit anything under `app/src/main/cpp/third_party/llama.cpp/` — it is a vendored submodule. Verify against its headers; do not patch it.
- Do NOT touch UI files (`ui/**`) in this PR. The link-external removal is PR 2 because its callers are UI.
- Preserve the existing generation-scoped cancellation, leased native handles, lifecycle gates, bounded streaming buffers, and output acknowledgement. This PR must not alter generation semantics.
- Preserve the "leased native handles" contract: every `NativeHandleRegistry::instance().acquire(handle)` in `llmhost_jni.cpp` stays intact.
- Discovered bugs are explicit behavior changes; record them in the commit message per AGENTS.md.

---

## File Structure

| File | Responsibility |
| ---- | -------------- |
| `Engine.cpp` | `encode()` — mean-pooled embeddings via the decode path |
| `storage/VectorStore.kt` | Embedding-dimension versioning so stale vectors are rejected, not compared |
| `storage/DocumentChunker.kt` | Owns the shared `QUERY_MAX_CHARS` bound |
| `storage/RagManager.kt` | Applies the query clamp before encoding |
| `service/MemoryGovernor.kt` | Emits trim + poll signals (unchanged source of truth) |
| `service/MemoryPressureReconciler.kt` (new) | Single owner of the native pressure level |
| `storage/ModelStorageManager.kt` | Import space accounting |
| `HuggingFaceModelCatalog.kt` | Durable custom-entry store; `Failure` carries identity |
| `model/ModelDownloadManager.kt` | Populates the new failure identity |

---

### Task 1: Route `Engine::encode` through the decoder path

**Why first:** this is the native change that carries the most risk, and PR 1's full gate depends on it. Doing it first means a native regression is isolated.

**Files:**
- Modify: `app/src/main/cpp/Engine.cpp:2124-2205` (`Engine::encode`)
- Test: `app/src/test/cpp/engine/engine_mock_test.cpp` (existing mock target; the encode path itself is device-only — see Step 6)

**Interfaces:**
- Consumes: `llama_decode`, `llama_get_embeddings_ith`, `llama_memory_clear`, `llama_get_memory`, `llama_batch_init`, `llama_batch_free` — all from vendored `llama.h`.
- Produces: unchanged external contract. `std::vector<float> Engine::encode(const std::string& text)` returns an empty vector on every failure path, exactly as today.

- [ ] **Step 1: Record the current failure mode in the mock test**

Read `app/src/test/cpp/engine/engine_mock_test.cpp` and append a test that asserts `encode` returns empty for a mock runtime without a model — the contract every caller already depends on:

```cpp
    // The mock runtime has no loaded model, so encode must return empty rather
    // than fabricate a vector. RagManager treats empty as failure.
    TEST(EngineMock, EncodeWithoutModelReturnsEmpty) {
        llmhost::Engine engine;
        EXPECT_TRUE(engine.encode("hello world").empty());
    }
```

If the file uses a different test framework macro than `TEST`, use the macro already present in that file. Do not introduce a new framework.

- [ ] **Step 2: Build and run the native host tests to confirm the new test passes on the current code**

Run:
```bash
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests --parallel 4
ctest --test-dir build/prism-native-tests --output-on-failure
```
Expected: `engine_mock_test` passes, including the new case. This is a characterization test — it passes before the change, which is intended, because `Engine::encode` already returns empty at `Engine.cpp:2131` when `runtime->ctx == nullptr`.

If this build is killed the same way CI is, see Step 6 — do not assume it is your change.

- [ ] **Step 3: Replace `llama_encode` with the decode path**

In `Engine::encode`, replace the block from `llama_set_embeddings(runtime->ctx, true);` through the end of the mean-pool loop (currently `Engine.cpp:2165-2202`) with:

```cpp
    // Embeddings for a decoder-only model go through llama_decode, matching
    // examples/embedding/embedding.cpp. llama_encode would request
    // LLM_GRAPH_TYPE_ENCODER, which build_arch_graph ignores for llama/qwen/bert,
    // forcing non-causal attention through a causal decoder graph.
    //
    // The KV cache is irrelevant here and reusing it would leak prompt-cache
    // state between unrelated documents, so clear it first (embedding.cpp:41).
    llama_memory_clear(llama_get_memory(runtime->ctx), true);
    llama_set_embeddings(runtime->ctx, true);

    // logits[i] must be set for EVERY token: with pooling_type == NONE the
    // per-token vectors live at the indices marked as outputs, and
    // llama_get_embeddings is documented to return NULL otherwise.
    // llama_context::decode sets output_all = cparams.embeddings, so the
    // allocator would mark all tokens for us, but we set them explicitly so the
    // intent survives a change to that default.
    llama_batch batch = llama_batch_init(actual, 0, 1);
    bool batch_ok = batch.token != nullptr && batch.logits != nullptr;
    if (batch_ok) {
        for (int32_t i = 0; i < actual; i++) {
            batch.token[i] = tokens[static_cast<size_t>(i)];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 1;
        }
        batch.n_tokens = actual;
    }

    const int32_t rc = batch_ok ? llama_decode(runtime->ctx, batch) : -1;
    llama_batch_free(batch);

    if (rc != 0) {
        llama_set_embeddings(runtime->ctx, false);
        LOGW("encode_decode_failed tokens=%d rc=%d", actual, rc);
        return {};
    }

    // Read the vectors BEFORE restoring non-embeddings mode.
    // llama_context::get_embeddings_ith indexes into embd.data, and
    // set_embeddings may reserve or release that buffer, so reading after
    // disabling it risks a stale pointer. It also throws std::runtime_error
    // (rather than returning null) when embd.data is null, so the read is
    // guarded.
    //
    // pooling_type == NONE is the only branch implemented: for pooled models
    // upstream reads llama_get_embeddings_seq, and mean-pooling an already-pooled
    // vector would be wrong, so those return empty and PR 2 surfaces the reason.
    const int32_t n_embd = llama_model_n_embd(runtime->model);
    std::vector<float> result(static_cast<size_t>(n_embd), 0.0f);
    int32_t pooled = 0;
    try {
        for (int32_t t = 0; t < actual; t++) {
            const float* token_emb = llama_get_embeddings_ith(runtime->ctx, t);
            if (token_emb == nullptr) continue;
            for (int32_t e = 0; e < n_embd; e++) {
                result[static_cast<size_t>(e)] += token_emb[e];
            }
            pooled++;
        }
    } catch (const std::exception&) {
        LOGW("encode_read_embeddings_failed tokens=%d", actual);
        llama_set_embeddings(runtime->ctx, false);
        return {};
    }

    llama_set_embeddings(runtime->ctx, false);

    if (pooled == 0) {
        LOGW("encode_no_token_embeddings tokens=%d n_embd=%d", actual, n_embd);
        return {};
    }
    const float inv_n = 1.0f / static_cast<float>(pooled);
    for (int32_t e = 0; e < n_embd; e++) {
        result[static_cast<size_t>(e)] *= inv_n;
    }

    return result;
```

Keep the existing token-size guard (`if (actual > runtime->batch_size)`) exactly where it is — it is load-bearing, keeping the batch within `n_ubatch` so `GGML_ASSERT(cparams.n_ubatch >= n_tokens)` in `llama-context.cpp:1269` cannot fire. Do not remove or relax it.

- [ ] **Step 4: Verify the native host tests still pass**

Run:
```bash
cmake --build build/prism-native-tests --parallel 4
ctest --test-dir build/prism-native-tests --output-on-failure
```
Expected: all pass. `Engine.cpp` must still compile — this catches any signature error against the vendored `llama.h`.

- [ ] **Step 5: Verify the Android native build compiles**

Run:
```bash
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: BUILD SUCCESSFUL, including `:app:buildCMakeDebug[arm64-v8a]` and `[x86_64]`. This is the real compile gate for the JNI layer; the host mock test does not compile `llmhost_jni.cpp`.

- [ ] **Step 6: Commit, and record the verification limit honestly**

```bash
git add app/src/main/cpp/Engine.cpp app/src/test/cpp/engine/engine_mock_test.cpp
git commit -m "fix(engine): compute embeddings through the decoder path

Engine::encode called llama_encode on decoder-only models. That requests
LLM_GRAPH_TYPE_ENCODER, but build_arch_graph ignores the requested graph
type for llama/qwen2/qwen3/bert and returns the causal decoder graph,
which llama_context::encode then runs with causal_attn forced off.

Route through llama_decode with logits set per token and read vectors
via llama_get_embeddings_ith, matching examples/embedding/embedding.cpp
which never calls llama_encode. Clear the KV cache first so prompt-cache
state cannot leak between unrelated documents.

Not a capability gate: llama_model_has_encoder is true only for T5 and
T5ENCODER, so gating on it would have rejected BERT and every
embedding-only model and disabled document ingestion app-wide.

The pooled (non-NONE pooling_type) branch is out of scope and returns
empty rather than double-pooling an already-pooled sequence vector.

Embedding values change, so stored vectors are incompatible with the
previous build. VectorStore gains dimension versioning in a follow-up
task in this PR; this commit ships the native change alone.

Verification: native host ctest passes; assembleDevDebug compiles the
JNI layer for arm64-v8a and x86_64. Real embedding output is NOT
verified - that needs a device and a real GGUF, neither available here.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Version stored embeddings so stale vectors are rejected

**Why:** Task 1 changes embedding values. Without versioning, existing installs silently compare new query vectors against old stored vectors and return meaningless matches.

**Files:**
- Modify: `storage/VectorStore.kt` (companion object, `DB_VERSION` at `:270`, `CREATE_TABLE_SQL` at `:280-289`, `search` at `:145`, `insert`/`insertBatch` at `:95`/`:107`)
- Test: `app/src/test/java/com/prismai/llmhost/storage/VectorStoreSchemaTest.kt` (create)

**Interfaces:**
- Consumes: `VectorStore.cosineSimilarity(a: FloatArray, b: FloatArray): Float` (`:300`), `VectorStore.floatArrayToBytes` / `bytesToFloatArray` (`:318`/`:329`).
- Produces: `VectorStore.CURRENT_EMBEDDING_DIM: Int` and `VectorStore.isCompatible(embedding: FloatArray): Boolean`, consumed by Task 3.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/storage/VectorStoreSchemaTest.kt`:

```kotlin
package com.prismai.llmhost.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorStoreSchemaTest {

    @Test
    fun currentEmbeddingDim_isPositive() {
        assertTrue(VectorStore.CURRENT_EMBEDDING_DIM > 0)
    }

    @Test
    fun isCompatible_acceptsVectorOfCurrentWidth() {
        val candidate = FloatArray(VectorStore.CURRENT_EMBEDDING_DIM) { 0.5f }
        assertTrue(VectorStore.isCompatible(candidate))
    }

    @Test
    fun isCompatible_rejectsVectorFromPreviousBuild() {
        val stale = FloatArray(VectorStore.CURRENT_EMBEDDING_DIM + 1) { 0.5f }
        assertFalse(VectorStore.isCompatible(stale))
    }

    @Test
    fun isCompatible_rejectsEmptyVector() {
        assertFalse(VectorStore.isCompatible(FloatArray(0)))
    }

    @Test
    fun cosineSimilarity_returnsZeroOnDimensionMismatch() {
        val a = FloatArray(4) { 1f }
        val b = FloatArray(8) { 1f }
        assertEquals(0f, VectorStore.cosineSimilarity(a, b), 0.0001f)
    }
}
```

The last test is the important one: a width mismatch must score 0 (i.e. "no match"), not read out of bounds. Check the current body of `cosineSimilarity` at `:300` — if it does not already handle differing lengths, that is a live out-of-bounds read and the test will fail.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*VectorStoreSchemaTest*'
```
Expected: FAIL to compile — `CURRENT_EMBEDDING_DIM` and `isCompatible` do not exist.

- [ ] **Step 3: Implement the dimension guard**

In `VectorStore`'s companion object, after `const val DB_VERSION = 1`, add:

```kotlin
        /**
         * Width of embedding vectors stored by this build. Task 1 of this PR
         * changed the embedding computation, so vectors written by earlier
         * builds are not comparable with new query vectors. Bump this when the
         * embedding path changes again.
         */
        const val CURRENT_EMBEDDING_DIM = 4096

        fun isCompatible(embedding: FloatArray): Boolean =
            embedding.isNotEmpty() && embedding.size == CURRENT_EMBEDDING_DIM
```

Set `CURRENT_EMBEDDING_DIM` to the `n_embd` of a real model you have verified; if no model's width is known at implementation time, use the value above as a placeholder and record in the commit that it must be confirmed on device. Do not silently leave it at 0.

Then guard `cosineSimilarity` at `:300` — make its first statement:

```kotlin
            if (!isCompatible(a) || !isCompatible(b)) return 0f
```

and add a matching guard at the top of `search` (`:145`) so a wrong-width query returns an empty list rather than scoring every row:

```kotlin
        if (!isCompatible(queryEmbedding)) return emptyList()
```

Leave `DB_VERSION` and `CREATE_TABLE_SQL` alone. A schema migration is not required: storing the width is unnecessary because a stale row simply fails the width check on read, which is the behavior we want.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*VectorStoreSchemaTest*'
```
Expected: PASS.

- [ ] **Step 5: Run the whole storage test package for regressions**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*storage*'
```
Expected: PASS, including the existing `VectorStoreTopKTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/storage/VectorStore.kt app/src/test/java/com/prismai/llmhost/storage/VectorStoreSchemaTest.kt
git commit -m "fix(storage): reject vectors from incompatible builds

Task 1 changed the embedding computation, so vectors already in a user's
prism_vector_store.db are not comparable with new query vectors. Without
a width check, search would score mismatched vectors and return
confident nonsense.

Add CURRENT_EMBEDDING_DIM and isCompatible, guard cosineSimilarity to
score 0 on mismatch, and short-circuit search. No schema migration: a
stale row fails the width check on read, which is the intended outcome.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Clamp the RAG query path

**Why:** `RagManager.query` encodes the raw user prompt with no length bound. Task 1 reintroduces the decode path, so an unbounded prompt now runs a real forward pass per token over the whole prompt.

**Files:**
- Modify: `storage/DocumentChunker.kt` (add companion constant near the `chunk` defaults at `:34`)
- Modify: `storage/RagManager.kt:135-151` (`query`)
- Test: `app/src/test/java/com/prismai/llmhost/storage/RagQueryClampTest.kt` (create)

**Interfaces:**
- Consumes: `RagManager`'s constructor-injected `encode: suspend (String) -> FloatArray` (`:24-29`) — this is what makes the clamp testable without a model.
- Produces: `DocumentChunker.QUERY_MAX_CHARS: Int`, consumed by `RagManager.query`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/storage/RagQueryClampTest.kt`:

```kotlin
package com.prismai.llmhost.storage

import com.prismai.llmhost.storage.DocumentChunker.Chunk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagQueryClampTest {

    private fun chunker() = DocumentChunker()
    private fun emptyStore() = object : Nothing() {}

    @Test
    fun query_encodesTextClampedToBound() = runTest {
        val encoded = mutableListOf<String>()
        // encode is injected, so this asserts the clamp without a model.
        val store = FakeVectorStore()
        val rag = RagManager(store, chunker()) { text ->
            encoded += text
            FloatArray(4) { 0.1f }
        }

        val huge = "x".repeat(DocumentChunker.QUERY_MAX_CHARS * 3)
        rag.query(huge, topK = 1)

        assertTrue("expected a clamp to have been applied", encoded.isNotEmpty())
        assertTrue(
            "encoded text exceeded the bound: ${encoded.first().length}",
            encoded.all { it.length <= DocumentChunker.QUERY_MAX_CHARS },
        )
    }

    @Test
    fun query_leavesShortTextIntact() = runTest {
        val encoded = mutableListOf<String>()
        val rag = RagManager(FakeVectorStore(), chunker()) { text ->
            encoded += text
            FloatArray(4) { 0.1f }
        }

        rag.query("short question", topK = 1)

        assertEquals(listOf("short question"), encoded)
    }
}
```

`VectorStore` is a concrete class over SQLite, so `FakeVectorStore` must be a real constructible substitute. Check whether `RagManager` can be given a minimal store for tests; if `VectorStore` cannot be constructed in a JVM unit test (it likely needs a `Context`), the cleanest fix is to introduce a narrow interface for the two methods `RagManager` actually uses (`search`, `insertBatch`) and implement it in tests. Do not contort the test to reach a database.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*RagQueryClampTest*'
```
Expected: FAIL — `DocumentChunker.QUERY_MAX_CHARS` does not exist, and the query text is passed through untruncated.

- [ ] **Step 3: Add the bound to `DocumentChunker`**

The bound belongs here, not in `RagManager`, so ingest and query cannot drift apart. Add to `DocumentChunker`'s companion object:

```kotlin
        /**
         * Maximum characters encoded for a retrieval query. Ingestion chunks to
         * 512 characters, so a query far larger than that spends decode work on
         * text that cannot match a single chunk well.
         */
        const val QUERY_MAX_CHARS = 2048
```

- [ ] **Step 4: Apply the clamp in `RagManager.query`**

At `storage/RagManager.kt:135`, replace the `encode(userPrompt)` call with a clamped version:

```kotlin
            // Bound the encoded text: Engine::encode runs a real decode pass over
            // every token, so an unbounded user prompt is unbounded work.
            val queryText = userPrompt.take(DocumentChunker.QUERY_MAX_CHARS)
            val queryEmbedding = runCatching {
                encode(queryText)
            }.getOrNull()
```

Leave the surrounding null/empty handling untouched.

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*RagQueryClampTest*'
```
Expected: PASS, including the short-text case proving no needless truncation.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/storage/DocumentChunker.kt app/src/main/java/com/prismai/llmhost/storage/RagManager.kt app/src/test/java/com/prismai/llmhost/storage/RagQueryClampTest.kt
git commit -m "fix(storage): bound the text encoded for a RAG query

RagManager.query encoded the raw user prompt with no length limit. Now
that Engine::encode performs a real decode pass per token, that is
unbounded work for a prompt that cannot match a 512-character chunk well.

Add QUERY_MAX_CHARS to DocumentChunker so ingest and query share one
bound, and clamp the query text.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Reconcile push and poll memory pressure

**Why:** today a CRITICAL push followed by a NORMAL poll is suppressed by `distinctUntilChanged()`, so native pressure stays at 3 and generation cancels forever.

**Files:**
- Create: `service/MemoryPressureReconciler.kt`
- Modify: `service/InferenceService.kt:627-645` (the two competing paths)
- Test: `app/src/test/java/com/prismai/llmhost/service/MemoryPressureReconcilerTest.kt` (create)

**Interfaces:**
- Consumes: `MemoryState` enum from `service/MemoryGovernor.kt:18-35` (levels `NORMAL=0`, `WATCH=1`, `PRESSURE=2`, `CRITICAL=3`).
- Produces:
  - `class MemoryPressureReconciler(private val apply: (Int) -> Unit)`
  - `fun onPush(state: MemoryState)`
  - `fun onPoll(state: MemoryState)`
  - `fun currentLevel(): Int`
  - `fun isCritical(): Boolean`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/service/MemoryPressureReconcilerTest.kt`:

```kotlin
package com.prismai.llmhost.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressureReconcilerTest {

    @Test
    fun criticalPushThenNormalPoll_clearsNativeLevel() {
        val applied = mutableListOf<Int>()
        val r = MemoryPressureReconciler { applied += it }

        r.onPush(MemoryState.CRITICAL)
        r.onPoll(MemoryState.NORMAL)

        assertEquals("native must be explicitly cleared after recovery", 0, r.currentLevel())
        assertEquals(0, applied.last())
        assertFalse("a recovered system is no longer critical", r.isCritical())
    }

    @Test
    fun repeatedNormalPolls_doNotRewriteNativeLevel() {
        val applied = mutableListOf<Int>()
        val r = MemoryPressureReconciler { applied += it }

        r.onPoll(MemoryState.NORMAL)
        r.onPoll(MemoryState.NORMAL)
        r.onPoll(MemoryState.NORMAL)

        assertEquals("unchanged polls must not spam native", 1, applied.size)
    }

    @Test
    fun normalPollAfterPressurePush_escalatesToPollSeverity() {
        val applied = mutableListOf<Int>()
        val r = MemoryPressureReconciler { applied += it }

        r.onPoll(MemoryState.PRESSURE)
        r.onPoll(MemoryState.WATCH)

        // A later observation supersedes an earlier one from the other path;
        // recovery must be able to lower pressure, not only raise it.
        assertEquals(1, r.currentLevel())
        assertEquals(1, applied.last())
    }

    @Test
    fun criticalIsStickyUntilANormalObservation() {
        val r = MemoryPressureReconciler { }

        r.onPush(MemoryState.CRITICAL)
        assertTrue(r.isCritical())

        r.onPoll(MemoryState.WATCH)
        assertTrue("WATCH must not mask CRITICAL", r.isCritical())
        assertEquals(3, r.currentLevel())

        r.onPoll(MemoryState.NORMAL)
        assertFalse(r.isCritical())
        assertEquals(0, r.currentLevel())
    }

    @Test
    fun pollEscalationWinsOverLowerPush() {
        val r = MemoryPressureReconciler { }

        r.onPush(MemoryState.WATCH)
        r.onPoll(MemoryState.CRITICAL)

        assertEquals(3, r.currentLevel())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*MemoryPressureReconcilerTest*'
```
Expected: FAIL to compile — `MemoryPressureReconciler` does not exist.

- [ ] **Step 3: Implement the reconciler**

Create `service/MemoryPressureReconciler.kt`:

```kotlin
package com.prismai.llmhost.service

/**
 * Single owner of the memory-pressure level written to native inference.
 *
 * Memory pressure reaches us from two independent paths: the push-based
 * `onTrimMemory` / `onLowMemory` callback, and the polling flow. Previously
 * each wrote to native itself, and only the polling flow was deduplicated, so a
 * CRITICAL push was followed by a NORMAL poll that was filtered as "unchanged"
 * and never cleared it — leaving native pressure pinned at CRITICAL and
 * cancelling generation indefinitely.
 *
 * The rule is: an observation from either path is applied unless it is identical
 * to the last applied level. Recovery therefore always writes explicitly, and
 * CRITICAL is never masked by a milder observation of the other kind.
 */
class MemoryPressureReconciler(
    private val apply: (Int) -> Unit,
) {
    private var lastApplied: Int? = null
    private var critical = false

    /** Push-based trim/low-memory callback. Escalates immediately. */
    fun onPush(state: MemoryState) {
        if (state.level >= MemoryState.CRITICAL.level) {
            critical = true
        }
        // A push that is milder than an active CRITICAL must not clear it.
        if (critical && state.level < MemoryState.CRITICAL.level) return
        write(state.level)
    }

    /**
     * Polling observation. A NORMAL poll is the only signal that can mean
     * "memory recovered", so it is allowed to clear an escalation - including
     * one that came from the push path.
     */
    fun onPoll(state: MemoryState) {
        if (state.level >= MemoryState.CRITICAL.level) {
            critical = true
        } else if (state.level == MemoryState.NORMAL) {
            critical = false
        }
        write(state.level)
    }

    fun currentLevel(): Int = lastApplied ?: MemoryState.NORMAL.level

    fun isCritical(): Boolean = critical

    private fun write(level: Int) {
        if (lastApplied == level) return
        lastApplied = level
        apply(level)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*MemoryPressureReconcilerTest*'
```
Expected: PASS. Note `criticalPushThenNormalPoll_clearsNativeLevel` is the regression test for the reported bug.

- [ ] **Step 5: Wire both paths through the reconciler**

In `InferenceService`, replace the competing blocks at `:627-645` with a single owner. Construct once near the other service fields:

```kotlin
    private val memoryPressureReconciler = MemoryPressureReconciler { level ->
        engine.setMemoryPressure(level)
    }
```

Replace the push registration:

```kotlin
        // Push-based trim callback (instant notification). Routed through the
        // reconciler so it cannot race the polling flow into native.
        memoryGovernor.register { state ->
            memoryPressureReconciler.onPush(state)
            if (state == MemoryState.CRITICAL) {
                // The push path previously skipped this entirely.
                saveTranscriptSafely()
                if (!criticalMemoryAlertActive) {
                    publishUiEvent("Memory critical; transcript saved")
                }
                criticalMemoryAlertActive = true
            }
        }
```

Replace the polling collector:

```kotlin
        // Polling is the fallback for gradual pressure. distinctUntilChanged()
        // is removed: the reconciler owns dedup, and it must be able to write an
        // explicit recovery after a push escalation.
        memoryGovernor.monitorMemory()
            .onEach { state ->
                memoryPressureReconciler.onPoll(state)
                criticalMemoryAlertActive = memoryPressureReconciler.isCritical()
            }
            .launchIn(serviceScope)
```

Behavior change, explicitly: native pressure can now be lowered by a recovery
observation that previously had no effect. That is the fix.

- [ ] **Step 6: Run the full unit suite**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/service/MemoryPressureReconciler.kt app/src/main/java/com/prismai/llmhost/service/InferenceService.kt app/src/test/java/com/prismai/llmhost/service/MemoryPressureReconcilerTest.kt
git commit -m "fix(service): reconcile push and poll memory pressure in one owner

Two paths wrote native pressure: the onTrimMemory/onLowMemory push
callback and the polling flow. Only polling was deduplicated via
distinctUntilChanged, so a CRITICAL push was followed by a NORMAL poll
that was filtered as unchanged and never cleared native pressure. Native
generation then kept cancelling after memory recovered.

Introduce MemoryPressureReconciler as the single writer. An observation
from either path is applied unless identical to the last applied level,
so recovery always writes explicitly, and CRITICAL is never masked by a
milder observation of the other kind.

The push path now also saves the transcript and emits the UI event, which
it previously skipped.

Behavior change: native pressure can be lowered by a recovery observation
that previously had no effect.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Fix import space accounting

**Why:** the post-copy check demands the full model size in free space *again* after that size is already on disk, so a model that fits fails after a long wait.

**Files:**
- Modify: `storage/ModelStorageManager.kt` — `hasUsableSpaceFor` (`:878-890`), pre-copy call (`:237`), mid-copy call (`:628-630`), post-copy call (`:246`)
- Test: `app/src/test/java/com/prismai/llmhost/storage/ImportSpaceAccountingTest.kt` (create)

**Interfaces:**
- Consumes: `StatFs` via `hasUsableSpaceFor(bytes: Long): Boolean`.
- Produces:
  - `ModelStorageManager.hasReserveSpace(): Boolean` — reserve-only check for the post-copy gate.
  - `ModelStorageManager.hasUsableSpaceFor(bytes: Long): Boolean` — unchanged signature, pre-copy meaning unchanged.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/storage/ImportSpaceAccountingTest.kt`:

```kotlin
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
```

These are pure arithmetic predicates over an injected free-space value. The
implementer should extract them as `internal`/public statics on
`ModelStorageManager` so the arithmetic is testable without a real filesystem —
`StatFs` cannot be constructed in a JVM unit test.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*ImportSpaceAccountingTest*'
```
Expected: FAIL to compile — the predicates do not exist.

- [ ] **Step 3: Extract the predicates**

In `ModelStorageManager`, replace `hasUsableSpaceFor` (`:878-890`) with:

```kotlin
    /**
     * True when [freeBytes] covers a model of [modelBytes] plus the post-import
     * reserve. Used before the copy, where the model's bytes are still to be
     * written.
     */
    fun hasUsableSpaceFor(freeBytes: Long, modelBytes: Long): Boolean =
        freeBytes > modelBytes + MIN_FREE_SPACE_AFTER_IMPORT

    /**
     * True when [freeBytes] still covers the post-import reserve. Used after the
     * staged copy has landed: those bytes are already on disk, and
     * promoteDirectory is a rename that consumes no additional space, so
     * requiring the model size again rejected models that genuinely fit.
     */
    fun hasReserveAfterCopy(freeBytes: Long): Boolean =
        freeBytes > MIN_FREE_SPACE_AFTER_IMPORT

    private fun freeSpaceBytes(): Long {
        val dir = modelsDir.also { it.mkdirs() }
        return StatFs(dir.absolutePath).availableBytes
    }

    private fun hasUsableSpaceFor(bytes: Long): Boolean =
        hasUsableSpaceFor(freeSpaceBytes(), bytes)

    private fun hasReserveSpace(): Boolean =
        hasReserveAfterCopy(freeSpaceBytes())
```

Keep `MIN_FREE_SPACE_AFTER_IMPORT` (`:976`) at its current 512 MiB value.

- [ ] **Step 4: Fix the post-copy call site**

At `:246`, replace:

```kotlin
        if (!hasUsableSpaceFor(bytes)) {
            return ImportResult.Failure(insufficientSpaceError(bytes)).also { cleanup(stagingDir) }
        }
```

with:

```kotlin
        // The model bytes are already on disk at this point and promotion is a
        // rename, so only the reserve must remain. Requiring the full size again
        // made a fitting model fail after the entire copy completed.
        if (!hasReserveSpace()) {
            return ImportResult.Failure(insufficientSpaceError(bytes)).also { cleanup(stagingDir) }
        }
```

Leave the pre-copy check at `:237` and the mid-copy guard at `:628-630` unchanged. The pre-copy check is correct, and the mid-copy one already passes `0L` so it is reserve-only.

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*ImportSpaceAccountingTest*'
```
Expected: PASS.

- [ ] **Step 6: Run the storage suite for regressions**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*storage*' --tests '*Import*'
```
Expected: PASS, including `ModelIdentityDeletionTest`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/storage/ModelStorageManager.kt app/src/test/java/com/prismai/llmhost/storage/ImportSpaceAccountingTest.kt
git commit -m "fix(storage): stop double-counting model space after import

hasUsableSpaceFor compared free space against bytes + 512 MiB reserve both
before and after the copy. The post-copy gate therefore demanded room for
the full model a second time, after those bytes were already on disk, so
a model that genuinely fit failed only after the entire copy completed.

promoteDirectory is a rename and consumes no additional space, so the
post-copy gate now checks the reserve alone. The pre-copy check is
unchanged - that is where the model's bytes are still to be written.

Behavior change: an import that previously failed after a full copy can
now succeed. Verified by unit tests over the extracted predicates;
not yet reproduced on device with a real multi-GB model.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Persist custom download specs across process death

**Why:** `customEntries` is a process-local map, but WorkManager persists only the entry id. Any download that survives process death — the reason WorkManager is used — fails at `HuggingFaceDownloadWorker.kt:87-90`.

**Files:**
- Modify: `HuggingFaceModelCatalog.kt` — `customEntries` (`:383`), `find` (`:385`), `createCustomEntry` (`:387-405`)
- Test: `app/src/test/java/com/prismai/llmhost/CustomDownloadSpecPersistenceTest.kt` (create)

**Interfaces:**
- Consumes: `HuggingFaceModelEntry` data class (`HuggingFaceModelCatalog.kt:15-36`), fields `id`, `name`, `repoId`, `fileName`, `expectedBytes`, `license`, `parameters`, `quantization`, `notes`, `curated`.
- Produces:
  - `CustomEntryStore` — persists and restores `HuggingFaceModelEntry` rows as JSON.
  - `HuggingFaceModelCatalog.find(id: String): HuggingFaceModelEntry?` — unchanged signature, now consults the store.
  - `HuggingFaceModelCatalog.createCustomEntry(repoId: String, fileName: String): HuggingFaceModelEntry` — unchanged signature, now persists.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/CustomDownloadSpecPersistenceTest.kt`:

```kotlin
package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomDownloadSpecPersistenceTest {

    @Test
    fun specRoundTrips_throughTheStore() {
        val store = FakeCustomEntryStore()
        val original = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "model.gguf")
        store.put(original)

        // Simulate process death: a fresh store instance reads the persisted row.
        val restored = FakeCustomEntryStore(rehydratedFrom = store)
        val found = restored.find(original.id)

        assertNotNull("a queued download must survive process death", found)
        assertEquals("user/repo", found!!.repoId)
        assertEquals("model.gguf", found.fileName)
        assertEquals(original.id, found.id)
    }

    @Test
    fun restoredEntry_isNeverCurated() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
        assertTrue("custom entries must stay unverified-verifiable", !entry.curated)
    }

    @Test
    fun restoredEntry_keepsExpectedBytesAndMetadata() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
        assertEquals(-1L, entry.expectedBytes)
        assertEquals("Custom", entry.parameters)
        assertNull(HuggingFaceModelCatalog.find("custom_does_not_exist"))
    }
}
```

`HuggingFaceModelCatalog` is an `object` singleton, which makes instance state
hard to isolate in tests. The implementation task should therefore split the
pure serialization into a `CustomEntryStore` class that takes its persistence
backend as a constructor parameter, so the test can supply a fake. Keep the
object's public API unchanged.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*CustomDownloadSpecPersistenceTest*'
```
Expected: FAIL — there is no durable store, and `buildCustomEntry` (pure, no
persistence side effect) does not exist.

- [ ] **Step 3: Implement durable storage**

Add to `HuggingFaceModelCatalog.kt`:

```kotlin
/**
 * Durable storage for user-supplied download specs.
 *
 * A WorkManager request persists only the entry id, but WorkManager's whole
 * purpose is surviving process death. With specs held in a process-local map, a
 * queued custom download failed in doWork with "Model catalog entry not found"
 * after any restart. Backend is injected so this is unit-testable without a
 * Context; the production instance uses SharedPreferences.
 */
class CustomEntryStore(private val backend: Backend) {

    interface Backend {
        fun readAll(): List<String>
        fun writeAll(rows: List<String>)
    }

    fun put(entry: HuggingFaceModelEntry) {
        val rows = findAll().map { serialize(it) } .filterNot { idOf(it) == entry.id }
        backend.writeAll(rows + serialize(entry))
    }

    fun find(id: String): HuggingFaceModelEntry? =
        findAll().firstOrNull { it.id == id }

    fun findAll(): List<HuggingFaceModelEntry> =
        backend.readAll().mapNotNull { deserialize(it) }

    companion object {
        private const val PREFS_NAME = "prism_custom_downloads"

        fun forContext(context: Context): CustomEntryStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return CustomEntryStore(object : Backend {
                override fun readAll(): List<String> =
                    prefs.all.values.filterIsInstance<String>()

                override fun writeAll(rows: List<String>) {
                    prefs.edit().clear().apply()
                    rows.forEachIndexed { i, row -> prefs.putString("entry_$i", row) }
                }
            })
        }

        fun serialize(entry: HuggingFaceModelEntry): String = JSONObject().apply {
            put("id", entry.id)
            put("name", entry.name)
            put("repoId", entry.repoId)
            put("fileName", entry.fileName)
            put("expectedBytes", entry.expectedBytes)
            put("license", entry.license)
            put("parameters", entry.parameters)
            put("quantization", entry.quantization)
            put("notes", entry.notes)
            // Never restore curated=true: an unverified custom download must not
            // become integrity-gated, nor bypass the fail-closed policy.
            put("curated", false)
        }.toString()

        fun idOf(json: String): String =
            runCatching { JSONObject(json).optString("id") }.getOrDefault("")

        fun deserialize(json: String): HuggingFaceModelEntry? = runCatching {
            val o = JSONObject(json)
            HuggingFaceModelEntry(
                id = o.optString("id"),
                name = o.optString("name"),
                repoId = o.optString("repoId"),
                fileName = o.optString("fileName"),
                expectedBytes = o.optLong("expectedBytes", -1L),
                license = o.optString("license", "Community / Unspecified"),
                parameters = o.optString("parameters", "Custom"),
                quantization = o.optString("quantization", "Auto"),
                notes = o.optString("notes", ""),
                curated = false,
            ).takeIf { it.id.isNotBlank() && it.repoId.isNotBlank() }
        }.getOrNull()
    }
}
```

Then make the object thread-safe and durable. Replace `customEntries` (`:383`)
and `find`/`createCustomEntry` (`:385-405`) with:

```kotlin
    private val customStore: CustomEntryStore by lazy {
        CustomEntryStore.forContext(appContext)
    }

    fun find(id: String): HuggingFaceModelEntry? =
        entries.firstOrNull { it.id == id } ?: customStore.find(id)

    /** Pure builder; does not persist. Call [registerCustom] to persist. */
    fun buildCustomEntry(repoId: String, fileName: String): HuggingFaceModelEntry {
        val cleanRepo = repoId.trim().trim('/')
        val cleanFile = fileName.trim().removePrefix("/")
        val id = "custom_" + (cleanRepo + "_" + cleanFile)
            .replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        return HuggingFaceModelEntry(
            id = id, name = cleanFile.removeSuffix(".gguf"),
            repoId = cleanRepo, fileName = cleanFile,
            expectedBytes = -1L,
            license = "Community / Unspecified", parameters = "Custom",
            quantization = "Auto",
            notes = "User-submitted custom Hugging Face GGUF repository",
            curated = false,
        )
    }

    fun createCustomEntry(repoId: String, fileName: String): HuggingFaceModelEntry {
        val entry = buildCustomEntry(repoId, fileName)
        customStore.put(entry)
        return entry
    }
```

Note this removes the `mutableMapOf` entirely, which also fixes the data race:
`createCustomEntry` is called from the UI thread and `find` from the WorkManager
thread, and the map was neither synchronized nor persistent.

If the object has no `appContext`, thread one in from `InferenceService`, which
already constructs the catalog with a `Context`.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*CustomDownloadSpecPersistenceTest*'
```
Expected: PASS, including the rehydration case.

- [ ] **Step 5: Confirm the worker now resolves custom entries after restart**

Check `HuggingFaceDownloadWorker.kt:87-90` still calls `HuggingFaceModelCatalog.find(entryId)`. It does, and that call now succeeds post-restart with no worker change required. Add one assertion to the test file that `find` on a persisted-but-not-in-memory id returns the entry, to lock in the worker's actual dependency.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/HuggingFaceModelCatalog.kt app/src/main/java/com/prismai/llmhost/CustomEntryStore.kt app/src/test/java/com/prismai/llmhost/CustomDownloadSpecPersistenceTest.kt
git commit -m "fix(downloads): persist custom download specs across process death

WorkManager persists only the entry id, but custom entries lived in a
process-local mutableMapOf. Any queued custom download that survived
process death - the entire reason for using WorkManager - failed in
doWork with \"Model catalog entry not found\", because the map was rebuilt
empty in the new process.

Persist the full spec (repo, filename, expected bytes, metadata) via
CustomEntryStore over SharedPreferences, with the backend injected so the
round-trip is unit-testable. Restored entries always carry curated=false,
preserving the existing fail-closed integrity policy.

Also removes a data race: createCustomEntry runs on the UI thread and
find on the WorkManager thread, against an unsynchronized map.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Give download failures an identity

**Why:** `ModelDownloadState.Failure` carries only `entryName`, so the Retry button falls back to the dropdown selection and re-downloads the wrong model. PR 2 consumes the id this adds.

**Files:**
- Modify: `HuggingFaceModelCatalog.kt:60` (`ModelDownloadState.Failure`)
- Modify: `model/ModelDownloadManager.kt:109-118` and `:182-188`
- Test: `app/src/test/java/com/prismai/llmhost/model/DownloadFailureIdentityTest.kt` (create)

**Interfaces:**
- Consumes: `HuggingFaceDownloadWork.KEY_ENTRY_ID`, already read at `ModelDownloadManager.kt:109`.
- Produces: `ModelDownloadState.Failure(val entryId: String?, val entryName: String, val message: String)`. PR 2 reads `entryId`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/model/DownloadFailureIdentityTest.kt`:

```kotlin
package com.prismai.llmhost.model

import com.prismai.llmhost.HuggingFaceModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadFailureIdentityTest {

    @Test
    fun failure_carriesTheEntryIdSoRetryTargetsTheFailedModel() {
        val state: HuggingFaceModelCatalog.ModelDownloadState =
            HuggingFaceModelCatalog.ModelDownloadState.Failure(
                entryId = "model_b",
                entryName = "B",
                message = "Download failed",
            )
        val failure = state as HuggingFaceModelCatalog.ModelDownloadState.Failure
        assertEquals("model_b", failure.entryId)
        assertEquals("B", failure.entryName)
    }

    @Test
    fun failureResolvesBackToItsCatalogEntry() {
        val entry = HuggingFaceModelCatalog.entries.first()
        val failure = HuggingFaceModelCatalog.ModelDownloadState.Failure(
            entryId = entry.id,
            entryName = entry.name,
            message = "x",
        )
        assertNotNull(HuggingFaceModelCatalog.find(failure.entryId!!))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DownloadFailureIdentityTest*'
```
Expected: FAIL to compile — `Failure` has no `entryId`.

- [ ] **Step 3: Add the field**

At `HuggingFaceModelCatalog.kt:60`:

```kotlin
    data class Failure(
        /** Catalog id of the model that failed. Nullable only for legacy states. */
        val entryId: String?,
        val entryName: String,
        val message: String,
    ) : ModelDownloadState()
```

Give `entryId` a default of `null` if it eases call-site churn elsewhere, but prefer the explicit form so no constructor call is silently left without identity.

- [ ] **Step 4: Populate it from work data**

At `model/ModelDownloadManager.kt:182-188`, the `WorkInfo.State.FAILED` branch:

```kotlin
            WorkInfo.State.FAILED -> {
                val failure = message.ifBlank { "Download failed" }
                uiState._modelDownloadState.value =
                    ModelDownloadState.Failure(entryId, entryName, failure)
```

`entryId` and `entryName` are already in scope from the lookup at `:109-113`. Verify that; if `entryId` is scoped inside the `entry?.let` block, hoist it so the `FAILED` branch can read it.

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DownloadFailureIdentityTest*'
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/HuggingFaceModelCatalog.kt app/src/main/java/com/prismai/llmhost/model/ModelDownloadManager.kt app/src/test/java/com/prismai/llmhost/model/DownloadFailureIdentityTest.kt
git commit -m "fix(downloads): carry the entry id on download failures

ModelDownloadState.Failure stored only entryName, so the Retry Download
button fell back to the dropdown's selected entry - which defaults to the
first catalog item. Retrying a failure on model B silently re-downloaded
model A.

KEY_ENTRY_ID was already read at ModelDownloadManager.kt:109 but
discarded. Carry it on the failure state so PR 2 can target the model
that actually failed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Remove dead `LightweightEmbeddingEngine`

**Why:** it is the only object named like an embedding engine, and it is dead. Leaving it invites a future reader to wire the wrong path.

**Files:**
- Delete: `storage/LightweightEmbeddingEngine.kt`
- Modify: `ADVERSARIAL_AUDIT_2026-07-30.md`, `FINDINGS_REPORT_2026-07-31.md` (one-line breadcrumb only)

**Interfaces:**
- Consumes: none.
- Produces: none. Pure deletion.

- [ ] **Step 1: Confirm it is genuinely unreferenced**

Run:
```bash
grep -rn "LightweightEmbeddingEngine" app/src/ scripts/ 2>/dev/null
```
Expected: only the declaration line inside the file itself. If any code or test reference exists, stop and report rather than deleting.

- [ ] **Step 2: Delete the file**

```bash
git rm app/src/main/java/com/prismai/llmhost/storage/LightweightEmbeddingEngine.kt
```

- [ ] **Step 3: Leave a breadcrumb in the historical audit documents**

Do not rewrite the dated findings. Append one line to each document that
references the file, noting the removal and the date, so a reader who follows
the old reference is not misled:

```markdown
> 2026-10-06: `storage/LightweightEmbeddingEngine.kt` referenced above was
> deleted as dead code. No code, test, or benchmark referenced it; the only
> callers are in this document. Document ingestion uses `RagManager` with
> `NativeLlmBridge.encode`.
```

- [ ] **Step 4: Compile to confirm nothing referenced it**

Run:
```bash
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore(storage): delete dead LightweightEmbeddingEngine

Named like the embedding path but referenced by no code, test, or
benchmark - only by two dated audit documents, one of which already marks
PRISM-14 false. Leaving it invites wiring the wrong engine.

Historical documents get a breadcrumb rather than a rewrite; they are
dated findings and editing them would destroy the record.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Full verification gate

**Why:** this PR changes `Engine.cpp`. Per AGENTS.md a debug-only build is insufficient — `assembleDevDebug` never compiles the RelWithDebInfo native config, R8/ProGuard rules, or the vulkan-shaders-gen host tool.

**Files:** none. Verification only.

**Interfaces:**
- Consumes: `scripts/verify.ps1`.
- Produces: recorded evidence that native + release builds compile.

- [ ] **Step 1: Run the full gate**

Run:
```bash
pwsh ./scripts/verify.ps1
```
If PowerShell is unavailable on this host, run the equivalent directly and say so in the report:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
```
Expected: all succeed. `assembleDevBenchmark` is the one that compiles the RelWithDebInfo native config and R8 rules.

- [ ] **Step 2: Re-run the native host tests**

Run:
```bash
cmake --build build/prism-native-tests --parallel 4
ctest --test-dir build/prism-native-tests --output-on-failure
```
Expected: all pass. If the compiler is killed here as it is on CI, that is the PR 3 issue and is **not** caused by this PR — record it and continue.

- [ ] **Step 3: Report honestly what was and was not verified**

State explicitly:
- Run and passing: name the exact commands and their results.
- **Not verified: real embedding output, memory-pressure recovery on device, and a real multi-GB import.** All three need hardware or a real GGUF. Do not claim them.

- [ ] **Step 4: Do not push without user approval**

Per AGENTS.md, real-inference claims need current device evidence. Present the evidence and ask before pushing or opening a PR.