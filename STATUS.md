# PrismLocal Status

**Last verified:** 2026-08-01
**Status:** active development
**Confidence:** high

## Purpose

Android host application for local GGUF inference using a JNI C++ bridge to llama.cpp, providing local RAG, memory extraction, voice I/O, background agent execution, and tool dispatch.

## Current State

The app features a complete C++/NDK JNI bridge to `llama.cpp`. Native memory limits, background agent execution with thermal/battery safeguards, and vector-store RAG are implemented. Active development focuses on Bonsai-27B integration planning.

## Verified Capabilities

- Local GGUF LLM inference via JNI C++ `llama.cpp` bindings.
- Persistent memory extraction and SQLite vector store cosine RAG indexing.
- Voice I/O integration via Android SpeechRecognizer and TextToSpeech.
- Background agent execution (`BackgroundAgentManager.kt`) with thermal (pause on SEVERE+) and battery limits (pause <15%).
- SHA-256 manifest verification for Hugging Face GGUF model downloads.

## Recent Evidence

- `FINDINGS_REPORT_2026-07-31.md` documents JNI memory bounds, model switch hashing fixes, and bounds-checked memory indexing audits.
- `ROADMAP.md` confirms Tier 1 (Memory, RAG, Knowledge Pack) and Tier 2 (Voice, Connectors, Background) completed.

## In Progress

- **Bonsai-27B Q1_0 GGUF integration plan** (`docs/BONSAI_27B_INTEGRATION_PLAN.md`).

## Blockers

- Real inference validation requires physical device / emulator logcat verification per security invariants.

## Risks and Unknowns

- Thermal throttling and OOM allocation limits when running 27B model quantization targets on mid-tier mobile NPU/GPUs.

## Verification

- `.\scripts\verify.ps1` (unit tests + `assembleDevBenchmark` + `assemblePlayRelease`) verified; CI runs the same set in the `native-builds` job.

## Next Actions

1. Execute Bonsai-27B integration plan Phase 0 spike tests.
2. Verify JNI mutex cancellation and MemoryGovernor allocation under RAM pressure.
3. Run `.\gradlew.bat --no-daemon :app:testDebugUnitTest`.

## Evidence Sources

- [README.md](file:///C:/Workspace/Project_Android/PrismLocal/README.md)
- [QA_CHECKLIST.md](file:///C:/Workspace/Project_Android/PrismLocal/QA_CHECKLIST.md)
- [ROADMAP.md](file:///C:/Workspace/Project_Android/PrismLocal/ROADMAP.md)
- [FINDINGS_REPORT_2026-07-31.md](file:///C:/Workspace/Project_Android/PrismLocal/FINDINGS_REPORT_2026-07-31.md)
