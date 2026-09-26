# PrismLocal reliability and qualification status — 2026-09-26

This is a current status record, not a broad qualification claim. The initial
fixtures are defined in `docs/inference/qualification-fixtures-v1.md`.

## Evidence observed

- **OBSERVED — current candidate:** PR #13 is open at code candidate
  `6296ef8460d39c28145f3a41257e7203ab836f85`. It contains native lifecycle
  gating, transactional batch acknowledgement, carrier version 2, trace
  minimization, transcript write invalidation, model storage lifecycle
  coordination, background-task persistence minimization, and the fix for a
  duplicate Kotlin companion declaration. A further #16 fix binds confirmation
  dispatch admission to agent-mode state and adds a test for a capability that
  remains granted after disablement. The final code commit `3e1db88` corrects
  a test fixture so the stale pending cancellation case actually stages an
  agent confirmation. On this exact candidate, both duplicated Dev/Play JVM
  jobs and both sanitized host CTest jobs passed. The DevDebug instrumentation
  APK compiled, and unsigned DevBenchmark/PlayRelease assembly succeeded.
  Documentation head `2e0050b1ae880fa92db4c3255a2a9a7934940bee` is that record
  only; its pull-request CI also passed JVM, host CTest, instrumentation
  compile, and unsigned benchmark/release assembly. Reviews of `c1f81fc` and
  `be11cee` identified successive #20 scheduling races; the current candidate
  adds retry-on-deferral and follow-up-chain ownership. A fresh read-only
  review of code candidate `6296ef8` is recorded below. It is not an approval,
  and merge remains on hold.
- **VERIFIED — host contract:** standalone native host CMake build completed and
  CTest passed 9/9, including lifecycle-gate serialization and tail-checked
  acknowledgement validation. These tests compile portable production headers;
  they do not compile `Engine.cpp` or exercise Android JNI.
- **VERIFIED — local JVM checks:** with the already-installed SDK at
  `C:\Users\icbag\AppData\Local\Android\Sdk` and its valid NDK 28.2, the
  focused DevDebug tests for `BackgroundAgentManagerTest`,
  `BackgroundGenerationOwnershipTest`, `GenerationResultStoreTest`, and
  `GenerationSessionWaitTest` passed. The configured `D:\Dev\AndroidLab\Sdk`
  NDK lacks `source.properties`; no dependency was installed.
- **OBSERVED — connected devices:** `adb devices -l` returned no attached
  physical devices. `emulator -list-avds` listed `Lab_Phone_API36` and
  `Lab_TV_API36`. A read-only `Lab_Phone_API36` boot using its existing system
  image remained ADB-offline for more than three minutes; the launched emulator
  was stopped, and no instrumentation ran.
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
  refinement is included in candidate `020f887`; both Dev/Play test suites
  passed there.
- **OBSERVED — model storage change:** a process-wide gate serializes import
  promotion and deletion. Download requests are tagged by model owner, deletion
  cancels matching work, and a captured revision rejects stale promotion. New
  Android instrumentation covers real import/delete barriers and stale download
  promotion. The instrumentation APK compiled on `020f887`; it has not been
  connected to a device.
- **VERIFIED — background-task privacy:** queued/running prompts remain stored
  for restart recovery. Completed records omit prompts and cap saved result
  summaries at 120 characters. A regression assertion checks this file format;
  the JVM suites passed on `020f887`.
- **OBSERVED — authorization review/fix:** a fresh review of `eace9bf` found
  that disabling agent mode could race between confirmation validation and
  dispatch for capabilities that remain granted. The new snapshot records that
  agent mode is required, mode changes advance the policy revision, and
  settings updates revoke admission before publishing the disabled state.
  The regression test now stages and consumes a `rename_current_chat`
  confirmation, disables agent mode while `CHAT_MANAGE` remains granted, and
  verifies dispatch admission is denied. Exact-candidate unit tests passed.
