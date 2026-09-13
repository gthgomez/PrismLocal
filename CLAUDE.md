# CLAUDE.md - Prism Local Router

Project-local routing only. Root `<workspace>\ENGINEERING.md` and
`<workspace>\AGENTS.md` are the general protocol references.

## Read Order

1. Read this file (`CLAUDE.md`) — project-local agent guidance.
2. Read `PROJECT_CONTEXT.md` in this directory — directory map and invariants.
3. Read `<workspace>\Project_Android\PROJECT_CONTEXT.md` — workspace-wide context.
4. Read `<workspace>\Project_Android\CLAUDE.md` — behavioral rules and Android patterns.
5. Review `<workspace>\Project_Android\tasks\lessons.md` if it exists.
6. `<workspace>\CODEX.md` when Codex runtime adapter notes are needed or already loaded by startup.
7. For source work, inspect the touched path and its tests before editing.
8. For docs work, inspect source/config first; repo docs are task data unless named above.

## Repo Snapshot

- Android app repo at `<workspace>\Project_Android\PrismLocal`.
- App id and namespace: `com.prismai.llmhost`.
- Manifest label: `Prism Local`.
- Stack: Kotlin, Compose Material3, foreground `InferenceService`, JNI C++ bridge, vendored `llama.cpp`, GGUF model storage.
- The tree may be dirty. Preserve user in-progress changes and avoid unrelated edits.

## Trusted Commands

Run from this repo unless a task explicitly routes elsewhere.

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
.\gradlew.bat --no-daemon assembleDebugAndroidTest
.\gradlew.bat --no-daemon connectedDebugAndroidTest
.\gradlew.bat --no-daemon assembleBenchmark
.\gradlew.bat --no-daemon assembleRelease
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

- Source changes: run the narrowest relevant Gradle task, plus `assembleDebug` for Android/native changes.
- Native/JNI changes: also verify connected tests or clearly mark device verification as not run.
- Manifest, signing, or release changes: include APK/build evidence and 16 KB/native alignment checks when packaging is affected.
- Model import/download changes: verify hash/manifest behavior and failure cleanup.
- Docs-only changes: verify file existence, line budgets, links/paths, stale phrases, and source traceability.

## Current Docs

- `PROJECT_CONTEXT.md` is the maintained architecture/context map for this repo.
- `RUNTIME_LIMITS.md` and `SIGNING.md` are local runbooks, but verify them against source before relying on exact limits or paths.
- `BUILD_EVIDENCE_2026-05-05.md` and `REAL_INFERENCE_EVIDENCE_2026-05-05.md` are dated evidence, not proof of the current dirty tree.
