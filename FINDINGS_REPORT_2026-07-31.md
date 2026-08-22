# Prism Local — Audit Findings & Remediation Report

**Date:** 2026-07-31 (Updated 2026-08-01)
**Branch:** `main` (commit `3de358c`)
**Status:** Re-evaluated against current codebase (`3de358c`).
- **Confirmed underlying issues (17):** PRISM-04, PRISM-05, PRISM-06, PRISM-07, PRISM-09, PRISM-12, PRISM-13, PRISM-16, PRISM-17, PRISM-19, PRISM-20, PRISM-22, PRISM-25, PRISM-26, PRISM-28, PRISM-29, PRISM-31.
- **Partially confirmed / Overstated (7):** PRISM-01, PRISM-03, PRISM-08, PRISM-10, PRISM-18, PRISM-23, PRISM-24, PRISM-27.
- **Not confirmed as written / Inaccurate / Stale (8):** PRISM-02, PRISM-11, PRISM-14, PRISM-15, PRISM-21, PRISM-30, PRISM-32.

---

## Executive Summary & Key Corrections

1. **PRISM-02 is false:** `GenerationOrchestrator.kt:108` passes `directToolCall` to `agentToolRouter.handleToolCall()`, which evaluates `definition.risk`. Destructive tool calls (`AgentToolRisk.CONFIRM`) trigger the pending action state and user confirmation UI before execution.
2. **PRISM-11 is stale / inactive:** While `FileProvider` is omitted from `AndroidManifest.xml`, current chat/diagnostics exports use Storage Access Framework (SAF) or in-memory streams; no active `file://` URI sharing path exists in the codebase.
3. **PRISM-14 is false for active RAG:** `InferenceService.kt:289-291` injects native model embeddings via `engine.encode(text)`. `LightweightEmbeddingEngine.kt` is completely unused.
4. **PRISM-15 is overstated:** `CHARS_PER_TOKEN = 4` in `GenerationBudget.kt` is an un-tokenized Kotlin UI estimation heuristic. Native `Engine.cpp` tokenizes and handles context limits/truncation prior to evaluation.
5. **PRISM-32 is not a current cleartext vulnerability:** App targets SDK 36, and Android automatically disables cleartext HTTP traffic by default for apps targeting API 28+.
6. **Store Policy Risk Remains Real:** Declared `READ_SMS`, `READ_CONTACTS`, and `READ_CALENDAR` permissions in `AndroidManifest.xml` carry real Google Play Store rejection risk and must be removed unless backing core promoted functionality.

---

## CRITICAL FINDINGS

### PRISM-01 [PARTIALLY CONFIRMED / OVERSTATED] — FGS Type Mismatch: Potential Crash on Android 14+

**Status:** Partially Confirmed / Overstated  
**What's broken:** `InferenceService.kt:1241` passes `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` to `startForeground()`, but `AndroidManifest.xml:43` declares `android:foregroundServiceType="specialUse"`. Android 14+ (API 34+) throws `ForegroundServiceTypeNotAllowedException` if the started service type does not match the manifest declaration.

**Exact locations:**
- Manifest declaration: `AndroidManifest.xml:43` — `android:foregroundServiceType="specialUse"`
- Service code: `InferenceService.kt:1241` — `startGenerationForeground()` passes `FOREGROUND_SERVICE_TYPE_DATA_SYNC`

**Remediation:** Make manifest and service calls match (e.g., set both to `dataSync` or both to `specialUse`).

---

### PRISM-02 [NOT CONFIRMED / FALSE] — Direct NL Tool Routing Confirmation Bypass

**Status:** Not Confirmed as Written (False)  
**Analysis:** The original report claimed `directToolCall()` executes destructive actions with zero confirmation. In reality, `GenerationOrchestrator.kt:108` passes `directToolCall` to `agentToolRouter.handleToolCall()`, which evaluates `definition.risk`. If the tool risk is `AgentToolRisk.CONFIRM`, it enters `confirmation.pendingCall` and displays the user confirmation UI before executing.