- **OBSERVED — remaining code gaps:** #15 now captures stream output by
  generation ID and agent-chain ID, with active waiters retaining results; reset
  ordering and production interleavings still need independent review. #20 now
  persists source chat ownership, blocks conflicting follow-ups, defers UI chat
  switching until the task releases the shared engine, and requeues a task when
  user work wins admission. Service admission, source-chat restore, deferred
  switch, and follow-up ownership are covered by `ServiceGenerationOwnershipTest`.
  `delete_model` confirmation now records version, SHA-256, and path, and the
  storage gate refuses a different installed identity. Queued work continues
  across chat switches and app backgrounding unless explicitly cancelled;
  source-chat deletion invalidates queued work and is rejected while its task
  is active. Issues #14–#21 remain open. Connected `InferenceService`
  execution is still outstanding.
- **RECORDED — independent review of `6296ef8`:** a read-only review of
  `4d458ea0f42b7736a87443f1cfd3a302992f5d98..6296ef8460d39c28145f3a41257e7203ab836f85`
  found 4 bugs and 1 suggestion. New Chat and an asynchronous native reset
  could still seize or cancel an in-flight generation. A deferred background
  task could retry in a tight loop and drop a queued chat switch. A
  session-wait timeout could release engine ownership without cancelling the
  generation. A failed native decode could be acknowledged before its error
  terminal was delivered. Chat-trace deletion also removes unowned or
  unreadable artifacts. The first three ownership defects are addressed in the
  follow-up on this branch. The native decode acknowledgement bug and the
  trace-deletion suggestion remain open. This review does not approve the
  candidate. No GitHub review was posted, because the follow-up changes the
  lines those findings cite.
- **DOCUMENTED — earlier emulator work:**
  `docs/evidence/REAL_INFERENCE_EVIDENCE_2026-05-05.md` records a prior emulator
  smoke run and its historical model hash. It is not evidence for the current
  app/native candidate or this qualification campaign.

## Qualification gaps

- **VERIFIED — prior exact candidate CI:** on `020f88782466ab7262d3925c14fcc908b20a8363`,
  both host CTest jobs and both Dev/Play JVM jobs passed. The DevDebug Android
  instrumentation APK compiled successfully. The unsigned benchmark/release
  build was still running at the last observation. No connected instrumentation
  run is recorded.
- **VERIFIED — current code candidate CI:** on
  `6296ef8460d39c28145f3a41257e7203ab836f85`, pull-request run
  [36227927044](https://github.com/gthgomez/PrismLocal/actions/runs/36227927044)
  completed successfully on 2026-09-26. Host CTest passed. The single
  "Unit Tests & Golden Conformance" job passed its Dev and Play debug step
  at 07:50:39Z. DevDebug Android instrumentation tests compiled at 07:59:30Z.
  Unsigned `assembleDevBenchmark` and `assemblePlayRelease` succeeded at
  08:12:20Z. The matching push run 36227924908 also succeeded. No connected
  instrumentation run is recorded.
- **VERIFIED — documentation head CI:** on
  `2e0050b1ae880fa92db4c3255a2a9a7934940bee`, pull-request run
  [36228603444](https://github.com/gthgomez/PrismLocal/actions/runs/36228603444)
  completed successfully on 2026-09-26. Host CTest passed 9/9 at 08:03:05Z.
  The Dev and Play debug unit-test step passed at 08:04:42Z. DevDebug Android
  instrumentation tests compiled at 08:13:35Z. Unsigned DevBenchmark and
  PlayRelease assembly succeeded at 08:26:52Z. The matching push run
  36228601251 also succeeded. This head changes only the qualification record
  and does not alter the code candidate. No connected instrumentation run is
  recorded.
- **BLOCKED:** connected emulator execution of the new JNI schema, slow-consumer,
  cancellation/reset and stale-generation instrumentation. The AVD remained
  ADB-offline; the physical device is unavailable. The tracked smoke-model asset
  is also missing. Local JVM tests used the separate installed SDK/NDK override.
- **BLOCKED:** physical-device qualification. Next action: attach a supported
  physical device and validate the model fixture's provenance before staging
  it; then run `connectedDevDebugAndroidTest` and the small-model load →
  generate → cancel → reset → generate smoke while recording the exact
  app/native SHA, model SHA-256/quantization, Android build, settings, TTFT,
  throughput, peak memory, terminal reason, cancellation latency and thermal
  state.
- **NOT QUALIFIED:** semantic quality, larger models, broad device support,
  performance and endurance. Host/JVM/contract checks do not establish these.
