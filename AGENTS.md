# AGENTS.md — LLMHostAndroid (Gemini 3 Flash Override)

> Inherits from root [AGENTS.md](file:///C:/Workspace/Project_Android/AGENTS.md). General guidance is in [CLAUDE.md](./CLAUDE.md).

## Gemini-Specific Risks
- Hallucinated JNI method signatures — name mangling must match C++ function names exactly
- Incorrect CMakeLists.txt NDK configuration — ABI targets, C++17 standard, include paths
- Confusion between Kotlin coroutine cancellation and native thread safety — native state needs mutex guards
- Hallucinated llama.cpp API bindings — verify against the vendored llama.cpp headers

## Build (Gradle)

**Working directory must be this project root** (`PrismLocal/`), not `Project_Android/`.

```powershell
cd C:\Workspace\Project_Android\PrismLocal
.\gradlew.bat --no-daemon :app:testDevDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDevDebug
```

The project has `dev` and `play` product flavors — bare `testDebugUnitTest` / `assembleDebug` fail with "ambiguous task". Use the flavor-qualified names (`DevDebug`, `PlayDebug`).

From the composite workspace root only:

```powershell
.\PrismLocal\gradlew.bat -p PrismLocal --no-daemon :app:assembleDevDebug
```

**Do not** run `.\gradlew.bat :app:assembleDebug` from `Project_Android` — the composite root has no `:app` module (`project 'app' not found`).

**Verification gate:** from `PrismLocal/`, `.\gradlew.bat --no-daemon :app:assembleDevDebug`

**Native builds on Windows:** if the vulkan-shaders-gen step fails with "CMake was unable to find a build program corresponding to Ninja", prepend the SDK cmake dir to PATH first: `$env:PATH = "C:\Users\<user>\AppData\Local\Android\Sdk\cmake\3.22.1\bin;$env:PATH"`.
