# Prism Local — Code Review & Remediation Report

**Date:** 2026-09-13
**Base commit:** `590c6c2` (`main`)
**Review branch:** `fix/swarm-review-2026-09-13`
**Scope:** first-party code only — `app/src/main/java/com/prismai/llmhost/**`,
`app/src/main/cpp/{Engine.cpp,Engine.hpp,llmhost_jni.cpp,CMakeLists.txt}`,
`app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`.
Vendored `app/src/main/cpp/third_party/**` (llama.cpp / Vulkan) excluded.
**Method:** multi-agent review swarm (4 parallel read-only reviewers for
correctness/security/reliability/contracts) plus a 4-agent simplification search
(reuse/quality/efficiency/clarity), then a fix team of subagents, one workstream
per finding cluster. Findings were verified against source before fixing.

> **Verification status:** this campaign was executed on a Linux host **without a
> JDK, Gradle, or Android SDK**, so nothing here was compiled or unit-tested.
> All changes are statically verified only. CI (`.github/workflows/android-ci.yml`)
> is the authoritative gate and must be green before merge.

---

## 1. Remediation summary

44 files changed (+512 / −857), 1 new test. Grouped by area.

### 1.1 Correctness and reliability

| # | Finding | Fix | Files |
|---|---------|-----|-------|
| 1 | Thread-sweep benchmark queued 4 presets but only ever ran the first; `InferenceService.benchmarkQueue` was never populated | Added `onBenchmarkComplete` hook fired from the benchmark terminal path, wired to `BenchmarkRunner.runNextQueued()`; removed the dead queue and derived status from `benchmarkRunner.queue` | `benchmark/BenchmarkRunner.kt`*, `generation/GenerationOrchestrator.kt`, `service/InferenceService.kt` |
| 2 | Streaming rewrote `chat_index.json` and re-sorted all sessions every ~75 ms | `ChatManager.updateTranscriptMessage` no longer touches/persists the index during streaming; index persists on append/rename/delete | `chat/ChatManager.kt` |
| 3 | Model import emitted progress per 8 KB read, each wrapped in `runBlocking { setDownloadProgress }` (~500k commits for 4 GB) | `ModelStorageManager.copyStream` throttles to ~500 ms with one guaranteed final 100 % emission | `storage/ModelStorageManager.kt`, `HuggingFaceDownloadWorker.kt` |
| 4 | RAG ingestion/query ran native embedding + SQLite on the main thread | `NativeLlmBridge.encode` runs on `Dispatchers.Default`; `RagManager` ingest/query on `Dispatchers.IO`; `ChatScreen` launches on IO | `bridge/NativeLlmBridge.kt`, `storage/RagManager.kt`, `ui/ChatScreen.kt` |
| 5 | Oversized chunks were silently dropped during ingestion while reporting success | Added `RagManager.IngestResult` (stored/failed/partial); `RagTools.ingestDocument` reports partial failure | `storage/RagManager.kt`, `agent/tools/RagTools.kt` |
| 6 | `deleteModel` accepted unvalidated `model_id` and deleted `File(modelsDir, modelId)` recursively (path traversal) | Canonical `isInside(modelsDir, …)` containment check before any recursive delete; fails closed | `storage/ModelStorageManager.kt` |
| 7 | `BackgroundAgentManager.shutdown()` was never called; and when called it cancelled its own scope before cleanup ran | Call added in `InferenceService.onDestroy()`; cleanup extracted to `performStopCleanup()`, scope cancelled only after cleanup completes; idempotent, non-blocking | `BackgroundAgentManager.kt`, `service/InferenceService.kt` |

\* `BenchmarkRunner.kt` needed no change; the hook and wiring live in the
orchestrator/service.

### 1.2 Security and privacy

