# PROJECT_CONTEXT.md - LLMHostAndroid

## What This Is

`LLMHostAndroid` (PrismLocal) is an Android app for local GGUF inference. The current
manifest presents the app as `Prism Local`, with package/application id
`com.prismai.llmhost`. It is a Kotlin/Compose app with a foreground inference
service, app-owned model storage, and a JNI bridge to a C++ `llama.cpp` engine.

This document is source-backed routing context for agents. It does not replace
root workspace policy, source code, current build output, or fresh device logs.

## Build And Platform

- Root project name: `LlmHostFromDocs`; included module: `:app`.
- Gradle wrapper: Gradle `9.4.1`.
- Android Gradle Plugin: `com.android.application` `9.2.0`.
- Kotlin Compose plugin: `2.2.10`; Compose BOM `2026.04.01`.
- Java/Kotlin target: JVM 17.
- Android SDKs: min 26, compile 36, target 36.
- NDK: `28.2.13676358`; CMake version requested by Gradle: `3.22.1`.
- Native ABIs in `defaultConfig`: `arm64-v8a`, `x86_64`.
- Build types observed: `debug`, `release`, `benchmark`, `profile`, `adreno`.
- `debug` enables `LLMHOST_DEBUG_HOOKS`; release-like builds disable it.

Do not add `kotlin.android` to the plugin block. Parent Android policy records
that AGP 9.x owns this path and double-declaration can break the build.

## Architecture Map

- `MainActivity.kt` starts and binds `InferenceService`, then renders `ChatScreen`.
- `ChatScreen.kt` is the central Compose UI. It collects service `StateFlow`s for
  model state, chat sessions, transcripts, runtime settings, downloads,
  benchmarks, device capability, and pending agent-tool confirmations.
- `InferenceService.kt` is a thin coordinator/foreground service manager (reduced from 5,391 to 1,044 lines). It initializes and binds the modular subsystems:
  - `com.example.llmhost.generation.GenerationOrchestrator` — Coordinates generation flows, token collection, and metrics.
  - `com.example.llmhost.chat.ChatManager` — Governs transcript files, chat CRUD, and full-text search indexing.
  - `com.example.llmhost.model.ModelManager` / `ModelStorageManager` — Oversees GGUF loading, profiling, and memory assessment.
  - `com.example.llmhost.agent.AgentToolRouter` — Dispatches execution of the 11 modular agent tools.
- `NativeLlmBridge.kt` loads `libllmhost`, serializes JNI calls with a mutex, and
  exposes streaming generation as `Flow<GenerationChunk>` using a pre-allocated carrier buffer.
- `app/src/main/cpp/CMakeLists.txt` builds `libllmhost.so` from
  `llmhost_jni.cpp` and `Engine.cpp`, links static `llama`/`ggml`, disables most
  llama.cpp tools/tests/server outputs, and sets 16 KB page-size linker flags.
- `Engine.cpp` owns the C++ runtime: llama backend init, model/context load,
  context shifting, token ring, cancellation, benchmark path, memory-pressure
  cancellation, and JNI-safe terminal states.
- `Engine.hpp` defines `StreamState` and `GenerationConfig`; Kotlin state values
  are mirrored in `NativeLlmBridge.kt`.
- `ModelStorageManager.kt` imports GGUF files into app-owned model directories,
  validates GGUF headers/metadata when possible, computes SHA-256, writes
  `manifest.json`, and resolves the active model through manifest verification.
- `HuggingFaceModelCatalog.kt` contains curated GGUF entries and expected sizes
  or hashes where known.
- `HuggingFaceDownloadWorker.kt` uses WorkManager for curated downloads, remote
  metadata checks, resumable download, SHA-256 verification, then imports through
  `ModelStorageManager`.
- `MemoryGovernor.kt` polls Android memory pressure and maps it to native levels.
- `AgentTools.kt` defines app-local structured tool requests and risk gates.
  Settings changes, model downloads/switches, chat deletion/clear/export, and
  benchmarks are confirmation-gated in source.
- `AttachmentTextExtractor.kt` extracts bounded text/MHTML snippets for prompts.
  Image attachments are metadata-only; the native bridge is text-only.
- `MarkdownText.kt`, `StreamingTextState.kt`, and `Utf8TextPipeline.kt` handle
  display markdown, throttled stream text, and native UTF-8 cleanup.

`app/src/main/cpp/llm_host.cpp` still exists but is not included by the current
CMake source list. Treat it as inactive unless CMake is changed.

## Data And Storage

- Active model, active chat, and generation settings live in SharedPreferences.
- Chats are app-private JSON files: `chat_index.json` plus `chats/<id>.json`.
- Benchmark history is app-private `benchmark_runs.json`.
- Recovery text can be written to app-private `recovery_transcript.txt`.
- Models live under app-owned external `models/<modelId>/` directories with
  `manifest.json` and versioned GGUF files.
- `.gitignore` excludes local build outputs, signing files, model binaries,
  `validation/`, `release/`, and `app/src/androidTest/assets/smoke-model/`.

## Commands

**Cwd:** always `PrismLocal/` (this repo). The workspace composite root
`Project_Android/` has no `:app` module; agents that run Gradle from the parent
folder will fail with `project 'app' not found`.

Trusted commands from this repo:

