# AGENTS.md — LLMHostAndroid (Gemini 3 Flash Override)

> Inherits from root [AGENTS.md](file:///C:/Workspace/Project_Android/AGENTS.md). General guidance is in [CLAUDE.md](./CLAUDE.md).

## Gemini-Specific Risks
- Hallucinated JNI method signatures — name mangling must match C++ function names exactly
- Incorrect CMakeLists.txt NDK configuration — ABI targets, C++17 standard, include paths
- Confusion between Kotlin coroutine cancellation and native thread safety — native state needs mutex guards
- Hallucinated llama.cpp API bindings — verify against the vendored llama.cpp headers

**Verification gate:** `./gradlew assembleDebug`
