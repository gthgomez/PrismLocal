# PrismLocal reliability and qualification status — 2026-09-26

This is a current status record, not a broad qualification claim. The initial
fixtures are defined in `docs/inference/qualification-fixtures-v1.md`.

## Evidence observed

- **OBSERVED — app/native candidate:** local candidate commit is
  `ab7b91eafe8b190cb9380b4f35b640284b8d8fb2`. It contains the native
  lifecycle gate, transactional batch acknowledgement, carrier version 2,
  trace minimization, and updated tests. CI/review status applies only after
  this exact commit is pushed; later commits require fresh checks/review.
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
- **DOCUMENTED — earlier emulator work:**
  `docs/evidence/REAL_INFERENCE_EVIDENCE_2026-05-05.md` records a prior emulator
  smoke run and its historical model hash. It is not evidence for the current
  app/native candidate or this qualification campaign.

## Qualification gaps

- **BLOCKED:** connected emulator execution of the new JNI schema, slow-consumer,
  cancellation/reset and stale-generation instrumentation. The current host has
  no attached ADB device and the Gradle configuration stops at the incomplete
  NDK installation. The tracked smoke-model asset is also missing.
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
