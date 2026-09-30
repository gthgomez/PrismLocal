# CLAUDE.md - Prism Local Router

@AGENTS.md

Project-local facts and commands supplement the imported agent-neutral router.
Managed workspace guidance may supplement this file when explicitly loaded; it is
not a required dependency of a standalone clone.

## Read Order

1. Follow repository-root `AGENTS.md`; skip it if already loaded.
2. Read local `PROJECT_CONTEXT.md` and the touched source/tests.
3. Load parent workspace or runtime adapters only when present and relevant.
   Missing optional parent guidance does not block project-local work.
4. Read relevant established lessons when available. Otherwise capture a reusable
   correction in the current task/PR handoff; do not invent parent paths.

## Repo Snapshot

- Android app repo at `<workspace>\Project_Android\PrismLocal`.
- App id and namespace: `com.prismai.llmhost`.
- Manifest label: `Prism Local`.
- Stack: Kotlin, Compose Material3, foreground `InferenceService`, JNI C++ bridge, vendored `llama.cpp`, GGUF model storage.
- The tree may be dirty. Preserve user in-progress changes and avoid unrelated edits.

## Trusted Commands

Run from this repo unless a task explicitly routes elsewhere.

```powershell
.\gradlew.bat --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDevDebug
.\gradlew.bat --no-daemon :app:assembleDevDebugAndroidTest
.\gradlew.bat --no-daemon :app:connectedDevDebugAndroidTest
# Full canonical verification gate:
.\scripts\verify.ps1
.\gradlew.bat --no-daemon :app:assembleDevBenchmark
.\gradlew.bat --no-daemon :app:assemblePlayRelease
```

Notes:
- Native builds compile/link `llama.cpp` and can be slow.
- `connectedDebugAndroidTest` needs an attached emulator/device and local smoke-model assets.
- `assembleRelease` is unsigned unless external signing properties/env vars are supplied.
- For docs-only edits, prefer line/path/stale-phrase checks over heavy Gradle runs.

## High-Risk Zones

- `app/build.gradle.kts`, root Gradle files, wrapper files, NDK/CMake settings.
- `app/src/main/AndroidManifest.xml`, permissions, foreground-service declarations.
- `app/src/main/cpp/CMakeLists.txt`, `Engine.cpp`, `Engine.hpp`, `llmhost_jni.cpp`.
- `app/src/main/cpp/third_party/llama.cpp/**` and `.gitmodules`.
- `NativeLlmBridge.kt`, `InferenceService.kt`, cancellation, memory pressure, stream state.
- `ModelStorageManager.kt`, model import, GGUF validation, manifests, SHA-256 checks.
- `HuggingFaceDownloadWorker.kt`, network downloads, resume, checksum validation.
- `AgentTools.kt`, confirmation gates, destructive chat actions, network/model actions.
- Signing surfaces: `SIGNING.md`, release build config, keystore env/property names.
- Machine/local artifacts: `local.properties`, `release/`, `validation/`, `*.jks`, `*.keystore`, `*.apk`, `*.aab`, `*.gguf`.

## Local Invariants

- Do not add `kotlin.android` to Gradle plugins; parent Android policy forbids the AGP 9.x double-declaration.
- Do not document or commit signing credential values, keystores, model binaries, or local SDK paths.
- `llm_host.cpp` no longer exists in the tree; current CMake sources are `llmhost_jni.cpp` and `Engine.cpp`.
- Real inference claims require current build/device evidence, not just dated evidence docs.
- Network/model/tool actions exposed through `AgentTools.kt` require the app's confirmation path unless source proves otherwise.

## Done Criteria

- Source changes: run the narrowest relevant Gradle task, plus `assembleDevDebug` or `.\scripts\verify.ps1` for Android/native changes.
- Native/JNI changes: also verify connected tests or clearly mark device verification as not run.
- Manifest, signing, or release changes: include APK/build evidence and 16 KB/native alignment checks when packaging is affected.
- Model import/download changes: verify hash/manifest behavior and failure cleanup.
- Docs-only changes: verify file existence, line budgets, links/paths, stale phrases, and source traceability.

## Current Docs

- `PROJECT_CONTEXT.md` is the maintained architecture/context map for this repo.
- `RUNTIME_LIMITS.md` and `SIGNING.md` are local runbooks, but verify them against source before relying on exact limits or paths.
- `BUILD_EVIDENCE_2026-05-05.md` and `REAL_INFERENCE_EVIDENCE_2026-05-05.md` are dated evidence, not proof of the current dirty tree.