| # | Finding | Fix | Files |
|---|---------|-----|-------|
| 8 | `web_search` was `risk = SAFE` (auto-executed) alongside SAFE private-data readers — injection-driven exfiltration with no gate | Raised to `CONFIRM`; added to `shouldContinueAfterTool` so confirmed searches still feed the model | `tools/AgentTools.kt`, `agent/AgentToolRouter.kt` |
| 9 | Capability policy failed open: unmapped tools were granted | Mapped all 63 registered tools; `check()` now fails closed for unmapped names; removed stale entries; added `ToolCapabilityMappingTest` | `ToolCapabilityMapping.kt`, `tools/AgentTools.kt`, `app/src/test/.../ToolCapabilityMappingTest.kt` |
| 10 | Attachment prompt interpolated the filename unescaped and did not neutralize the body — `</untrusted_external_content>` breakout / `[INST]` injection | Both name and body routed through `ToolInputSanitizer.sanitizeExternalInput` before wrapping | `AttachmentTextExtractor.kt` |
| 11 | `delete_model` accepted any id string | Validates against the installed-model list; unknown ids rejected | `agent/tools/ModelTools.kt` |
| 12 | AndroidKeyStore key spec did not allow caller-provided IVs → `encrypt()` throws on device | Added reflective `setRandomizedEncryptionRequired(false)` | `cloud/auth/KeystoreCrypto.kt` |
| 13 | `getSecurityLevel()` reflection used the wrong parameter type (`SecretKey` vs `Key`) → always `UNKNOWN` | Use `java.security.Key` | `cloud/auth/KeystoreCrypto.kt` |
| 14 | `TokenStorage.clearSession()`/`saveSession()` used non-durable `apply()`; tokens could survive sign-out | Use `commit()` | `cloud/auth/TokenStorage.kt` |
| 15 | Expired session with no refresh token left the cloud backend selected with a null token | Sign out and fall back to `LOCAL_LLAMA` on unrecoverable expiry | `chat/ChatBackendManager.kt` |
| 16 | Supabase signup with email confirmation returns no `access_token` → generic error | New `SupabaseAuthOutcome` + `EmailConfirmationRequiredException`; user-only response handled without throwing | `cloud/auth/SupabaseAuthSession.kt`, `cloud/auth/SupabaseAuthClient.kt` |
| 17 | Auth/Prismatix HTTP clients followed redirects while carrying credentials/JWTs | `instanceFollowRedirects = false`; 3xx treated as error | `cloud/auth/SupabaseAuthClient.kt`, `cloud/prismatix/PrismatixClient.kt` |
| 18 | Prismatix connection leaked when the request-body write threw (`isConnected` gate) | `disconnect()` now runs unconditionally in `finally` | `cloud/prismatix/PrismatixClient.kt` |
| 19 | Prismatix inline SSE loop diverged from the unit-tested `PrismatixSseParser` (which was dead) | `streamChat` delegates to the shared parser (made `inline` to allow `emit` in the callback) | `cloud/prismatix/PrismatixClient.kt`, `cloud/prismatix/PrismatixSseParser.kt` |
| 20 | User/agent content written to Logcat (RAG query, agent prompt/results, TTS text, search queries, UI messages) | Content logs gated behind `BuildConfig.DEBUG` or reduced to metadata | `storage/RagManager.kt`, `tools/GrokipediaClient.kt`, `KnowledgePackManager.kt`, `tools/VoiceIoManager.kt`, `BackgroundAgentManager.kt`, `MainActivity.kt` |

### 1.3 Platform / contracts

| # | Finding | Fix | Files |
|---|---------|-----|-------|
| 21 | `POST_NOTIFICATIONS` declared but never requested at runtime → notifications silently dropped on API 33+ | Runtime request via `ActivityResultContracts.RequestPermission()` | `MainActivity.kt` |

### 1.4 Dead code, clarity, docs