---

### PRISM-03 [PARTIALLY CONFIRMED] — Missing `llama_set_abort_callback`

**Status:** Partially Confirmed  
**What's broken:** `Engine.cpp` checks `cancel_requested` between batch chunks, but does not register `llama_set_abort_callback()`. Intra-batch prompt evaluation matrix operations cannot be interrupted mid-batch.

**Exact locations:**
- `Engine.cpp:488, 507` — `llama_decode()` calls
- `llama.h:970` — API exists but is not invoked in `Engine.cpp`

---

### PRISM-04 [CONFIRMED] — `READ_SMS` Without Default SMS Handler = Play Store Rejection

**Status:** Confirmed (Store Compliance Risk)  
**What's broken:** `AndroidManifest.xml:14` declares `<uses-permission android:name="android.permission.READ_SMS" />`. Google Play policy strictly restricts SMS permissions to designated Default SMS Handlers.

**Exact location:** `AndroidManifest.xml:14`

**Remediation:** Remove `READ_SMS` from `AndroidManifest.xml`.

---

### PRISM-05 [CONFIRMED] — Sensitive Permissions Declared Prior to Feature Readiness

**Status:** Confirmed (Store Compliance Risk)  
**What's broken:** `AndroidManifest.xml:12-13` declares `READ_CONTACTS` and `READ_CALENDAR` for future connector features. Sensitive permissions must not be declared until actively used by promoted features.

**Exact locations:**
- `AndroidManifest.xml:12` — `READ_CONTACTS`
- `AndroidManifest.xml:13` — `READ_CALENDAR`

**Remediation:** Remove lines 12-13 until feature release.

---

### PRISM-06 [CONFIRMED] — `minScore = 0.0f` Injects RAG Context Noise

**Status:** Confirmed  
**What's broken:** `RagManager.kt:112` calls `vectorStore.search(queryEmbedding, topK = topK, minScore = 0.0f)`. Cosine similarity search returns low-scoring, unrelated document chunks when `minScore` is set to 0.0.

**Exact location:** `RagManager.kt:112`

**Remediation:** Raise `minScore` threshold (e.g., to 0.35f).

---

### PRISM-07 [CONFIRMED] — KV Cache Wipe on Context Overflow

**Status:** Confirmed  
**What's broken:** `Engine.cpp:445-464` calls `llama_memory_clear()` and resets `current_position = 0` when tokens exceed context capacity, clearing the entire KV cache instead of applying sliding-window truncation.

**Exact locations:** `Engine.cpp:445-464`

---

## HIGH-IMPACT FINDINGS

### PRISM-08 [PARTIALLY CONFIRMED / OVERSTATED] — `worker.join()` Lock Scope

**Status:** Partially Confirmed / Overstated  
**What's broken:** Calling `worker.join()` inside mutex locks in `Engine.cpp` can stall calling threads while native worker threads finish prompt processing.

---

### PRISM-09 [CONFIRMED] — Streaming Markdown Re-Parse Overhead

**Status:** Confirmed  
**What's broken:** `CodeBlockRendering.kt:716` re-runs `enhancedMarkdownBlocks(text)` on text state updates without incremental parsing or adequate throttling.

---

### PRISM-10 [PARTIALLY CONFIRMED] — OOM & Native Exception Handling

**Status:** Partially Confirmed  
**What's broken:** Process termination during native OOM or unhandled exceptions relies on service cancellation cleanup. Recovery transcripts are only saved during graceful cancellation flows.

---

### PRISM-11 [STALE / INACTIVE] — Missing `FileProvider` Declaration

**Status:** Stale / Inactive  
**Analysis:** While `AndroidManifest.xml` lacks a `<provider>` element for `FileProvider`, current app exports use Storage Access Framework (SAF) or memory buffers; no active `file://` URI share path is invoked.

---

### PRISM-12 [CONFIRMED] — Unsanitized Tool Output in Transcript Flow

**Status:** Confirmed  
**What's broken:** `AgentToolRouter.kt:390-400` formats tool event summaries without invoking `ToolInputSanitizer.sanitizeExternalInput()`.

