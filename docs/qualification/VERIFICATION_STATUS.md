# Verification status

Current as of the reliability sprint. Every claim below is backed by a command
that was actually run, or is explicitly marked as unverified.

> **Verification provenance.** The rows below describe jobs in the Android CI
> workflow and are sourced from CI run `37477970025` and the sprint plan. None of
> those commands were re-executed while writing this document: Gradle and native
> builds were deferred to the central gate for the sprint. The `native-host-tests`
> and sanitizer rows were failing or newly added at the time of writing and sit
> under "Pending CI confirmation", not "Proven by CI". Treat a row as current
> evidence only after re-running its command.

## Proven by CI

| Check | How | Notes |
| ----- | --- | ---- |
| JVM unit tests | `:app:testDevDebugUnitTest`, `:app:testPlayDebugUnitTest` | Runs on every push |
| Release-like native builds | `assembleDevBenchmark`, `assemblePlayRelease` | Compiles the RelWithDebInfo native config and R8 rules that debug never touches |

## Pending CI confirmation

These checks are instrumented or newly added but had no green CI run when this
document was written. They move under "Proven by CI" only once a run confirms
them.

| Check | How | Notes |
| ----- | --- | ---- |
| Native host tests | `ctest` in `native-host-tests` | Covers header-only runtime helpers plus `engine_mock_test`, which runs real `Engine.cpp` on a mock model. This was the failing job; the `scripts/instrument_native_build.sh` change is pending a CI run. |
| ASan/UBSan on the Engine target | `engine_mock_test` under `PRISM_ENABLE_SANITIZERS=ON` | Added by the reliability sprint. Not yet confirmed by a green run. |

## Compiled but NOT executed

- **Android instrumentation.** `assembleDevDebugAndroidTest` compiles
  `app/src/androidTest`, including `RealInferenceSmokeTest` and the new
  `storage/VectorStoreRevisionMigrationTest`. No emulator or device runs them in
  CI.
- **Vector-store v1→v2 migration and revision-filtered reads.**
  `VectorStoreRevisionMigrationTest` seeds a pre-revision database at
  `user_version = 1`, opens it through `VectorStore` to exercise the
  `ALTER TABLE … embedding_revision` upgrade, and asserts `search` /
  `getCurrentChunks` exclude the stale revision-1 row while `getAllChunks`
  still returns it. It is compiled by `assembleDevDebugAndroidTest` but has not
  been executed: Robolectric is not on the unit-test classpath, so the real
  SQLite behavior is only verified on a device/emulator.
- **Real-model smoke fixtures are not in the tracked tree.** The tiny GGUF the
  smoke test requires is not committed, so `RealInferenceSmokeTest` cannot pass
  in CI even after its terminal assertion was corrected. The strict `MAX_TOKENS`
  assertion is sound: the test asserts `tokenCount >= 1` before the terminal
  assertion, so the `maxTokens = 1` budget is consumed and the engine reports
  `MAX_TOKENS`. A budget-limited run therefore cannot emit `EOF`, and
  `MAX_TOKENS` is guaranteed; the always-true invariants (a terminal chunk with
  a known reason) remain the durable part of that fix.

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

## Review-fix follow-up (this branch)

This branch is a review-fix follow-up on top of the merged wave-1 integration.
Its changes — off-main chat pre-flight, saveable per-chat drafts, RAG stale
chunk removal, voice recognizer lifecycle handling, and AI-report failure
logging — are **pending CI and device verification**, not proven by this
document. They are covered by JVM unit tests where the logic is testable and by
compilation elsewhere, but no CI run or qualified-device pass has confirmed
them yet. Treat the claims above as the baseline this branch builds on, not as
evidence for these changes.

## Release gate

The authoritative gate is the user journey in
`docs/superpowers/specs/2026-10-06-reliability-sprint-design.md`, run on a
qualified APK on physical hardware. CI is a necessary precondition, not a
substitute.
