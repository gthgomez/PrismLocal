# Real Inference Fix Plan - Android LLM Host

## Objective

Move `C:\Workspace\artifacts\llm-host-apk-20260505` from a debug JNI lifecycle harness to a real local GGUF inference APK.

The project is **GREEN only when every required gate below has direct evidence**. Any `FAIL`, `NOT RUN`, missing asset, mock-only path, or inferred claim keeps the status **RED** or **YELLOW**.

## Current Rating

Status: **GREEN - real local GGUF inference smoke path verified**

Completion evidence:

- Final evidence artifact: `REAL_INFERENCE_EVIDENCE_2026-05-05.md`.
- `assembleDebug`, `assembleDebugAndroidTest`, and `assembleRelease` passed after `llama.cpp` integration.
- `connectedDebugAndroidTest` passed 8/8 tests on `TD_Pixel8_API36(AVD) - 16`.
- APK `zipalign -c -P 16 -v 4` passed for debug, androidTest, and release APKs.
- `libllmhost.so` LOAD segments are aligned to `0x4000` for debug/release `arm64-v8a` and `x86_64`.
- Kotlin no longer reads native `controlBuffer` memory directly and no `DirectByteBuffer` polling remains.
- Release `BuildConfig.java` has `LLMHOST_DEBUG_HOOKS=false`.
- Logcat captured real GGUF model load, prompt eval, token emission, decoded text, EOF, cancellation, and no SIGSEGV/SIGBUS/tombstone/JNI abort signatures.

Resolved gaps:

- Pinned `llama.cpp` source recorded in `app/src/main/cpp/LLAMA_CPP_VERSION.md`.
- Native engine now owns real `llama_model*` / `llama_context*` state.
- Tiny GGUF smoke model is present locally and as androidTest asset; SHA-256 recorded.
- Real one-token decode returns token id `647` and decoded text `time` in final logcat.
- Device/emulator evidence captured on `TD_Pixel8_API36(AVD) - 16`.
- Instrumentation covers one-token smoke, cancellation, critical memory pressure cancellation, and 50-cycle create/load/generate/destroy stress.

## Green Definition

Final status may be marked **GREEN** only when all of these are `PASS` with captured output:

- `assembleDebug` builds with `llama.cpp` compiled and linked.
- `assembleDebugAndroidTest` builds.
- `connectedDebugAndroidTest` runs on a device/emulator.
- `zipalign -c -P 16 -v 4 app-debug.apk` reports `Verification successful`.
- Every packaged native `.so` has LOAD segment alignment `0x4000` or higher.
- `nm` / `llvm-nm` confirms the expected JNI symbols exist in `libllmhost.so`.
- Real tiny GGUF model loads through `ModelStorageManager`.
- `DEBUG_MOCK_MODEL` is not used in real smoke evidence.
- One-token smoke returns at least one real token id and non-empty decoded text.
- Cancellation reaches state `CANCELLED` through the real native loop.
- Create/destroy stress shows no SIGSEGV, SIGBUS, tombstone, or JNI abort.
- Logcat captures model load, prompt eval, token emission, cancellation, EOF, and engine destruction.
- Code search confirms no direct Kotlin `DirectByteBuffer` or `controlBuffer` native-memory reads.
- Release build has `LLMHOST_DEBUG_HOOKS=false`.

## Phase 0 - Baseline Evidence Lock

Goal: Preserve current harness evidence before replacing the native layer.

Tasks:

- Record current APK path and build output.
- Record current `zipalign` and `llvm-readelf` output.
- Record `adb devices` output.
- Search source for `controlBuffer`, `DirectByteBuffer`, `DEBUG_MOCK_MODEL`, `DEBUG_SIMULATE_RING`, and private-member test access.

PASS evidence:

- Build log from current harness.
- Alignment output saved or quoted.
- Search output showing no unsafe Kotlin native-memory reads.

Green impact: does not make project green; it only protects the starting baseline.

## Phase 1 - Add Real llama.cpp Source

Goal: Bring real inference source into the project in a pinned, repeatable way.

Tasks:

- Add `llama.cpp` under `app/src/main/cpp/third_party/llama.cpp`.
- Pin the exact commit or snapshot hash in a local note.
- Do not use floating `main` without a recorded commit.
- Keep OpenMP disabled.
- Keep llamafile disabled.
- Build only required Android CPU backend for the first pass.

Implementation target:

- `app/src/main/cpp/third_party/llama.cpp`
- `app/src/main/cpp/LLAMA_CPP_VERSION.md`

