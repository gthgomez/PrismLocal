# PrismLocal Status

**Last verified:** 2026-10-09
**Status:** active development
**Confidence:** high

## Purpose

Android host application for local GGUF inference using a JNI C++ bridge to llama.cpp, providing local RAG, memory extraction, voice I/O, background agent execution, and tool dispatch.

## Current State

The app features a complete C++/NDK JNI bridge to `llama.cpp`. Native memory limits, background agent execution with task durability and thermal/battery safeguards, and direct-SQLite vector-store RAG are implemented. The P0/P1 correctness and reliability merge train (handle leases, lossless streaming, fail-closed prompt admission, truthful agent terminal outcomes, and serialized model deletion) has merged into `main` (PR #10).

The reliability-closure train merged on 2026-10-09 (#25–#29), followed by the complete bug findings and agent remediation train (#34–#37):
- **#34 (Sequence A - RAG & Vector Store)**: PL-F11 (document chunking boundary), PL-F10 (knowledge pack status), PL-F08 (v2→v3 obsolete row cleanup), PL-F18 (complete pack wipe across curated and on-demand documents), PL-F12 (monotonic encoder epoch validation).
- **#35 (Sequence B - Chat Admission & Draft Durability)**: PL-F01 (admit prompts before cancelling active generation), PL-F02 (truthful direct-tool failure outcome), PL-F03 (in-flight send token and draft revision ownership), PL-F04 (attachment picker bound to originating chat), PL-F07 (bounded saved-state bundles with app-private payload storage).
- **#36 (Sequence C - Task Durability & Search Consistency)**: PL-F05 (atomic background task persistence with `.bak` rollback), PL-F06 (search index eviction on chat clear/delete, ordered chat index snapshots, safe promoteTempFile rollback), PL-Q02 (deterministic durability barrier in `BackgroundAgentPersistenceTest`), PL-Q03 (`ChatSearchAndDeletionConsistencyTest`).
- **#37 (Sequence D - Model Imports, Downloads & Provenance)**: PL-F09 / PL-F17 (sequential import queue retry on busy, preserve fatal `Throwable`, release URI map), PL-F13 (`ResumableDownloadEngine` range validation, 200 reset, ETag mutation detection, storage reserve and size ceiling), PL-F14 (custom entry ID SHA-256 derivation with legacy fallback), PL-F15 (verification cache threat model & boundary test), PL-F16 (`UNKNOWN_LEGACY` provenance preservation).

`main` is at `6745922`; all JVM unit tests pass in both flavors, native CTest suite 10/10 passes, and Android test instrumentation compiles cleanly.

## Verified Capabilities

- Local GGUF LLM inference via JNI C++ `llama.cpp` bindings.
- Persistent memory extraction and SQLite vector store cosine RAG indexing with encoder epoch validation.
- Voice I/O integration via Android SpeechRecognizer and TextToSpeech.
- Durable background agent execution (`BackgroundAgentManager.kt`) surviving process termination with atomic writes, rollback recovery, and thermal/battery limits.
- SHA-256 manifest verification and resumable downloads with Content-Range validation, ETag mutation safeguards, and storage headroom guarantees.
- Monotonic `NativeHandleRegistry` lifetime leases across all 23 JNI entry points.
- Lossless bridge streaming with bounded-sufficient backpressure.
- Fail-closed prompt admission without cancelling active generations on refusal.
- Truthful agent terminal semantics and continuation trace accounting.
- Serialized model deletion and atomic import dispatch with retry on busy.

## Recent Evidence

- PR #34, #35, #36, #37 merged with full test passing: JVM unit tests (`testDevDebugUnitTest`, `testPlayDebugUnitTest`), native host tests (10/10 CTest), and Android instrumentation compilation (`assembleDevDebugAndroidTest`).
- `FINDINGS_REPORT_2026-07-31.md` documents JNI memory bounds, model switch hashing fixes, and bounds-checked memory indexing audits.
- `ROADMAP.md` confirms capability roadmap status and P0 merge train tracking.
- `docs/qualification/VERIFICATION_STATUS.md` records comprehensive qualification ledger.

## In Progress

- Physical-device qualification campaign (`QUAL` gate per `docs/evidence/QUAL_RUNBOOK.md`).
- Bonsai-27B 1-bit Q1_0 on-device test and memory budget validation.

## Blockers

- **PL-Q01 (DEVICE_BLOCKED)**: Real inference validation requires physical device / emulator logcat verification per security invariants.
- No automated test executes `llama_decode` in embeddings mode: the native host mock returns before any real encode. Real embedding output and generation remain unverified without a physical device (issue #21 remains open as the release qualification gate).

## Risks and Unknowns

- Thermal throttling and OOM allocation limits when running 27B model quantization targets on mid-tier mobile NPU/GPUs.

## Verification

- `.\scripts\verify.ps1` (unit tests + `assembleDevBenchmark` + `assemblePlayRelease`) verified; CI runs the same set in the `native-builds` job.
- `./gradlew testDevDebugUnitTest testPlayDebugUnitTest` clean pass on `main`.
- `ctest --test-dir build/prism-native-tests --output-on-failure` 10/10 clean pass.

## Next Actions

0. Issue #21 remains OPEN as the device qualification gate (`PL-Q01`, `DEVICE_BLOCKED`). Issues #14–#20, #30, #31, #33 remediated in PR #34–#37.
1. Execute physical-device qualification campaign (`QUAL`) on real hardware per `docs/evidence/QUAL_RUNBOOK.md`.
2. Execute Bonsai-27B Q1_0 device spike per `docs/BONSAI_27B_INTEGRATION_PLAN.md`.

## Evidence Sources

- [README.md](./README.md)
- [QA_CHECKLIST.md](./QA_CHECKLIST.md)
- [ROADMAP.md](./ROADMAP.md)
- [FINDINGS_REPORT_2026-07-31.md](./FINDINGS_REPORT_2026-07-31.md)
