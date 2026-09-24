# Final Fix Report — Inference Lifecycle Race Fixes

**Date:** 2026-09-24
**Final fix commit:** `e616fb5` — `fix(lifecycle): close final admission ownership races`

## Root causes fixed

1. **Source-chat ownership TOCTOU:** `AgentToolRouter` performed the early source-chat check outside the `AgentTrace` monitor. A callback could pass that check, wait for trace ownership, and append after a transition changed chats. The ownership check is now repeated inside `AgentTrace.withCurrentChain` immediately before `onAppendTranscriptMessage`, under the same monitor as `beginChain`.
2. **Follow-up admission bypass:** `startAgentFollowUpGeneration` launched directly into `operationMutex` and `GenerationOrchestrator.startFollowUp`. It now submits `runGenerationAdmission`, then acquires `operationMutex`, and revalidates chain/chat ownership before any follow-up generation work. Follow-up activity markers are cleared from job completion, not the cancellable launching coroutine.
3. **Caller cancellation:** awaited coordinator commands were detached from their caller Job. Commands now remember the caller only before actor start. Cancellation before the actor's start gate skips cleanup/mutation/admission; cancellation after start cannot interrupt an already-admitted actor command. Parent/actor shutdown fails pending commands.
4. **Unobserved public transition outcomes:** the coordinator now accepts a nonblocking outcome observer. Public `createChat`, `switchChat`, `deleteChat`, and `clearTranscript` publish/log rejected and failed outcomes with operation context while still returning only `Queued`/`Rejected` request results.
5. **Deferred coverage:** tracker methods now have direct zero/negative-ID tests; wrong-source-chat confirmation discard coverage verifies both the newer token and UI card survive.

## Exact verification evidence

- Focused lifecycle/agent/generation tests:
  - `ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:testDevDebugUnitTest --tests 'com.prismai.llmhost.lifecycle.*' --tests 'com.prismai.llmhost.agent.*' --tests 'com.prismai.llmhost.generation.*'`
  - Result: `BUILD SUCCESSFUL` (including 42 focused tests in the final focused run).
- Forced full Dev/Play unit matrix:
  - `ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon --rerun-tasks :app:testDevDebugUnitTest :app:testPlayDebugUnitTest`
  - Result: `BUILD SUCCESSFUL in 1m 7s`; 52 tasks executed; both Dev and Play unit-test tasks passed.
- Main/androidTest compilation:
  - `ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:compileDevDebugAndroidTestSources :app:compilePlayDebugAndroidTestSources`
  - Result: `BUILD SUCCESSFUL in 32s`; both flavor androidTest source compilations passed.
- Native host tests:
  - `cmake -S app/src/test/cpp -B /tmp/opencode/prismlocal-native-tests -DCMAKE_BUILD_TYPE=Debug`
  - `cmake --build /tmp/opencode/prismlocal-native-tests`
  - `ctest --test-dir /tmp/opencode/prismlocal-native-tests --output-on-failure`
  - Result: all 7 native tests passed; 0 failed.
- Whitespace:
  - `git diff --check`
  - Result: passed with no output.

## Files changed

- `app/src/main/java/com/prismai/llmhost/agent/AgentToolRouter.kt`
- `app/src/main/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinator.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/src/test/java/com/prismai/llmhost/agent/AgentActivityTrackerTest.kt`
- `app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt`
- `app/src/test/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinatorTest.kt`

## Self-review

- Preserved nonblocking public request semantics; only the observer is asynchronous with actor execution.
- Preserved actor nested-transition handling, bounded coordinator shutdown, native teardown fallback, consumed-confirmation continuity, chain-scoped follow-up markers, settings cancellation rollback, and explicit confirmed error mapping.
- The command start gate is the linearization point: `markStartedAndCheckCancellation` wins over cancellation before start; after it returns, caller cancellation is deliberately not propagated into the actor command.
- No native, persistence, JNI, manifest, or device-test changes were made.

## Concerns / limitations

- Connected-device instrumentation tests were not run; no device was available/claimed.
- Gradle emitted existing deprecation warnings in `MemoryGovernor.kt` and `CodeBlockRendering.kt`; these are unrelated to this wave.
- Public outcome observer delivery runs on the lifecycle actor after request acceptance; service observers are intentionally lightweight logging/UI-event publication.