| # | Finding | Fix | Files |
|---|---------|-----|-------|
| 22 | Unused parallel engine abstraction (`InferenceEngine`, `LlamaCppEngineAdapter`, `MediaPipeNpuEngineAdapter`, `GrammarCompiler`) | Deleted (kept live `EngineConfigStore`) | `engine/*.kt` (4 deleted) |
| 23 | Dead `ModelDownloadService` duplicating `HuggingFaceDownloadWorker` | Deleted + manifest entry removed | `service/ModelDownloadService.kt`, `AndroidManifest.xml` |
| 24 | Dead Room persistence (`storage/db/*`) + dependencies + ProGuard keeps | Deleted; Room deps and keep rules removed | `storage/db/*` (5 deleted), `app/build.gradle.kts`, `app/proguard-rules.pro` |
| 25 | `Engine.cpp` `cached_sampler` named/commented as a cache but freed every generation | Renamed `owned_sampler`, corrected comment | `app/src/main/cpp/Engine.cpp` |
| 26 | Dead JNI `NativeDrainResult` package fallback | Removed | `app/src/main/cpp/llmhost_jni.cpp` |
| 27 | Docs drift: `com.example.llmhost`, phantom `llm_host.cpp`, C++17 vs C++20, stale line count | Corrected against source | `PROJECT_CONTEXT.md`, `CLAUDE.md`, `AGENTS.md` |

---

## 2. Known issues intentionally **not** fixed

These need a build/device turn, a product decision, or a larger, riskier refactor.

| # | Issue | Why deferred |
|---|-------|--------------|
| D1 | Hugging Face integrity can be skipped: `expectedSha256 ?: return` with only 3/25 catalog entries pinning a hash, and dynamic imports pass `null` | Requiring a SHA would break legitimate dynamic downloads; needs a product decision (pin or reject). |
| D2 | `VectorStore.search` loads every row (text + embedding BLOB) into heap under a global lock, no `LIMIT`/pagination | Correct fix is a query/pagination redesign; needs profiling + build. |
| D3 | `InferenceService.onDestroy` runs `runBlocking { … engine.destroySafely() }` on the main thread | Moving native teardown off-main with a bounded wait is risky without a device. |
| D4 | Dev validator permits cleartext `localhost`/`127.0.0.1`/`10.0.2.2` but no network-security-config exists and targetSdk 36 blocks cleartext | Needs a debug-only `network_security_config`; no build to verify. |
| D5 | Unbounded `readText()` on remote metadata/search responses | Low impact; cap or stream-parse in a follow-up. |
| D6 | `WorkspaceTools` root is the whole `filesDir`, so SAFE reads can reach chats/traces/audit log | Changing the root would orphan existing data; needs migration + tests. |
| D7 | Broad reuse refactors (path-containment helpers ×6, SHA-256/hex, atomic writes, byte formatters, tool registries) | High churn across many files; must be validated by the build. |
| D8 | Babel public-export policy hits: Windows host paths in docs, `.supabase.co` in pinned hosts | Documentation/policy decision; cross-repo consistency. |

---

## 3. Cross-repo comparison

- **GitHub:** PR #1 (`feat(cloud): AndroidKeyStore token encryption and Cloud Chat
  substrate`) is **merged**; `origin/feat/cloud-chat-keystore-auth` is
  content-identical to `main`. No unfixed code hidden there. The merged
  `fix(security): correct KeyProperties constants` is accurate (verified against
  AOSP) and was not reopened.
- **Babel (agent harness):** applied the `06_Task_Overlays/AI-Android-Development-v1.md`
  review contract, `SECURITY.md`, `tools/security/policy.json`, and
  `skills/android-testing-strategy`. This surfaced the additional issues in §2
  (D1, D2, D3, D4, D5, D8) and confirmed lifecycle-aware state collection and
  test routing are correct.

---

## 4. Verification

- Static review only on this host (no JDK/Gradle/Android SDK).
- Required gate before merge:
  - `./gradlew :app:testDevDebugUnitTest :app:testPlayDebugUnitTest --no-daemon`
  - `./gradlew :app:assembleDevBenchmark :app:assemblePlayRelease --no-daemon`
  - or `./scripts/verify.ps1` on Windows.
- Highest-risk-to-compile changes: the `inline` `PrismatixSseParser.parseStream`
  + `emit` pattern, the `withContext` wrappers in `NativeLlmBridge`/`RagManager`,
  and the reflective Keystore calls.
