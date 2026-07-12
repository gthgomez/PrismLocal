# Prism Local — Capability Roadmap

> **Context**: If cloud AI disappeared tomorrow, what would a local-only assistant need to replace the utility people get from ChatGPT/Claude/Gemini? This roadmap prioritizes features by **(daily utility) × (implementation feasibility) × (local-only advantage)**.

**Status**: ~85% of P0-P2 restrictions unlock complete (see `handoffs/handoff-20260711-100959.md`). P3 (vision/multimodal, GPU offloading) deferred.

---

## Tier 1 — Transformational (high impact, feasible on-device)

### 1. Persistent Memory & Identity 🟢 IN PROGRESS

The assistant remembers facts about the user across sessions. Not just chat history — extracted, queryable, decaying knowledge.

**Files**: `MemoryModels.kt`, `MemoryStore.kt`, `MemoryExtractor.kt`, `MemoryRetriever.kt`, `MemoryTools.kt`, `ChatScreen.kt` (UI)

**Architecture**:
- After each conversation, run extraction prompt → store 3-5 durable facts
- On new conversation, inject top-K relevant memories into system prompt
- Keyword-overlap relevance scoring (v1); embedding-based retrieval (v2, needs embedding model)
- Decay: memories not accessed in 30 days are marked decayed; decayed memories not retrieved
- Merge: similar facts are consolidated
- User-facing memory browser: view, search, delete individual memories

**Dependencies**: None — pure Kotlin + SQLite
**Effort**: Medium | **Impact**: Critical

---

### 2. Local RAG / Document Grounding 🔴 PLANNED

Index the user's local files (PDF, markdown, text, EPUB) into an on-device vector store. Retrieved chunks prepended to prompt.

**Why #2**: A 3B local model *with access to your documents* outperforms a 70B model *without context*.

**Components needed**:
- On-device embedding via llama.cpp `llama_encode` or dedicated small embedder GGUF
- SQLite + brute-force cosine vector index (viable for <100K chunks)
- File import flow (Android SAF / content URI)
- Recursive character chunking with paragraph-boundary respect
- Prompt template: system context + retrieved chunks + user query

**Dependencies**: Embedding model support in JNI bridge (requires C++ changes)
**Effort**: Large | **Impact**: Critical

---

### 3. Offline Knowledge Pack 🔴 PLANNED

Pre-indexed, downloadable knowledge base — Wikipedia abstracts, common documentation sets. Shared RAG pipeline with document grounding.

**Components**:
- Pre-processed packs: Wikipedia simplified (~2GB), Android SDK docs, Python/JS references
- Same vector store as document RAG — shared infrastructure
- Download management (like model downloads: curated, verified, user-gated)
- Query routing: factual question → knowledge pack; personal question → user documents

**Dependencies**: RAG infrastructure (item 2)
**Effort**: Medium (once RAG exists) | **Impact**: High

---

## Tier 2 — Practical Utility

### 4. Voice I/O Pipeline 🔴 PLANNED

Speech-to-text input, text-to-speech output. Fully offline.

**Components**:
- STT: Android `SpeechRecognizer` on-device mode (API 31+) as pragmatic v1; Whisper.cpp GGUF as v2
- TTS: Android system TTS (already offline-capable)
- Streaming recognition for real-time display
- Wake word: "Hey Prism" (optional, Porcupine or similar)

**Dependencies**: Android system APIs (v1); Whisper.cpp model (v2, needs CMake changes)
**Effort**: Medium | **Impact**: High

---

### 5. Structured Data Connectors (Read-Only) 🔴 PLANNED

Read-only access to contacts, calendar, SMS, notifications — with strict privacy boundaries.

**Components**:
- Android Content Provider queries (ContactsContract, CalendarContract, Telephony.Sms)
- Privacy architecture: never store sensitive data in transcripts, CONFIRM-gate all access
- Tool definitions: `search_contacts`, `get_calendar_events`, `list_sms_threads`

**Risk**: HIGH — dangerous permissions, privacy-critical. Requires thorough security review.
**Effort**: Medium | **Impact**: High

---

### 6. Background Agent Execution 🟡 DEFERRED

Agent continues working after app switch / screen lock. Results as notification.

**Components**:
- InferenceService already runs as foreground service — partial foundation exists
- Wake lock management for screen-off inference
- Thermal/battery budget enforcement
- Notification channel for completed tasks
- Task queue: user stacks "do this while I'm away" items
- Pause/resume UX

**Dependencies**: Thermal management improvements
**Effort**: Medium | **Impact**: Medium-High

