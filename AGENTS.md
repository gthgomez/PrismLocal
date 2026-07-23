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
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
```

From the composite workspace root only:

```powershell
.\PrismLocal\gradlew.bat -p PrismLocal --no-daemon :app:assembleDebug
```

**Do not** run `.\gradlew.bat :app:assembleDebug` from `Project_Android` — the composite root has no `:app` module (`project 'app' not found`).

**Verification gate:** from `PrismLocal/`, `.\gradlew.bat --no-daemon assembleDebug`
