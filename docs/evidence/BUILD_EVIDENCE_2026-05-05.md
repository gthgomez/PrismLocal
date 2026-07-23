# LLM Host From Docs - APK Build Evidence - 2026-05-05

## Source

Input archive:

`C:\Users\icbag\Downloads\drive-download-20260505T045523Z-3-001.zip`

Converted doc text:

`C:\Workspace\artifacts\llm-host-docs-20260505-text\`

Build project created from those snippets:

`C:\Workspace\artifacts\llm-host-apk-20260505\`

## APK Outputs

Main debug APK:

`C:\Workspace\artifacts\llm-host-apk-20260505\app\build\outputs\apk\debug\app-debug.apk`

- Size: `3,872,083` bytes

Instrumentation APK:

`C:\Workspace\artifacts\llm-host-apk-20260505\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`

- Size: `446,328` bytes

## Build Commands

Command:

```powershell
$env:GRADLE_USER_HOME='C:\Users\icbag\.gradle'
$env:ANDROID_HOME='C:\Users\icbag\AppData\Local\Android\Sdk'
C:\Workspace\Project_Android\gradlew.bat -p C:\Workspace\artifacts\llm-host-apk-20260505 assembleDebug assembleDebugAndroidTest
```

Result:

`PASS`

Evidence:

```text
BUILD SUCCESSFUL in 13s
74 actionable tasks: 11 executed, 63 up-to-date
```

## Safety Requirements

| Requirement | Status | Evidence |
|---|---:|---|
| Remove direct `controlBuffer` reads from generation loop or guard backing memory lifetime. | PASS | No `controlBuffer` exists in the generated app source. Kotlin reads stream state only through `nativeGetState()` under `nativeMutex`. |
| Replace private-member test access with public `@VisibleForTesting` debug APIs or test JNI functions. | PASS | Tests use `debugDrainTokensForTesting()`; they do not access `nativeHandle`, `nativeMutex`, or private native methods directly. |
| Fix cancellation tests so they do not expect terminal chunks after cancelling the collector. | PASS | `cancellationDoesNotRequireTerminalAfterCollectorCancel()` cancels the collector and checks stale drain behavior only if a generation id was observed. It does not assert a post-cancel terminal chunk. |
| Put `DEBUG_MOCK_MODEL` and `DEBUG_SIMULATE_RING` behind debug/test build flags. | PASS | Gradle sets `BuildConfig.LLMHOST_DEBUG_HOOKS=true` for debug and CMake receives `-DLLMHOST_DEBUG_HOOKS=ON`. C++ debug hook branches are wrapped in `#if LLMHOST_DEBUG_HOOKS`. |
| Run `assembleDebug` and fix compile errors. | PASS | `assembleDebug` passed. |
| Run the 16 KB validation script/check. | PASS | `zipalign -c -P 16 -v 4 app-debug.apk` returned `Verification successful`. |
| Validate native LOAD segment alignment. | PASS | `llvm-readelf -W -l` showed every `libllmhost.so` LOAD segment for `arm64-v8a` and `x86_64` aligned to `0x4000`. |
| Run instrumentation stress tests. | NOT RUN | `adb devices` showed no attached device/emulator. Instrumentation test APK compilation passed with `assembleDebugAndroidTest`. |
| Run real tiny GGUF one-token smoke test and logcat capture. | NOT RUN | No `llama.cpp` source, real tiny GGUF file, or attached device/emulator was present. The generated native bridge does not fake real GGUF inference; it logs/returns unsupported for real model loading. |

## 16 KB Validation Details

APK alignment command:

```powershell
C:\Users\icbag\AppData\Local\Android\Sdk\build-tools\36.0.0\zipalign.exe -c -P 16 -v 4 app-debug.apk
```

Key output:

```text
lib/arm64-v8a/libllmhost.so (OK)
lib/x86_64/libllmhost.so (OK)
Verification successful
```

Native LOAD segment checks:

```text
arm64-v8a LOAD alignments: 0x4000, 0x4000, 0x4000
x86_64 LOAD alignments:   0x4000, 0x4000, 0x4000
```

## Known Limitation

This APK is a doc-derived build harness for the native bridge and safety gates. It does not include real `llama.cpp` inference because the input ZIP did not contain `llama.cpp`, a model asset, or a buildable original Android LLM host source tree.
