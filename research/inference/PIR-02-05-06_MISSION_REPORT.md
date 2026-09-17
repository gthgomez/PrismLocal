# PIR-02 / PIR-05 / PIR-06 — Mission Report

- **Branch:** `feat/inference-pir-02-05-06` (PR #8, `OPEN`, `MERGEABLE`)
- **Base `main`:** `e9c3e669e09de17868ad38aa7031dec33df08aab` (`git rev-parse main`, unchanged)
- **Branch head:** `0134f66365db10b7003c1305f0ef1399caad1075` (`git rev-parse HEAD`)
- **Audited roadmap base:** `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` (roadmap provenance)
- **llama.cpp gitlink:** `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`
- **Host toolchain used for the fixtures:** gcc 13.3.0, cmake 3.28.3, OpenJDK 21.0.12
- **Date:** 2026-09-17

> This report is a read-only evidence pass over the committed branch. All commands
> and exit codes below were executed or read during this pass. No device, no
> model load, and no benchmark was run.

## 1. Scope

This branch is an **offline, host-level** slice of the first-wave missions. It does
not close the on-device gates.

**Delivered on this branch**

- **PIR-00:** portable host CTest harness (`app/src/test/cpp/`), evidence contract,
  dry-run provenance capture script, and a frozen (but dirty-tree) baseline manifest.
- **PIR-02 (partial):** lossless incremental UTF-8 streaming in the Kotlin bridge,
  a header-only `StreamProtocol.hpp` transport/terminal contract, a host stress
  fixture, and — since `c4b4f12`/`0134f66` — the terminal-after-full-drain wiring
  (Engine/JNI/bridge `produced`/`drained`/`pending` accounting with a deferred
  `ackEof`) plus a host drain-completion fixture. `StreamSequenceTracker` is **not**
  yet the shared runtime store: the engine enforces the same contract with its own
  atomic `produced_tokens`/`drained_tokens` counters and a `StreamTerminal` mapping.
  Native handle leases / `NativeHandleRegistry` are still **not** delivered.
- **PIR-05 (partial):** header-only `ConversationState.hpp` and transactional
  KV/token/position/cache-identity bookkeeping in `Engine.cpp`, plus host
  context-replay fixtures. Cross-identity reuse is refused; unverified shifting
  is disabled (full reset).
- **PIR-06 (partial):** versioned requested/applied plan and load key in Kotlin,
  persisted `useVulkan`, reload suppression fix, JNI `use_vulkan` wiring, strict
  CPU-only device selection, and nullable (honest) load diagnostics. Native
  applied readback/observed placement and independent PP/TG threading are **not**
  delivered.

**Deliberately not delivered / out of scope**

- PIR-01, PIR-03, PIR-04, PIR-07, PIR-08 (PIR-06 declares dependencies on
  PIR-01/05/07/08; only PIR-05 is on this branch).
- Any device instrumentation run, model load, benchmark JSONL, or accelerator math.

## 2. Provenance and working-tree state

- The committed manifest `research/inference/baseline.lock.json` was generated at
  HEAD `6d007fe` over a **dirty tree** (`porcelain` records
  `HuggingFaceModelCatalog.kt` and `gradlew`). It is a source-derived snapshot,
  **not** a clean pinned baseline.
- The live checkout at HEAD `0134f66` is dirty only with pre-existing unrelated
  edits (`HuggingFaceModelCatalog.kt`, `gradlew`) plus this untracked report; the
  drain-wiring files that were uncommitted earlier have since landed as
  `c4b4f12`/`0134f66`. The manifest still needs a clean re-freeze.
- The runtime headers and host test sources used for the ctest evidence below are
  unchanged vs `HEAD` (`git diff --stat HEAD -- …` empty).
- **Action required:** re-run `scripts/inference/capture-baseline.sh --apply` on a
  clean integration commit before treating the baseline as release provenance.

## 3. Per-mission summary

| Mission | Commit(s) | Key files | What changed | Verification actually run | Disposition |
|---|---|---|---|---|---|
| **PIR-00** | `6d007fe`, `8e85327` | `app/src/test/cpp/{CMakeLists.txt,conversation_state_test.cpp,harness_self_test.cpp}`, `docs/inference/evidence-contract.md`, `scripts/inference/capture-baseline.sh`, `research/inference/baseline.lock.json` | Host-only C++20 CMake/CTest project (no Android/llama linkage), optional ASan/UBSan target; evidence-layer contract; dry-run-by-default provenance script; generated manifest | `ctest --test-dir build/prism-native-tests` → **5/5 pass, exit 0** (2 targets at its own commit; transport/context/drain added later); sanitizer build → **5/5 pass, exit 0**; `capture-baseline.sh` dry run → **exit 0**, emits JSON | **MODIFY** — harness/contract RETAIN; re-freeze baseline on a clean commit |
| **PIR-02** | `f228563`, `0f4ae88`, `558bd3e`, `d518977`, `c4b4f12`, `0134f66` | `bridge/Utf8TextPipeline.kt`, `bridge/NativeLlmBridge.kt`, `bridge/NativeDrainResult.kt`, `bridge/IncrementalUtf8Test.kt`, `runtime/StreamProtocol.hpp`, `Engine.cpp`, `Engine.hpp`, `llmhost_jni.cpp`, `test/cpp/stream/{stream_protocol_test.cpp,drain_completion_test.cpp,README.md}`, `test/cpp/CMakeLists.txt` | Stateful incremental UTF-8 decoder carrying split sequences across drains; trailing bytes delivered inside the terminal chunk; destructive U+FFFD filtering removed; header-only `DrainResult`/`StreamTerminal`/`StreamSequenceTracker`/`SpscRing`; **drain-completion wiring**: ControlBlock `produced_tokens`/`drained_tokens`, `writeToken`/`drainTokens`/`clearRing` accounting, `Engine::DrainResult` `schema_version`/`produced`/`drained`/`pending`, `finishSession`→`StreamTerminal`, `ackEof` defers (`eof_ack_deferred`) unless terminal AND `produced<=drained`, JNI marshal + `NativeDrainResult` fields, bridge emits terminal only when `terminal && !pending` | `stream_protocol_test` → **10000 iterations, 40920 checks, 0 failures, exit 0**; `drain_completion_test` → **2500 iterations, 7713 checks, 0 failures, exit 0**; JVM `IncrementalUtf8Test` (16 tests) → **CI unit tests pass**; `NativeHandleRegistry` leases → **absent/NOT_RUN** | **MODIFY** — streaming contract/decoder + drain/ack wiring RETAIN; `StreamSequenceTracker` still not the shared runtime store (engine uses its own atomic counters); leases + device G03 outstanding |
| **PIR-05** | `58a0bc0`, `0f4ae88`, `d518977` | `runtime/ConversationState.hpp`, `Engine.cpp`, `test/cpp/context/context_replay_test.cpp`, `androidTest/.../ContextReplayTest.kt` | `CacheIdentity` + `ConversationState`; return-check `llama_memory_seq_rm` before mutating tokens/position; invalidate-on-abort; commit only after successful decode; explicit identity (model/template/context/KV/FA); post-reset/shift recompute; reusable clamp to `current_position` | `context_replay_test` → **2016 checks, 0 failures, exit 0** (incl. `repeated_shifts`, 256-turn accounting); device `ContextReplayTest` → **skips via `Assume`, BLOCKED** | **MODIFY** — KV bookkeeping RETAIN; device gate + explicit envelope boundaries outstanding |
| **PIR-06** | `387ed1c`, `558bd3e`, `e7ae5f9`, `88c34e4` | `engine/runtime/InferencePlan.kt`, `engine/EngineConfigStore.kt`, `model/ModelManager.kt`, `ModelLoadDiagnostics.kt`, `GenerationSettings.kt`, `Engine.hpp`, `Engine.cpp`, `llmhost_jni.cpp`, `bridge/NativeLlmBridge.kt`, `EngineConfigStoreTest.kt` | Versioned requested/applied plan + single full `loadKey`; persisted `useVulkan`; reload derives from load key; adapter change forces reload; JNI `nativeLoadModelWithSettings` takes `use_vulkan`; strict CPU device pin when Vulkan disabled; observed diagnostic fields nullable | CI `Unit Tests & Golden Conformance` (dev+play) → **pass** (prior head); CI `Native Builds (benchmark & release)` → **pass** (run `34945883797`/`34945888376`, prior head); `EngineConfigStoreTest` (12 tests) → CI pass; native applied readback → **NOT_RUN** | **MODIFY** — plan/load-key/RETAIN; native readback, independent threads, diagnostics pending |

### Verified ctest detail (host, commit `0134f66`)

| Binary | Observed result |
|---|---|
| `harness_self_test` | `C++20 active: yes`; `ASan: available`; `UBSan: not active` (GCC macro probe); exit 0 |
| `conversation_state_test` | `checks: 67, failures: 0`; exit 0 |
| `stream_protocol_test` | `iterations: 10000, checks: 40920, failures: 0`; exit 0 |
| `drain_completion_test` | `iterations: 2500, checks: 7713, failures: 0`; exit 0 |
| `context_replay_test` | `checks: 2016, failures: 0`; exit 0 |

The sanitizer-enabled build (`build/prism-native-tests-san`, `PRISM_ENABLE_SANITIZERS=ON`)
compiles and links with `-fsanitize=address -fsanitize=undefined`; ctest reports
**5/5 pass, exit 0**. The self-test's `UBSan: not active` line is a macro-probe
false negative on GCC 13.3.0 (GCC does not define `__SANITIZE_UNDEFINED__`), not
evidence that UBSan is absent from the flags.

`drain_completion_test` is a **host contract** test: a `SpscRing<256>` +
`StreamSequenceTracker` mirroring the bridge's 128-token drain batches. It pins
the old "stop on terminal" behaviour as incomplete and asserts the terminal is
acknowledgeable only after the ring is fully drained. The engine itself does not
instantiate `StreamSequenceTracker`; `Engine.cpp` maintains equivalent atomic
`produced_tokens`/`drained_tokens` counters and maps `StreamState` to
`llmhost::StreamTerminal`, so the same contract is enforced in two places.

## 4. Correctness gates (roadmap §24)

Statuses are limited to PASS / FAIL / NOT_RUN / BLOCKED. No gate below is claimed
PASS: the host fixtures validate contracts, and CI proves builds/tests, but none of
them produce the **native/applied-plan evidence** §24 requires.

| Gate | Status | Reason |
|---|---|---|
| G01 provenance/build | **NOT_RUN** | CI release-like builds pass, but the manifest is a dirty-tree snapshot; "clean pinned baseline" unmet. |
| G02 lifetime/operation | **NOT_RUN** | `NativeHandleRegistry`/leases absent and no device; drain/cancel/destroy races unexercised. |
| G03 lossless stream | **NOT_RUN** | Host `stream_protocol_test` + `drain_completion_test` pass, and the engine now enforces the drain/ack contract (`ackEof` defers until `produced<=drained`; bridge withholds the terminal while `pending`), but there is no device/model end-to-end evidence. |
| G04 Unicode | **NOT_RUN** | JVM `IncrementalUtf8Test` (16) passes; no native/device decode or NUL-policy evidence. |
| G05 sampling | **NOT_RUN** | PIR-03 not on this branch; no sampler fixtures. |
| G06 template/budget | **NOT_RUN** | PIR-04 not on this branch (PIR-02 dependency only). |
| G07 prefix/cache | **NOT_RUN** | Host replay/reuse fixtures pass; no model/logit comparison and no device; device test skips. |
| G08 shift/context | **NOT_RUN** | Unverified shifting is disabled to full reset, but no arch/SWA/hybrid/abort/device evidence. |
| G09 load contract | **NOT_RUN** | Requested/applied split and `useVulkan` wiring exist; native applied readback/observed placement and strict-CPU proof missing. |
| G10 memory/validation | **NOT_RUN** | PIR-07 GGUF validation is not on this branch; no corrupt/oversized metadata fixture. |
| G11 backend math | **BLOCKED** | Requires a physical device/backend; no accelerator run possible here. |
| G12 KV quality | **BLOCKED** | Requires model + device; no F16/q8/q4 quality measurement. |
| G13 speculation | **NOT_RUN** | PIR-17 not started; no speculation code on this branch. |
| G14 Android | **NOT_RUN** | PIR-09 not on this branch; FGS/lifecycle/thermal tests absent. |
| G15 auxiliary capabilities | **NOT_RUN** | PIR-16 not on this branch; embeddings/LoRA/vision untouched. |
| G16 benchmark integrity | **NOT_RUN** | PIR-01 record/clock schema is not on this branch; no eligible run records produced. |

## 5. Evidence boundary

- The host fixtures exercise **header-only logic** (`ConversationState.hpp`,
  `StreamProtocol.hpp`) and the Kotlin decode/plan helpers. They do **not** run the
  engine, load a model, or touch a device.
- The `IncrementalUtf8Test`, `EngineConfigStoreTest`, and the release-like Gradle
  builds are evidenced only by **CI** (`gh pr checks 8`, runs `34945883797` and
  `34945888376`, both `pass`, covering the prior head `d518977`). This pass did
  not re-run Gradle, and those checks must be re-confirmed on head `0134f66`.
- No benchmark number, latency, memory, thermal or accelerator claim is made; no
  `research/inference/results/` record exists for this branch.
- Absent device/model/toolchain is **BLOCKED**, never PASS, per the evidence
  contract (`docs/inference/evidence-contract.md` §5).

## 6. Remaining work

1. **Native handle leases:** implement `runtime/NativeHandleRegistry.hpp/.cpp` and
   wrap every JNI path (including pressure/thread/diagnostic/encode) in a lease;
   refuse new calls after `Closing`. This is what closes the G02 lifetime gap.
2. **Engine drain/ack wiring — LANDED (`c4b4f12`).** The terminal-after-full-drain
   audit is now enforced in `Engine.cpp`/JNI/`NativeLlmBridge` via atomic
   `produced_tokens`/`drained_tokens`, a deferred `ackEof` (`eof_ack_deferred`),
   and a bridge terminal gated on `terminal && !pending`. Remaining for G03:
   `StreamSequenceTracker` is still only a host-test object, not the shared runtime
   store — the engine re-implements the same contract with its own atomics — and
   there is no device/model end-to-end run.
3. **Explicit envelope token boundaries:** replace heuristic system-prefix
   detection in `Engine.cpp` with token boundaries emitted by the prompt envelope
   (PIR-05 step 5).
4. **Arch-gated shifting:** certify in-place shifting per model/backend/RoPE/SWA
   family; default unverified architectures to pruning + replay. Currently the
   fail-safe is a full reset, which is safe but unverified for reuse.
5. **Independent PP/TG threads + ubatch:** `ctx_params.n_threads_batch` is set
   equal to `n_threads` and `n_ubatch` equal to `n_batch`; the PIR-06 plan fields
   for independent values are not applied natively.
6. **`RuntimeDiagnostics.hpp/.cpp` and `PlanRoundTripTest.kt`:** the PIR-06
   native-observable diagnostics and the androidTest round-trip are not written,
   so requested-vs-applied-vs-observed is not yet provable natively.
7. **PIR-00 harness completeness:** PIR-00's own commit shipped only
   `conversation_state_test` + `harness_self_test`; the transport and context
   sub-tests arrived with PIR-02/PIR-05 (`0f4ae88`). If the integrator treats
   "transport/cache policy fixtures" as a PIR-00 deliverable, that is only now
   satisfied on this branch and should be recorded as landed by PIR-02/05.
8. **Re-freeze provenance:** regenerate `research/inference/baseline.lock.json` on
   a clean integration commit and confirm G01.
9. **Device gate:** execute `:app:connectedDevDebugAndroidTest` and the ctest
   targets, then the PIR-06 release-like Gradle gates, on an authorized device with
   a real model fixture to move G02–G10/G16 off NOT_RUN/BLOCKED.

## 7. Commands and observed results

```sh
git rev-parse main                 # e9c3e669e09de17868ad38aa7031dec33df08aab (unchanged)
git rev-parse HEAD                 # 0134f66365db10b7003c1305f0ef1399caad1075
git log --oneline main..HEAD       # 12 commits, 0134f66..f228563
git status --porcelain=v1          # HFF catalog + gradlew (pre-existing) + this report
git submodule status --recursive   # bbeb89d… llama.cpp (gguf-v0.18.0-863-gbbeb89d76)

cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests
ctest --test-dir build/prism-native-tests --output-on-failure
#   1/5 harness_self_test ....... Passed
#   2/5 conversation_state_test . Passed
#   3/5 stream_protocol_test ... Passed
#   4/5 drain_completion_test .. Passed
#   5/5 context_replay_test .... Passed
#   100% tests passed, 0 failed out of 5; exit 0

cmake -S app/src/test/cpp -B build/prism-native-tests-san -DCMAKE_BUILD_TYPE=Debug -DPRISM_ENABLE_SANITIZERS=ON
cmake --build build/prism-native-tests-san
ctest --test-dir build/prism-native-tests-san --output-on-failure
#   100% tests passed, 0 failed out of 5; exit 0
#   (CXX_FLAGS include -fsanitize=address -fsanitize=undefined)

./build/prism-native-tests/conversation_state_test   # checks: 67, failures: 0; exit 0
./build/prism-native-tests/stream_protocol_test      # 10000 iterations, 40920 checks, 0 failures; exit 0
./build/prism-native-tests/drain_completion_test     # 2500 iterations, 7713 checks, 0 failures; exit 0
./build/prism-native-tests/context_replay_test       # checks: 2016, failures: 0; exit 0

bash scripts/inference/capture-baseline.sh           # dry run, JSON to stdout; exit 0
```

> CI status for the two new commits was not re-queried in this pass (no Gradle
> run); the earlier PR #8 checks (`34945883797`, `34945888376`) cover the prior
> head only and must be re-confirmed on `0134f66`.

## 8. Disposition summary

| Mission | Disposition |
|---|---|
| PIR-00 | **MODIFY** — harness/contract/script RETAIN; manifest must be re-frozen clean |
| PIR-02 | **MODIFY** — streaming decoder, protocol contract and drain/ack wiring RETAIN; native handle leases + shared tracker/device G03 outstanding |
| PIR-05 | **MODIFY** — transactional cache bookkeeping RETAIN; device gate + envelope boundaries pending |
| PIR-06 | **MODIFY** — plan/load-key and `useVulkan`/strict-CPU wiring RETAIN; native readback, diagnostics, independent threads pending |

No REJECT. No gate is claimed PASS. The on-device gates remain NOT_RUN or BLOCKED
until a device and model fixture are available.