---

## Tier 3 — Differentiators

### 7. On-Device Personalization (LoRA) 🔴 PLANNED

Model adapts to user's writing style and vocabulary via LoRA adapters.

**Dependencies**: llama.cpp LoRA support maturity (emerging)
**Effort**: Large | **Impact**: High (long-term)

### 8. Local Automation Engine 🔴 PLANNED

IFTTT-style rules: "When X happens, do Y." Model-in-the-loop for decision-making.

**Dependencies**: Background agent execution (item 6)
**Effort**: Large | **Impact**: Medium

### 9. P2P Model/Knowledge Sharing 🔴 PLANNED

WiFi Direct transfer of models and knowledge packs between devices.

**Effort**: Large | **Impact**: Medium (niche)

---

## Tier 4 — Foundation Gaps

### 10. Structured Capability System 🔴 PLANNED

Replace `restrictedReason()` keyword matching with a proper capability-based security model.
Tools declare required capabilities; framework enforces; model cannot self-escalate.

**Current state**: `restrictedReason()` in `AgentTools.kt` uses keyword blocklists — trivially bypassable.
**Effort**: Medium | **Impact**: Critical for security

### 11. Content-Type-Aware Rendering 🟡 DEFERRED

Code blocks get syntax highlighting + copy button. Tables render as actual tables. Lists as cards.

**Effort**: Small | **Impact**: Medium (UX polish)

---

## Pending Decisions

| ID | Decision | Recommendation | Status |
|----|----------|---------------|--------|
| D1 | `web_search` risk: SAFE vs CONFIRM? | SAFE + first-use notification | **Resolved** — see `InferenceService.kt` first-use toast |
| D2 | `util_pct` vs `util_before_pct` dedup? | Dedup — `util_pct * 100.0f` inline | **Resolved** — `Engine.cpp` updated |

---

## Implementation Order

```
Phase 1 (current): Memory system ─── persistent identity
Phase 2:           RAG foundation ─── embedding + vector store + chunking
Phase 3:           Knowledge pack ─── Wikipedia bundle
Phase 4:           Voice I/O      ─── STT + TTS
Phase 5:           Data connectors ─── contacts/calendar/SMS
Phase 6:           Safety         ─── capability system
Phase 7:           Polish         ─── background execution, content rendering, LoRA
```

---

## Verification Gates Per Phase

| Phase | Build | Unit Tests | Integration Test |
|-------|-------|-----------|-----------------|
| 1 | `assembleDebug` | `testDebugUnitTest` | Memory CRUD smoke test |
| 2 | `assembleDebug` + native | `testDebugUnitTest` | Embed vector → retrieve → verify relevance |
| 3 | `assembleDebug` | `testDebugUnitTest` | Download pack → query → verify results |
| 4 | `assembleDebug` | `testDebugUnitTest` | STT input → model response → TTS output |
| 5 | `assembleDebug` | `testDebugUnitTest` | Query contacts → verify CONFIRM gate → verify result |
| 6 | `assembleDebug` | `testDebugUnitTest` | Attempt blocked tool → verify rejection → verify audit log |
| 7 | `assembleDebug` | `testDebugUnitTest` | Manual smoke test per item |

---

## Completed: InferenceService Architecture Refactor

InferenceService was refactored from a 5,391-line monolith into a 1,143-line orchestrator.
All code extracted into focused, testable modules under `agent/`, `benchmark/`, `chat/`, `engine/`,
`export/`, `generation/`, `model/`, and `ui/`.

**Phases completed**:
- **Phase A**: Model layer — `ModelManager`, `ModelStorageManager`, `ModelReadinessAssessor`, `DeviceProfiler`
- **Phase B**: Chat/export layer — `ChatManager`, `ChatExporter`, `TranscriptStore`, `ChatSearchIndex`
- **Phase C**: Benchmark layer — `BenchmarkStore`, `BenchmarkRunner`
- **Phase D**: Agent layer — `AgentToolRouter` + 11 tool-handler classes
- **Phase E**: Generation layer — `GenerationOrchestrator`, `PromptBuilder`, `GenerationMetrics`
- **Cleanup**: ~1,770 lines of dead code removed; formatting utilities centralized in `util/FormatUtils.kt`

**Result**: `assembleDebug` + `assembleRelease` + `testDebugUnitTest` all pass.
InferenceService reduced from 5,391 → **1,143 lines** (~79% reduction).

---
*Last updated: 2026-07-11 — Refactoring complete*