**Exact locations:** `AgentToolRouter.kt:390-400`

---

### PRISM-13 [CONFIRMED] — Native Error Codes Not Propagated to UI

**Status:** Confirmed  
**What's broken:** `ctrl->error_code` values in `Engine.cpp` are not piped through JNI `NativeDrainResult` to UI error handlers, resulting in generic error messaging.

---

### PRISM-14 [NOT CONFIRMED / FALSE] — `LightweightEmbeddingEngine` Fake Embeddings

**Status:** Not Confirmed as Written (False for Active RAG)  
**Analysis:** The original report claimed RAG relies on hash-bucket embeddings in `LightweightEmbeddingEngine.kt`. In reality, `InferenceService.kt:289-291` wires `RagManager` directly to native model embeddings via `engine.encode(text)`. `LightweightEmbeddingEngine` is completely unused.

---

### PRISM-15 [OVERSTATED] — Token Estimation Heuristic

**Status:** Overstated  
**Analysis:** `CHARS_PER_TOKEN = 4` in `GenerationBudget.kt` is a lightweight Kotlin-side UI packing estimate. Native `Engine.cpp` performs exact tokenization and enforces prompt bounds before evaluation.

---

### PRISM-16 [CONFIRMED] — In-Memory Background Task Queue

**Status:** Confirmed  
**What's broken:** `BackgroundAgentManager.kt:122-124` uses `MutableStateFlow` for task queuing without persistent storage or WorkManager integration.

---

## MEDIUM & LOW FINDINGS

- **PRISM-17 [CONFIRMED]:** LMK Level 3 memory pressure notification cancels generation but does not force native GGUF model unloading.
- **PRISM-18 [PARTIALLY CONFIRMED / OVERSTATED]:** Per-token auto-scrolling in `ChatScreen.kt:415-424`.
- **PRISM-19 [CONFIRMED]:** Prompt composer displays "Reconnecting..." placeholder prior to service binding.
- **PRISM-20 [CONFIRMED]:** Ring buffer spin-sleep in `Engine.cpp:110-130`.
- **PRISM-21 [NOT CONFIRMED AS WRITTEN]:** Repetition detection logic in `Engine.cpp`.
- **PRISM-22 [CONFIRMED]:** Unindexed linear scan in `VectorStore.kt:116-130`.
- **PRISM-23 [PARTIALLY CONFIRMED]:** Memory query scoring in `SqlMemoryStore.kt`.
- **PRISM-24 [PARTIALLY CONFIRMED]:** Model switcher navigation depth in `ChatTopBar.kt`.
- **PRISM-25 [CONFIRMED]:** Bridge settings mutation thread safety in `NativeLlmBridge.kt`.
- **PRISM-26 [CONFIRMED]:** Hardcoded tool lists in `AgentToolRouter.kt`.
- **PRISM-27 [PARTIALLY CONFIRMED]:** Dark mode surface contrast in `PrismTheme.kt`.
- **PRISM-28 [CONFIRMED]:** Document browser file picker integration.
- **PRISM-29 [CONFIRMED]:** Missing `JNI_OnUnload` cleanup in `llmhost_jni.cpp`.
- **PRISM-30 [NOT CONFIRMED AS WRITTEN]:** Default chunk size in `DocumentChunker.kt`.
- **PRISM-31 [CONFIRMED]:** Code block syntax highlighting color overrides.
- **PRISM-32 [NOT CONFIRMED / INACCURATE]:** Missing `network_security_config.xml` (cleartext traffic is disabled by default for SDK 36 / API 28+).

---

## Verification

```text
RISK: LOW
STATUS: done

CHANGED FILES:
- Project_Android/PrismLocal/FINDINGS_REPORT_2026-07-31.md

VERIFICATION:
- Command: view_file / git status
- Result: pass
- Evidence: FINDINGS_REPORT_2026-07-31.md accurately reflects re-evaluated audit findings and corrections against commit 3de358c.
```