PASS evidence:

- `git submodule status` or equivalent pinned snapshot record.
- File existence for llama headers and CMake entrypoint.
- No network-dependent build step left undocumented.

Green blocker if missing:

- Any unpinned third-party source keeps status RED.

## Phase 2 - Replace Stub Native Engine

Goal: Replace the hollow mock native layer with a real `llama.cpp` engine while preserving Kotlin safety.

Required files:

- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/Engine.cpp`

Native engine must hold:

- `llama_model*`
- `llama_context*`
- optional sampler state if required by the pinned llama.cpp API
- `std::mutex`
- active generation session state
- atomic cancellation flag
- token queue or ring buffer
- terminal state: `EOF`, `CANCELLED`, `ERROR`, `TOMBSTONED`

JNI methods to implement:

- `nativeCreateEngine`
- `nativeDestroyEngine`
- `nativeLoadModel`
- `nativeUnloadModel`
- `nativeStartGeneration`
- `nativeCancelGeneration`
- `nativeDrainTokens`
- `nativeAckEof`
- `nativeDecodeTokens`
- `nativeGetState`
- `nativeSetMemoryPressure`

Hard safety requirements:

- Every JNI entry handles null/zero handle safely.
- `nativeDestroyEngine` cancels and joins any active generation work before freeing model/context.
- `llama_decode` must never run concurrently on the same context.
- `nativeDrainTokens` is non-blocking.
- `nativeDecodeTokens` releases all JNI array/string resources.
- No native pointer is exposed to Kotlin as readable `DirectByteBuffer`.

PASS evidence:

- `assembleDebug` compiles C++ with llama.cpp.
- Native compile log includes `llmhost_jni.cpp`, `Engine.cpp`, and llama.cpp targets.
- Code search shows no direct Kotlin native-memory polling.

Green blocker if missing:

- Stubbed real load/generation path keeps status RED.

## Phase 3 - CMake / Gradle 16 KB Integration

Goal: Preserve Android 15 16 KB page compatibility after linking llama.cpp.

CMake requirements:

```cmake
add_subdirectory(third_party/llama.cpp)
add_library(llmhost SHARED llmhost_jni.cpp Engine.cpp)
target_link_libraries(llmhost PRIVATE llama ggml)
target_link_options(llmhost PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
```

Gradle requirements:

- `externalNativeBuild.cmake.path` points to the CMake file.
- NDK version is locked to r26+; current local NDK r27.1 is acceptable.
- `LLMHOST_DEBUG_HOOKS=true` only for debug/test.
- `LLMHOST_DEBUG_HOOKS=false` for release.
- First real target ABI should be `arm64-v8a`; `x86_64` may remain for emulator if it builds.

PASS evidence:

- `assembleDebug` success.
- `zipalign -c -P 16 -v 4 app-debug.apk` success.
- `llvm-readelf -W -l` over every packaged `.so` shows LOAD alignment `0x4000` or higher.

Green blocker if missing:

- Any packaged native LOAD segment below `0x4000` keeps status RED.

## Phase 4 - Tiny GGUF Smoke Model

Goal: Use a real, tiny model for deterministic smoke tests.

Preferred model:

- TinyStories 1M Q8_0 or TinyStories 33M Q4_0.

Storage recommendation:

- Prefer device external app files for the real smoke model to avoid APK bloat and mmap surprises.
- Use the existing `ModelStorageManager` manifest shape.

Device layout:

```text
/sdcard/Android/data/com.example.llmhost.debug/files/models/tinystories-1m/
  manifest.json
  tinystories-1m.Q8_0.gguf
```

Manifest:

```json
{
  "active_version": "v1",
  "versions": {
    "v1": {
      "file": "tinystories-1m.Q8_0.gguf",
      "sha256": "<sha256sum>"
    }
  }
}
```

Native load requirements:

- `nativeLoadModel` must return true only after real model/context creation succeeds.
- Use small `n_ctx`, such as `512`, for smoke.
- If Android 15/16 KB mmap causes SIGBUS, retry with llama model params `use_mmap = false`.

PASS evidence:

- SHA-256 of the model file recorded.
- `adb shell ls` confirms model and manifest are on device.
- `ModelStorageManager.resolveActiveModel()` returns a file path.
- Logcat shows real GGUF path loaded.

Green blocker if missing:

- No real GGUF file means real smoke is `NOT RUN`, so status cannot be GREEN.

## Phase 5 - Real One-Token Inference

Goal: Make the smallest real inference path work.

Minimal behavior:

- Prompt: `Once upon a`
- Tokenize prompt.
- Run prompt prefill.
- Sample or argmax exactly one next token.
- Queue that token id.
- `nativeDrainTokens` returns the token id.
- `nativeDecodeTokens` returns non-empty decoded text.
- `nativeGetState` returns `EOF` after the one-token path completes.

Kotlin contract stays:

- `generate(prompt): Flow<GenerationChunk>`
- terminal values:
  - `3 = EOF`
  - `4 = CANCELLED`
  - `5 = ERROR`
  - `6 = TOMBSTONED`

PASS evidence:

- Instrumentation or smoke activity log shows:
  - `model_loaded`
  - `prompt_eval_done`
  - `token_emitted id=<n>`
  - `decoded_text=<non-empty>`
  - `terminal=EOF`
- Test assertion confirms `tokenCount >= 1`.
- Test assertion confirms decoded text is not blank.
- Log does not contain `DEBUG_MOCK_MODEL`.

Green blocker if missing:

- Empty decoded text after token drain is FAIL.

## Phase 6 - Cancellation / Lifecycle Safety

Goal: Prove the real native loop handles cancellation and teardown.

Tests:

- Start generation, wait briefly, call cancel.
- Verify state `CANCELLED`.
- Start generation, destroy engine mid-generation.
- Repeat create/load/generate/destroy 50 times.
- Trigger memory pressure level `CRITICAL` and verify native generation cancels.

Required behavior:

- Cancellation flag is atomic.
- Generation loop checks cancellation each iteration.
- Destroy joins active generation thread before freeing context/model.
- After `destroySafely`, Kotlin native calls return safe defaults.

PASS evidence:

- Instrumentation output with all lifecycle tests passing.
- Logcat contains no tombstones.
- Logcat contains no SIGSEGV/SIGBUS/JNI abort.
- Cancellation state observed within a documented timeout.

Green blocker if missing:

- Device-only lifecycle tests not run means status stays YELLOW at best.

## Phase 7 - Instrumentation and Logcat Evidence

Goal: Replace compile-only confidence with device/runtime evidence.

Commands:

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
./gradlew connectedDebugAndroidTest
adb logcat -d -s LlmHostNative:V llama:V llmhost:V *:S
```

PASS evidence:

- Device listed by `adb devices`.
- Install success.
- `connectedDebugAndroidTest` success.
- Logcat saved with:
  - page size
  - model path
  - llama.cpp system info
  - token emitted
  - cancellation
  - destroy completed

Green blocker if missing:

- No attached device/emulator keeps real runtime status `NOT RUN`.

## Phase 8 - Optional Polling Improvement, Not Green-Blocking

The critiques suggest replacing 5 ms polling with `eventfd`, `ALooper`, or a selector-backed wakeup.

Decision:

- This is a performance improvement, not required for the first real inference GREEN gate.
- Do not implement until the real one-token path and lifecycle tests pass.

Reason:

- The current polling design is simpler and has already been safety-reviewed.
- Inverting the stream loop adds NDK/Kotlin complexity and could hide correctness bugs during the first real inference bring-up.

## Evidence Board

Use this board during implementation. Do not mark GREEN unless every required row is PASS.

| Gate | Required Evidence | Status |
|---|---|---:|
| Pinned llama.cpp source | commit/snapshot recorded | PASS |
| Real CMake integration | build log includes llama.cpp | PASS |
| Real native model/context | code + build evidence | PASS |
| Debug hooks off in release | release BuildConfig/CMake evidence | PASS |
| No DirectByteBuffer polling | source search output | PASS |
| `assembleDebug` | build output | PASS |
| `assembleDebugAndroidTest` | build output | PASS |
| 16 KB APK alignment | zipalign output | PASS |
| ELF LOAD alignment | readelf output | PASS |
| Tiny GGUF available | SHA + device path | PASS |
| ModelStorageManager resolves model | instrumentation/log output | PASS |
| Real one-token smoke | token id + decoded text + EOF | PASS |
| Cancellation | device test + state 4 | PASS |
| Lifecycle stress | 50 cycles, no crash | PASS |
| Logcat capture | saved logcat output | PASS |

Overall status rules:

- **RED:** any required implementation is missing, any required evidence is `FAIL`, or real inference is stubbed.
- **YELLOW:** real inference implemented and builds, but device tests or real smoke evidence are missing.
- **GREEN:** every required gate is `PASS` with evidence and no critical risks remain open.
