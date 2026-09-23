# PrismLocal Status

**Last verified:** 2026-09-23
**Status:** active development
**Confidence:** high

## Purpose

Android host application for local GGUF inference using a JNI C++ bridge to llama.cpp, providing local RAG, memory extraction, voice I/O, background agent execution, and tool dispatch.

## Current State

The app features a complete C++/NDK JNI bridge to `llama.cpp`. Native memory limits, background agent execution with thermal/battery safeguards, and direct-SQLite vector-store RAG are implemented. Active development focuses on the P0 correctness and reliability merge train (handle leases, lossless streaming, fail-closed prompt admission).

## Verified Capabilities

- Local GGUF LLM inference via JNI C++ `llama.cpp` bindings.
- Persistent memory extraction and SQLite vector store cosine RAG indexing.
- Voice I/O integration via Android SpeechRecognizer and TextToSpeech.
- Background agent execution (`BackgroundAgentManager.kt`) with thermal (pause on SEVERE+) and battery limits (pause <15%).
- SHA-256 manifest verification for Hugging Face GGUF model downloads.

## Recent Evidence

- `FINDINGS_REPORT_2026-07-31.md` documents JNI memory bounds, model switch hashing fixes, and bounds-checked memory indexing audits.
- `ROADMAP.md` confirms capability roadmap status and P0 merge train tracking.

## In Progress

- **P0 Correctness Merge Train** (NativeHandleRegistry, lossless stream buffering, fail-closed prompt admission, agent terminal truth, serialized model deletion).

## Blockers

- Real inference validation requires physical device / emulator logcat verification per security invariants.

## Risks and Unknowns

- Thermal throttling and OOM allocation limits when running 27B model quantization targets on mid-tier mobile NPU/GPUs.

## Verification

- `.\scripts\verify.ps1` (unit tests + `assembleDevBenchmark` + `assemblePlayRelease`) verified; CI runs the same set in the `native-builds` job.

## Next Actions

1. Complete P0 correctness merge train (P0A–P1B).
2. Execute physical-device qualification campaign (`QUAL`).
3. Run `.\scripts\verify.ps1` and `.\gradlew.bat --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest`.

## Evidence Sources

- [README.md](./README.md)
- [QA_CHECKLIST.md](./QA_CHECKLIST.md)
- [ROADMAP.md](./ROADMAP.md)
- [FINDINGS_REPORT_2026-07-31.md](./FINDINGS_REPORT_2026-07-31.md)
