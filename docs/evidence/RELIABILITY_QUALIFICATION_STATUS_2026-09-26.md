# PrismLocal reliability and qualification status — 2026-09-26

This is a current status record, not a broad qualification claim. The initial
fixtures are defined in `docs/inference/qualification-fixtures-v1.md`.

## Evidence observed

- **OBSERVED — current candidate:** PR #13 is open at
  `020f88782466ab7262d3925c14fcc908b20a8363`. It contains native lifecycle
  gating, transactional batch acknowledgement, carrier version 2, trace
  minimization, transcript write invalidation, model storage lifecycle
  coordination, background-task persistence minimization, and the fix for a
  duplicate Kotlin companion declaration. Exact-candidate Dev/Play JVM and
  sanitized host CTest jobs passed. The DevDebug instrumentation APK compiled;
  benchmark/release native builds are still running. No independent review has
  covered this SHA.
- **VERIFIED — host contract:** standalone native host CMake build completed and
  CTest passed 9/9, including lifecycle-gate serialization and tail-checked
  acknowledgement validation. These tests compile portable production headers;
  they do not compile `Engine.cpp` or exercise Android JNI.
- **OBSERVED — local Android build:** `:app:testDebugUnitTest` cannot configure
  because the configured NDK directory lacks `source.properties`. This is a
  blocked local build, not a test failure or pass.
- **OBSERVED — connected devices:** `adb devices -l` returned no attached
  devices. `emulator -list-avds` listed `Lab_Phone_API36` and `Lab_TV_API36`;
  neither was booted for this run.
- **OBSERVED — fixture availability:** the source references
  `app/src/androidTest/assets/smoke-model/`, but that directory/model asset is
  absent in this checkout. A 6.75 MB TinyStories model exists in the original
  workspace validation tree, but it was not copied into this public branch or
  run; its publication provenance/licensing was not revalidated here.
- **UNKNOWN — physical device:** no load/generate/cancel/reset/regenerate smoke
  run, real-device latency, memory, thermal, or endurance result is available.
- **VERIFIED — prior candidate checks:** `5aa0734` passed both Dev/Play JVM
  suites and host CTest. Its native benchmark/release build was still pending
  when inspected; it does not contain the later model-storage or trace-schema
  changes.
- **VERIFIED — transcript persistence change:** `ChatManager` now captures a
  per-chat revision before asynchronous scheduling. Clear invalidates older
  snapshots; deletion also retires the chat ID and serializes file removal
  against active publications. Deterministic production-gate barrier tests
  passed in both unit variants on candidate `2b9015c`; atomic-publication
  refinement is included in the current candidate; both Dev/Play test suites
  pass on `020f887`.
- **OBSERVED — model storage change:** a process-wide gate serializes import
  promotion and deletion. Download requests are tagged by model owner, deletion
  cancels matching work, and a captured revision rejects stale promotion. New
  Android instrumentation covers real import/delete barriers and stale download
  promotion. The instrumentation APK compiled on `020f887`; it has not been
  connected to a device.
- **VERIFIED — background-task privacy:** queued/running prompts remain stored
  for restart recovery. Completed records omit prompts and cap saved result
  summaries at 120 characters. A regression assertion checks this file format;
  the JVM suites pass on `020f887`.
- **OBSERVED — remaining code gaps:** reset/session wait ownership (#15),
  version/hash-bound delete confirmation (#17), and source-chat execution and
  persistence for queued background tasks (#20) remain incomplete. The product
  decision is that queued work continues through chat switches and app
  backgrounding unless explicitly cancelled; deleting its source chat must
  invalidate it. Current `BackgroundTask` records do not retain a source chat
  ID and service execution still resolves the active chat. Issues #14–#21
  remain open.
- **DOCUMENTED — earlier emulator work:**
  `docs/evidence/REAL_INFERENCE_EVIDENCE_2026-05-05.md` records a prior emulator
  smoke run and its historical model hash. It is not evidence for the current
  app/native candidate or this qualification campaign.

## Qualification gaps

- **OBSERVED — exact candidate CI:** on `020f88782466ab7262d3925c14fcc908b20a8363`,
  both host CTest jobs and both Dev/Play JVM jobs passed. The DevDebug Android
  instrumentation APK compiled successfully. The unsigned benchmark/release
  native build remains in progress. No connected instrumentation run is
  recorded.
- **BLOCKED:** connected emulator execution of the new JNI schema, slow-consumer,
  cancellation/reset and stale-generation instrumentation. The current host has
  no attached ADB device and the local Gradle configuration stops at the
  incomplete NDK installation. The tracked smoke-model asset is also missing.
- **BLOCKED:** physical-device qualification. Next action: provision the pinned
  Android NDK from an already approved local source, validate the model fixture's
  provenance before staging it, attach a supported physical device, then run
  `connectedDebugAndroidTest` and the small-model load → generate → cancel →
  reset → generate smoke while recording the exact
  app/native SHA, model SHA-256/quantization, Android build, settings, TTFT,
  throughput, peak memory, terminal reason, cancellation latency and thermal
  state.
- **NOT QUALIFIED:** semantic quality, larger models, broad device support,
  performance and endurance. Host/JVM/contract checks do not establish these.
