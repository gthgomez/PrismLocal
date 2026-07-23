# Prism Local — Capability Roadmap

> **Context**: If cloud AI disappeared tomorrow, what would a local-only assistant need to replace the utility people get from ChatGPT/Claude/Gemini? This roadmap prioritizes features by **(daily utility) × (implementation feasibility) × (local-only advantage)**.

**Last Updated**: 2026-07-19  
**Status**: Core capability roadmap (Phases 1-7), architectural refactoring, and JNI performance/safety upgrades have been successfully completed.

### Active implementation plan — Bonsai-27B

**Canonical plan (implement this):** [`docs/BONSAI_27B_INTEGRATION_PLAN.md`](./docs/BONSAI_27B_INTEGRATION_PLAN.md)

Spike-gated Q1_0 GGUF integration for PrismML Bonsai (not BitNet/IQ1). Start Phase 0 only; catalog is last. Supersedes informal Antigravity drafts under the same topic.

---

## Tier 1 — Transformational (high impact, feasible on-device)

### 1. Persistent Memory & Identity 🟢 COMPLETED
The assistant remembers facts about the user across sessions. Not just chat history — extracted, queryable, decaying knowledge.
**Files**: `MemoryModels.kt`, `MemoryStore.kt`, `MemoryExtractor.kt`, `MemoryRetriever.kt`, `MemoryTools.kt`
**Completed**: Phase 1 memory integration (SQLite store, decay algorithm, and memory browser UI).

---

### 2. Local RAG / Document Grounding 🟢 COMPLETED
Index the user's local files (PDF, markdown, text) into an on-device vector store. Retrieved chunks prepended to prompt.
**Files**: `VectorStore.kt`, `DocumentChunker.kt`, `RagManager.kt`, `RagTools.kt`
**Completed**: Cosine similarity index in Room/SQLite, text chunking, and llama.cpp embedding extraction.

---

### 3. Offline Knowledge Pack 🟢 COMPLETED
Pre-indexed, downloadable knowledge base — Wikipedia abstracts, common documentation sets.
**Files**: `KnowledgePackManager.kt`, `GrokipediaClient.kt`, `KnowledgePackToolDefinitions.kt`
**Completed**: Downloader pipeline and HTML parser indexing Grokipedia articles into local vector store.

---

## Tier 2 — Practical Utility

### 4. Voice I/O Pipeline 🟢 COMPLETED
Speech-to-text input, text-to-speech output. Fully offline.
**Files**: `VoiceIoManager.kt`, `VoiceToolDefinitions.kt`
**Completed**: On-device Android SpeechRecognizer and system TextToSpeech integration.

---

### 5. Structured Data Connectors (Read-Only) 🟢 COMPLETED
Read-only access to contacts, calendar, SMS, notifications — with strict privacy boundaries.
**Files**: `DataConnectorTools.kt`, `ConnectorToolDefinitions.kt`
**Completed**: Android Content Provider bridges for contacts, calendar, and SMS threads with confirmation gates.

---

### 6. Background Agent Execution 🟢 COMPLETED
Agent continues working after app switch / screen lock. Results as notification.
**Files**: `BackgroundAgentManager.kt`, `BackgroundAgentToolDefinitions.kt`
**Completed**: PARTIAL_WAKE_LOCK foreground service runner with battery (pause <15%) and thermal (pause SEVERE+) guards.

---

## Tier 3 — Future / Deferred Capabilities

### 7. On-Device Personalization (LoRA) 🔴 PLANNED
Model adapts to user's writing style and vocabulary via LoRA adapters.
**Dependencies**: llama.cpp LoRA support maturity
**Status**: Deferred to subsequent roadmap

### 8. Local Automation Engine 🔴 PLANNED
IFTTT-style rules: "When X happens, do Y." Model-in-the-loop for decision-making.
**Dependencies**: Background agent execution (item 6)
**Status**: Deferred

### 9. P2P Model/Knowledge Sharing 🔴 PLANNED
WiFi Direct transfer of models and knowledge packs between devices.
**Status**: Deferred

---

## Tier 4 — Foundation Gaps & Maintenance

### 10. Structured Capability System 🟢 COMPLETED
Security model replacing keyword blocklists. Tools declare required capabilities, enforced by the framework.
**Files**: `CapabilityRegistry.kt`, `ToolCapabilityMapping.kt`
**Completed**: Phase 6 safety registry and tool validation constraints.

### 11. Content-Type-Aware Rendering 🟢 COMPLETED
Syntax highlighting + copy button for code blocks. Tables render as actual tables.
**Files**: `CodeBlockRendering.kt`, `TableRendering.kt`
**Completed**: Phase 7 markdown visual components using Compose.

### 12. Modularize UI Monolith 🟢 COMPLETED
Split the 4,194-line [ChatScreen.kt](file:///C:/Workspace/Project_Android/PrismLocal/app/src/main/java/com/example/llmhost/ChatScreen.kt) screen file into focused composable classes.
**Completed**: Reduced ChatScreen.kt to 691 lines (-83.5% reduction) across 11 subpackages under `ui/`.

---

## Pending Decisions

| ID | Decision | Recommendation | Status |
|----|----------|---------------|--------|
| D1 | `web_search` risk: SAFE vs CONFIRM? | SAFE + first-use notification | **Resolved** — see `InferenceService.kt` first-use toast |
| D2 | `util_pct` vs `util_before_pct` dedup? | Dedup — `util_pct * 100.0f` inline | **Resolved** — `Engine.cpp` updated |

---

## Implementation Order & Completed Milestones

### Milestone 1: Memory & Base Capabilities (Phases 1–5) — 🟢 COMPLETED
All local storage (Memory, Document RAG), offline STT/TTS, and data connectors compiled and tested.

### Milestone 2: Monolith Refactor & De-duplication — 🟢 COMPLETED
InferenceService was refactored from a 5,391-line monolith into a 1,044-line orchestrator. All code extracted into focused, testable packages under `agent/`, `benchmark/`, `chat/`, `engine/`, `export/`, `generation/`, `model/`, `ui/`, and `util/`.

### Milestone 3: JNI Performance & Safety Upgrades — 🟢 COMPLETED (2026-07-19)
- Reduced-allocation JNI fast path via pre-allocated carrier buffers in `NativeDrainResult`.
- Eliminated JNI heap allocations for the empty/fast path.
- Non-blocking intra-batch cancellation checks in `decodeTokensAt` reducing cancel latency to ~100ms.
- Throw JVM `NoSuchFieldError` on cached class field mismatch instead of silent early loops.
- `jniTimingsUs` primitive `LongArray` replacing boxed list allocations.
- Pre-allocated C++ vector capacity (`decode_times_us.reserve`).

### Milestone 4: Modularize UI Components — 🟢 COMPLETED (2026-07-19)
Decomposed `ChatScreen.kt` (4,194 lines -> 691 lines) into 11 modular components across `ui/theme`, `ui/components`, `ui/composer`, `ui/memory`, `ui/chat`, `ui/benchmark`, and `ui/controlplane`. Verified with `assembleDebug` and `testDebugUnitTest`.

---
*Last updated: 2026-07-19 — Capability phases, JNI optimizations, and UI modularization complete*
