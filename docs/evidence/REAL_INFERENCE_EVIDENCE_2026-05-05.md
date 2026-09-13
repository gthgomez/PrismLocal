# Real Inference Completion Evidence - 2026-05-05

## Outcome

Status: GREEN

`<workspace>\artifacts\llm-host-apk-20260505` now builds and runs a real `llama.cpp`-backed local GGUF one-token smoke path. The final connected Android instrumentation run passed 8/8 tests on `TD_Pixel8_API36(AVD) - 16`.

## Implementation Evidence

- `llama.cpp` source is vendored at `app/src/main/cpp/third_party/llama.cpp`.
- Pinned commit: `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`.
- Version note: `app/src/main/cpp/LLAMA_CPP_VERSION.md`.
- Native engine files:
  - `app/src/main/cpp/Engine.hpp`
  - `app/src/main/cpp/Engine.cpp`
  - `app/src/main/cpp/llmhost_jni.cpp`
  - `app/src/main/cpp/CMakeLists.txt`
- Native state includes real `llama_model*`, `llama_context*`, tokenizer/decode/sampler path, mutex-guarded context use, cancellation, token ring, EOF/CANCELLED/ERROR/TOMBSTONED terminal states, and safe destroy joins.

## Model Evidence

- Model source: `afrideva/TinyLLama-v0-GGUF`, file `tinyllama-v0.q8_0.gguf`.
- Local model path: `validation/models/tinystories-1m/tinyllama-v0.q8_0.gguf`.
- Android test asset path: `app/src/androidTest/assets/tinystories-1m/tinyllama-v0.q8_0.gguf`.
- Size: `6750304` bytes.
- SHA-256: `0cbe6769faaa77f4cdbf2f39dbc5f0fb9b3d62f7f9bc5760aa364946e6f2d0f5`.
- Final emulator filesystem proof:

```text
adb shell ls -l /sdcard/Android/data/com.example.llmhost.debug/files/models/tinystories-1m
total 6608
-rw-rw-rw- 1 shell ext_data_rw     191 2026-05-05 18:11 manifest.json
-rw-rw-rw- 1 shell ext_data_rw 6750304 2026-05-05 18:10 tinyllama-v0.q8_0.gguf

adb shell sha256sum /sdcard/Android/data/com.example.llmhost.debug/files/models/tinystories-1m/tinyllama-v0.q8_0.gguf
0cbe6769faaa77f4cdbf2f39dbc5f0fb9b3d62f7f9bc5760aa364946e6f2d0f5  /sdcard/Android/data/com.example.llmhost.debug/files/models/tinystories-1m/tinyllama-v0.q8_0.gguf
```

Instrumentation uses the same model bytes under the isolated id `tinystories-1m-instrumentation` to avoid colliding with manually staged production model files.

## Build And Device Verification

```text
<workspace>\Project_Android\gradlew.bat -p <workspace>\artifacts\llm-host-apk-20260505 assembleDebug assembleDebugAndroidTest assembleRelease
BUILD SUCCESSFUL in 4s
128 actionable tasks: 13 executed, 115 up-to-date
```

```text
<workspace>\Project_Android\gradlew.bat -p <workspace>\artifacts\llm-host-apk-20260505 connectedDebugAndroidTest
Starting 8 tests on TD_Pixel8_API36(AVD) - 16
Finished 8 tests on TD_Pixel8_API36(AVD) - 16
BUILD SUCCESSFUL in 48s
```

Android test XML summary:

```text
<testsuites tests="8" failures="0" errors="0" skipped="0" time="39.767" timestamp="2026-05-05T23:26:50" ...>
<testsuite name="com.example.llmhost.EngineStressTest" tests="4" failures="0" errors="0" skipped="0" ...>
<testsuite name="com.example.llmhost.RealInferenceSmokeTest" tests="4" failures="0" errors="0" skipped="0" ...>
```

## Runtime Log Evidence

Saved logcat: `validation/logcat-real-inference-2026-05-05.txt`.

Representative final-run lines:

```text
model_loaded path=/storage/emulated/0/Android/data/com.example.llmhost.debug/files/models/tinystories-1m-instrumentation/tinyllama-v0.q8_0.gguf mmap=true n_ctx=512 threads=4
prompt_eval_done generation_id=1 prompt_tokens=4
token_emitted id=647 generation_id=1
terminal=EOF generation_id=1
decoded_text=time generation_id=1
terminal=CANCELLED generation_id=7002
```

Crash scan:

```text
Select-String -Path .\validation\logcat-real-inference-2026-05-05.txt -Pattern 'SIGSEGV|SIGBUS|tombstone|JNI DETECTED ERROR|JNI abort|FATAL EXCEPTION' -CaseSensitive:$false
```

Result: no matches.

## Packaging And Safety Evidence

- APK sizes:
  - `app-debug.apk`: `23877259` bytes.
  - `app-debug-androidTest.apk`: `5738025` bytes.
  - `app-release-unsigned.apk`: `11431548` bytes.
- `zipalign -c -P 16 -v 4` result:
  - debug APK: `Verification successful`.
  - androidTest APK: `Verification successful`.
  - release APK: `Verification successful`.
- `llvm-readelf -W -l` over stripped debug/release `libllmhost.so` for `arm64-v8a` and `x86_64` reports all LOAD segment alignments as `0x4000`.
- `llvm-nm -D` confirms expected `Java_com_example_llmhost_NativeLlmBridge_native*` JNI exports in debug and release `libllmhost.so`.
- Release `BuildConfig.java` contains `public static final boolean LLMHOST_DEBUG_HOOKS = false;`.
- Source search result:

```text
NO_MATCH: DirectByteBuffer/controlBuffer
```

## Known Notes

- The existing `llm_host.cpp` file is no longer part of `llmhost` CMake sources; it was left in place rather than deleted because this is an artifact workspace and deletion was not required.
- The final connected test is hermetic: it stages the GGUF from androidTest assets into app-owned external files before resolving through `ModelStorageManager`.
