# Verification status

Current as of the reliability sprint. Every claim below is backed by a command
that was actually run, or is explicitly marked as unverified.

> **Verification provenance.** The rows under "Proven by CI" describe jobs in the
> Android CI workflow and are sourced from CI run `37477970025` and the sprint
> plan. None of those commands were re-executed while writing this document:
> Gradle and native builds were deferred to the central gate for the sprint.
> The `native-host-tests` and sanitizer rows were failing or newly added at the
> time of writing — the instrumentation fix in `scripts/instrument_native_build.sh`
> is **pending a CI run** to confirm. Treat a row as current evidence only after
> re-running its command.

## Proven by CI

| Check | How | Notes |
| ----- | --- | ---- |
| JVM unit tests | `:app:testDevDebugUnitTest`, `:app:testPlayDebugUnitTest` | Runs on every push |
| Release-like native builds | `assembleDevBenchmark`, `assemblePlayRelease` | Compiles the RelWithDebInfo native config and R8 rules that debug never touches |
| Native host tests | `ctest` in `native-host-tests` | Covers header-only runtime helpers plus `engine_mock_test`, which runs real `Engine.cpp` on a mock model. Pending the Task 1 CI run: this job was the failing one at the time of writing. |
| ASan/UBSan on the Engine target | `engine_mock_test` under `PRISM_ENABLE_SANITIZERS=ON` | Added by the reliability sprint. Pending CI confirmation. |

## Compiled but NOT executed

- **Android instrumentation.** `assembleDevDebugAndroidTest` compiles
  `app/src/androidTest`, including `RealInferenceSmokeTest`. No emulator or
  device runs them in CI.
- **Real-model smoke fixtures are not in the tracked tree.** The tiny GGUF the
  smoke test requires is not committed, so `RealInferenceSmokeTest` cannot pass
  in CI even after its terminal assertion was corrected.

## Not proven anywhere in CI

- **Real decoding.** `engine_mock_test` executes the real `Engine.cpp` token
  ring, backpressure, cancellation and lifecycle logic, but the mock path
  performs no llama decode. No CI job runs llama.cpp against a real model.
- **Embedding output.** After the decoder-path change, no test asserts that a
  real model produces a correct embedding vector.
- **Device behavior.** Keyboard insets, rotation, chat switching, and memory
  recovery on real hardware are unverified. See the release gate in the sprint
  spec.
- **Memory-pressure recovery on device.** The reconciler's JVM tests cover the
  state machine; whether native actually stops cancelling after recovery needs
  hardware under real memory pressure.

## Known gaps, deliberately not closed here

- Sanitized `Engine.cpp` builds are expensive. If CI cannot afford them at the
  default runner size, they should move to a larger runner rather than be
  dropped.
- The real-model smoke fixtures remain unsupplied. Adding them requires
  committing a model binary or fetching it in CI, which has licensing and
  reproducibility implications that are out of scope for a reliability sprint.

## Release gate

The authoritative gate is the user journey in
`docs/superpowers/specs/2026-10-06-reliability-sprint-design.md`, run on a
qualified APK on physical hardware. CI is a necessary precondition, not a
substitute.
