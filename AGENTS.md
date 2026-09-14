# AGENTS.md — LLMHostAndroid (Gemini 3 Flash Override)

> Inherits from the parent workspace `AGENTS.md` (`<workspace>/Project_Android/AGENTS.md`). General guidance is in [CLAUDE.md](./CLAUDE.md).

## Gemini-Specific Risks
- Hallucinated JNI method signatures — name mangling must match C++ function names exactly
- Incorrect CMakeLists.txt NDK configuration — ABI targets, C++20 standard, include paths
- Confusion between Kotlin coroutine cancellation and native thread safety — native state needs mutex guards
- Hallucinated llama.cpp API bindings — verify against the vendored llama.cpp headers

## Build (Gradle)

**Working directory must be this project root** (`PrismLocal/`), not `Project_Android/`.

```powershell
cd <workspace>\Project_Android\PrismLocal
.\gradlew.bat --no-daemon :app:testDevDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDevDebug
```

The project has `dev` and `play` product flavors — bare `testDebugUnitTest` / `assembleDebug` fail with "ambiguous task". Use the flavor-qualified names (`DevDebug`, `PlayDebug`).

From the composite workspace root only:

```powershell
.\PrismLocal\gradlew.bat -p PrismLocal --no-daemon :app:assembleDevDebug
```

**Do not** run `.\gradlew.bat :app:assembleDebug` from `Project_Android` — the composite root has no `:app` module (`project 'app' not found`).

**Verification gate:** from `PrismLocal/`, `.\gradlew.bat --no-daemon :app:assembleDevDebug` (quick loop). Before pushing anything touching native code, Gradle config, or ProGuard rules, run the full gate: `.\scripts\verify.ps1` — unit tests + `assembleDevBenchmark` + `assemblePlayRelease`. Debug-only builds never compile the RelWithDebInfo native config, R8/ProGuard rules, or the vulkan-shaders-gen host tool; CI enforces this via the `native-builds` job.

**Native builds on Windows:** the vendored llama.cpp local patch (`patches/llama.cpp/ggml-vulkan-local-build.patch`) forwards `CMAKE_MAKE_PROGRAM` into the `vulkan-shaders-gen` ExternalProject, so no PATH setup is required. If you ever reset/update the submodule and skip `git apply` of that patch, vulkan-shaders-gen fails with "CMake was unable to find a build program corresponding to Ninja" — re-apply the patch (preferred) or prepend `$env:PATH = "$env:ANDROID_HOME\cmake\3.22.1\bin;$env:PATH"` as a fallback.