```powershell
cd C:\Workspace\Project_Android\PrismLocal
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
.\gradlew.bat --no-daemon assembleDebugAndroidTest
.\gradlew.bat --no-daemon connectedDebugAndroidTest
# Minified variants: build ONE at a time on ~16 GB hosts (parallel R8 OOM'd daemon).
.\gradlew.bat --no-daemon --max-workers=2 assembleBenchmark
.\gradlew.bat --no-daemon --max-workers=2 assembleRelease
# Optional: assembleProfile, assembleAdreno (Adreno needs OpenCL include/lib props)
```

Package IDs by variant: `com.prismai.llmhost` (release), `.debug`, `.benchmark`,
`.profile`, `.adreno`. JNI exports must match `com.prismai.llmhost.bridge.NativeLlmBridge`
(`Java_com_prismai_llmhost_bridge_NativeLlmBridge_*`). ProGuard keeps use the same package.

From composite root only (uses this project’s wrapper + `-p`):

```powershell
.\PrismLocal\gradlew.bat -p PrismLocal --no-daemon :app:assembleDebug
```

Use `connectedDebugAndroidTest` only when an emulator/device is available. The
current checkout has a local smoke GGUF asset under `app/src/androidTest/assets`,
but `.gitignore` excludes that path, so a clean checkout may need asset staging.

Release signing properties (`LLMHOST_RELEASE_*`) live in user-level
`~/.gradle/gradle.properties` or the environment. Store path must point at
`PrismLocal/release/…` after the LLMHostAndroid → PrismLocal rename.

Packaging/release checks, when relevant:

- Android SDK `zipalign -c -P 16 -v 4 <apk>` for 16 KB APK alignment.
- Android SDK `llvm-readelf -W -l <libllmhost.so>` for native LOAD alignment.
- `llvm-nm -D <libllmhost.so>` when verifying JNI exports.

## Tests And Evidence

Unit tests under `app/src/test/java/com/example/llmhost` cover agent-tool
registry validation, attachment extraction, UI text helpers, markdown cleanup,
and UTF-8 normalization.

Instrumentation tests under `app/src/androidTest/java/com/example/llmhost`
cover native engine stress paths, model storage import/resolve/failure cleanup,
real tiny-model smoke generation and cancellation, service model switching,
foreground generation cancellation, transcript persistence, runtime settings
performance publication, and stream-state safety.

Dated evidence docs:

- `BUILD_EVIDENCE_2026-05-05.md` records an earlier debug/androidTest build and
  packaging checks before real inference was fully integrated.
- `REAL_INFERENCE_EVIDENCE_2026-05-05.md` records a GREEN connected test run
  with real tiny GGUF inference, logcat crash scan, APK alignment, JNI exports,
  and release debug-hooks-off checks.
- `REAL_INFERENCE_FIX_PLAN_2026-05-05.md` is a historical plan plus evidence
  board. Use it for context, not as current proof after later dirty-tree changes.
- `app/src/main/cpp/LLAMA_CPP_VERSION.md` records the vendored llama.cpp
  snapshot commit `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`.

Fresh build/test/log evidence is required before claiming the current tree
passes.

## Signing

Release signing is external. `app/build.gradle.kts` reads these names from Gradle
properties or environment variables:

- `LLMHOST_RELEASE_STORE_FILE`
- `LLMHOST_RELEASE_STORE_PASSWORD`
- `LLMHOST_RELEASE_KEY_ALIAS`
- `LLMHOST_RELEASE_KEY_PASSWORD`

Do not document values, keystore paths from a private machine, or credential
contents. `SIGNING.md` names the same inputs, but its example command references
an older artifact path; adapt commands to this repo path before use.

## High-Risk Paths

- Gradle, wrapper, NDK, CMake, build type, ABI, and dependency edits.
- Manifest permission/activity/service/foreground-service changes.
- JNI/native engine lifecycle, stream states, cancellation, memory pressure,
  model mmap fallback, and 16 KB linker flags.
- Model import/download paths, manifest promotion, SHA-256 validation, storage
  cleanup, and Hugging Face network behavior.
- Agent tool validation and confirmation gates.
- Chat transcript deletion/export and benchmark export paths.
- Signing, release outputs, `local.properties`, model binaries, and validation
  artifacts.

## Current Limitations And Gaps

- Build compilation (`assembleDebug`, `assembleBenchmark`, `assembleRelease`) and unit tests (`:app:testDebugUnitTest`) verified 2026-07-24 after JNI/ProGuard package rename fix.
- Do not assemble multiple minified variants in one Gradle invocation on low-RAM hosts.
- `RUNTIME_LIMITS.md` is updated to document the configurable context length (512 to 16,384), prompt batch sizes, max generated tokens (up to 1,024), and agent iterations (up to 12).
- The local JNI layer is performance-hardened with zero heap allocations on the polling fast path, and cancel latency has been optimized via C++ intra-batch cancellation checks in `decodeTokensAt`.
- The local smoke GGUF asset exists in this checkout but is ignored by git. Device tests may need asset setup on another machine.
- `README.md` and `QA_CHECKLIST.md` are present in the repository.
- OpenCL/Adreno support is experimental and depends on external include/library configuration; CPU/KleidiAI paths are the normal documented path.
- Production readiness, Play Store readiness, and current release signing status are unverified here.

## Docs Fitness

A cold-start agent should be able to answer what the app is, where source lives,
which commands are trusted, what paths are risky, which evidence is dated, what
is unverified, and what done means by reading `AGENTS.md` plus this file.

---
*Last updated: 2026-07-19 — Decomposed refactoring & JNI performance updates mapped*
