# PrismLocal Status

**Last verified:** 2026-10-09
**Status:** active development
**Confidence:** high

## Purpose

Android host application for local GGUF inference using a JNI C++ bridge to llama.cpp, providing local RAG, memory extraction, voice I/O, background agent execution, and tool dispatch.

## Current State

The app features a complete C++/NDK JNI bridge to `llama.cpp`. Native memory limits, background agent execution with task durability and thermal/battery safeguards, and direct-SQLite vector-store RAG are implemented. The P0/P1 correctness and reliability merge train (handle leases, lossless streaming, fail-closed prompt admission, truthful agent terminal outcomes, and serialized model deletion) has merged into `main` (PR #10).

The reliability-closure train then merged on 2026-10-09: #25 (native encode + model/chat/verification), #26 (encode runtime state, off-main I/O, atomic import/re-ingest, downloads, voice), #27 (trusted download pins + activation verification with a content-fingerprint cache), #28 (RAG encoder identity, failure-atomic re-ingest, bounded UI memory, non-destructive cleanup), and #29 (composer clears only after the send is admitted). `main` at `cf6e8b4`; CI (Unit Tests, Native Host Tests, Native Builds) green on each merged head and merge commit. No open PRs remain from that train.

## Verified Capabilities

- Local GGUF LLM inference via JNI C++ `llama.cpp` bindings.
- Persistent memory extraction and SQLite vector store cosine RAG indexing.
- Voice I/O integration via Android SpeechRecognizer and TextToSpeech.
- Durable background agent execution (`BackgroundAgentManager.kt`) surviving process termination with thermal (pause on SEVERE+) and battery limits (pause <15%).
- SHA-256 manifest verification for Hugging Face GGUF model downloads.
- Monotonic `NativeHandleRegistry` lifetime leases across all 23 JNI entry points.
- Lossless bridge streaming with bounded-sufficient backpressure.
- Fail-closed prompt admission with typed native error codes.
- Truthful agent terminal semantics and continuation trace accounting.
- Serialized model deletion through service operation mutex.

## Recent Evidence

- `FINDINGS_REPORT_2026-07-31.md` documents JNI memory bounds, model switch hashing fixes, and bounds-checked memory indexing audits.
- `ROADMAP.md` confirms capability roadmap status and P0 merge train tracking.
- PR #10 CI run passing all test suites: Unit Tests, Native Builds (DevBenchmark & PlayRelease), and Native Host Tests (CTest).
- `docs/evidence/QUAL_RUNBOOK.md` provides on-device qualification procedures.

## In Progress

- Physical-device qualification campaign (`QUAL` gate per `docs/evidence/QUAL_RUNBOOK.md`).
- Bonsai-27B 1-bit Q1_0 on-device test and memory budget validation.

## Blockers

- Real inference validation requires physical device / emulator logcat verification per security invariants.
- No automated test executes `llama_decode` in embeddings mode: the native host mock returns before any real encode. Real embedding output and generation remain unverified without a device (issue #21, a release-qualification blocker).

## Risks and Unknowns

- Thermal throttling and OOM allocation limits when running 27B model quantization targets on mid-tier mobile NPU/GPUs.

## Verification

- `.\scripts\verify.ps1` (unit tests + `assembleDevBenchmark` + `assemblePlayRelease`) verified; CI runs the same set in the `native-builds` job.

## Next Actions

0. Triage the open correctness/robustness issues filed from the review pass: #30 (persist model verification provenance), #31 (import path: don't swallow fatal `Throwable`; release the import URI map). The native/session issues #14–#20 remain open with their existing scope.
1. Execute physical-device qualification campaign (`QUAL`).
2. Execute Bonsai-27B Q1_0 device spike per `docs/BONSAI_27B_INTEGRATION_PLAN.md`.
3. Run `.\scripts\verify.ps1` and `.\gradlew.bat --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest`.

## Evidence Sources

- [README.md](./README.md)
- [QA_CHECKLIST.md](./QA_CHECKLIST.md)
- [ROADMAP.md](./ROADMAP.md)
- [FINDINGS_REPORT_2026-07-31.md](./FINDINGS_REPORT_2026-07-31.md)
