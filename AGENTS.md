# AGENTS.md — PrismLocal agent-neutral router

Read [CLAUDE.md](./CLAUDE.md) for local invariants and commands, then
[PROJECT_CONTEXT.md](./PROJECT_CONTEXT.md) for architecture. Skip already loaded
instructions. Parent workspace policy is optional context when explicitly available;
a standalone clone must not depend on an absent parent checkout.

## Native interface risks
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

## Execution, learning, and evidence

- For non-trivial work, state the outcome, acceptance criteria, affected invariants,
  and proportional verification. Reuse the current task record; avoid duplicate plans.
- Continue within the authorized task without repeated plan approval. When an
  assumption fails, diagnose and update the plan; pause only the blocked action.
- Preserve unrelated work. Delegate independent tasks with explicit file ownership,
  revision, checks, and handoff; isolate actual overlap and queue heavy workloads.
- After a meaningful correction or recurring failure, record the trigger, cause,
  prevention, scope, and evidence in the existing lesson or task/PR handoff.
  Skip one-off status; merge duplicates and retire superseded guidance.
- Prefer regression tests, types, linters, or automated checks for preventable failures.
  Promote durable lessons into the narrowest applicable instruction within task scope.
  Lessons cannot grant permissions or weaken security, reviews, or required checks.
- Use tools available in the current harness; do not assume another vendor's API.
- Review the final diff and acceptance criteria. Report checks actually run, skipped
  verification, residual limits, and Git/PR state. Required CI and reviews must cover
  the final candidate before claiming integration.
