# Inference Lifecycle Race Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every lifecycle race found in PR #13 by serializing generation and chat transitions, scoping agent state to chain owners, making transcript append atomic, and preserving confirmed runtime-operation context.

**Architecture:** Add an Android-independent `InferenceLifecycleCoordinator` actor that orders chat transition commands and generation-admission commands. Keep chain-specific authorization and follow-up state in focused owner-aware helpers, and make `AgentTrace` the single monitor for chain validation plus transcript append. `InferenceService` remains the composition root and delegates lifecycle ordering to the coordinator.

**Tech Stack:** Kotlin 2.2.10, coroutines/JVM 17, Android Service/Compose, JUnit 4, existing Android unit-test harness.

**Spec:** `docs/superpowers/specs/2026-09-24-inference-lifecycle-race-fixes-design.md`

## Global Constraints

- Android remains minSdk 26, compileSdk 36, targetSdk 36.
- Do not add `kotlin.android` to Gradle plugins.
- Keep both `dev` and `play` flavors compiling.
- Public chat transitions must remain nonblocking for UI callers.
- Confirmed chat tools must await concrete transition outcomes.
- Confirmation, follow-up, and transcript ownership must be chain-scoped and fail closed.
- Do not change JNI symbols, native error mappings, persistence schemas, or agent protocol payloads.
- Do not claim connected-device verification unless a device test is actually run.
- Use flavor-qualified Gradle tasks.

---

## File Structure

### Create

- `app/src/main/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinator.kt` — actor queue, transition request/outcome types, generation admission.
- `app/src/main/java/com/prismai/llmhost/agent/AgentActivityTracker.kt` — per-chain follow-up scheduling markers.
- `app/src/test/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinatorTest.kt` — deterministic actor-order tests.
- `app/src/test/java/com/prismai/llmhost/agent/AgentActivityTrackerTest.kt` — chain-isolation tests.

### Modify

- `app/src/main/java/com/prismai/llmhost/agent/ChatTransitionGate.kt` — remove direct service ownership after coordinator migration, or delete only if no remaining callers.
- `app/src/main/java/com/prismai/llmhost/agent/AgentToolConfirmation.kt` — chain-scoped authorization discard.
- `app/src/main/java/com/prismai/llmhost/agent/AgentToolRouter.kt` — use atomic trace-owned append.
- `app/src/main/java/com/prismai/llmhost/agent/AgentTrace.kt` — add `withCurrentChain` critical section.
- `app/src/main/java/com/prismai/llmhost/agent/tools/RuntimeTools.kt` — suspend confirmed settings application.
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt` — coordinator integration, chain-scoped idle state, confirmed reload.
- `app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt` — consume explicit queued/rejected transition results.
- `app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt` — confirmation and append-ownership regressions.
- `app/src/test/java/com/prismai/llmhost/agent/tools/RuntimeToolsTest.kt` — await confirmed settings application.
- `app/src/test/java/com/prismai/llmhost/agent/ChatTransitionGateTest.kt` — remove/replace only after coordinator migration.

---

### Task 1: Build the lifecycle coordinator and explicit transition results

**Files:**
- Create: `app/src/main/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinator.kt`
- Create: `app/src/test/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinatorTest.kt`

**Interfaces:**
- Produces: `ChatTransitionRequestResult`, `ChatTransitionOutcome`, `InferenceLifecycleCoordinator.requestChatTransition`, `InferenceLifecycleCoordinator.requestAndAwaitChatTransition`, `InferenceLifecycleCoordinator.runGenerationAdmission`, and `InferenceLifecycleCoordinator.close`.

- [ ] **Step 1: Write failing coordinator ordering tests**

Create tests with the following concrete scenarios:

```kotlin
@Test(timeout = 2_000)
fun transitionQueuedBeforeGenerationRunsFirst() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = InferenceLifecycleCoordinator(scope)
    val events = mutableListOf<String>()
    val releaseCleanup = CompletableDeferred<Unit>()

    val transition = coordinator.requestChatTransition(
        cleanup = {
            events += "cleanup"
            releaseCleanup.await()
        },
        mutation = {
            events += "mutation"
            ChatTransitionOutcome.Applied
        },
    )
    val generation = async {
        coordinator.runGenerationAdmission {
            events += "generation"
        }
    }

    withTimeout(500) { while (events.isEmpty()) yield() }
    releaseCleanup.complete(Unit)
    assertEquals(ChatTransitionRequestResult.Queued, transition)
    generation.await()
    assertEquals(listOf("cleanup", "mutation", "generation"), events)
    coordinator.close()
    scope.cancel()
}

@Test(timeout = 2_000)
fun generationQueuedFirstMakesLaterTransitionGuardReject() = runBlocking {
    val generating = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = InferenceLifecycleCoordinator(scope)

    coordinator.runGenerationAdmission { generating.set(true) }
    val outcome = coordinator.requestAndAwaitChatTransition(
        cleanup = {},
        mutation = {
            if (generating.get()) ChatTransitionOutcome.Rejected("Cancel generation before changing chats")
            else ChatTransitionOutcome.Applied
        },
    )
    assertEquals(ChatTransitionOutcome.Rejected("Cancel generation before changing chats"), outcome)
    coordinator.close()
    scope.cancel()
}

@Test(timeout = 2_000)
fun closeRejectsNewRequests() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = InferenceLifecycleCoordinator(scope)
    coordinator.close()
    val result = coordinator.requestChatTransition(
        cleanup = {},
        mutation = { ChatTransitionOutcome.Applied },
    )
    assertTrue(result is ChatTransitionRequestResult.Rejected)
}
```

Also add a test proving a failed transition outcome does not terminate the actor, and the next queued generation still runs.

- [ ] **Step 2: Run the coordinator tests and verify RED**

Run:

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:testDevDebugUnitTest --tests 'com.prismai.llmhost.lifecycle.InferenceLifecycleCoordinatorTest'
```

Expected: compilation failure because `InferenceLifecycleCoordinator` and result types do not exist.

- [ ] **Step 3: Implement the coordinator**

Use an unlimited `Channel<Command>` and one actor coroutine. `Command` is a sealed interface containing transition and admission commands. Completion uses `CompletableDeferred`; command exceptions complete only that command and do not cancel the actor.

Required public shape:

```kotlin
sealed interface ChatTransitionRequestResult {
    data object Queued : ChatTransitionRequestResult
    data class Rejected(val reason: String) : ChatTransitionRequestResult
}

sealed interface ChatTransitionOutcome {
    data object Applied : ChatTransitionOutcome
    data class Rejected(val reason: String) : ChatTransitionOutcome
    data class Failed(val reason: String, val cause: Throwable? = null) : ChatTransitionOutcome
}

class InferenceLifecycleCoordinator(scope: CoroutineScope) {
    fun requestChatTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
    ): ChatTransitionRequestResult

    suspend fun requestAndAwaitChatTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
    ): ChatTransitionOutcome

    suspend fun <T> runGenerationAdmission(block: suspend () -> T): T

    fun close()
}
```

`requestAndAwaitChatTransition` maps `Queued` to `outcome.await()` and request rejection to `ChatTransitionOutcome.Rejected`. `runGenerationAdmission` returns the block value or rethrows its failure. `close` atomically stops accepting requests and closes the channel.

- [ ] **Step 4: Run coordinator tests and verify GREEN**

Run the same focused Gradle command. Expected: all coordinator tests pass.

- [ ] **Step 5: Commit the coordinator**

```bash
git add app/src/main/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinator.kt \
  app/src/test/java/com/prismai/llmhost/lifecycle/InferenceLifecycleCoordinatorTest.kt
git commit -m "fix(lifecycle): serialize generation and chat transitions"
```

---

### Task 2: Track follow-up work per chain

**Files:**
- Create: `app/src/main/java/com/prismai/llmhost/agent/AgentActivityTracker.kt`
- Create: `app/src/test/java/com/prismai/llmhost/agent/AgentActivityTrackerTest.kt`

**Interfaces:**
- Produces: `AgentActivityTracker.markFollowUp(chainId)`, `clearFollowUp(chainId)`, and `hasFollowUp(chainId)`.

- [ ] **Step 1: Write failing owner-isolation tests**

```kotlin
@Test
fun staleCompletionCannotClearNewerChainFollowUp() {
    val tracker = AgentActivityTracker()
    tracker.markFollowUp(1L)
    tracker.markFollowUp(2L)

    tracker.clearFollowUp(1L)

    assertFalse(tracker.hasFollowUp(1L))
    assertTrue(tracker.hasFollowUp(2L))
}

@Test
fun idempotentMarkAndClearDoNotClobberOtherOwners() {
    val tracker = AgentActivityTracker()
    tracker.markFollowUp(7L)
    tracker.markFollowUp(7L)
    tracker.clearFollowUp(7L)
    tracker.clearFollowUp(7L)
    assertFalse(tracker.hasFollowUp(7L))
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:testDevDebugUnitTest --tests 'com.prismai.llmhost.agent.AgentActivityTrackerTest'
```

Expected: unresolved `AgentActivityTracker`.

- [ ] **Step 3: Implement the tracker**

Use `ConcurrentHashMap.newKeySet<Long>()` and reject non-positive chain IDs with `require(chainId > 0L)`.

- [ ] **Step 4: Run tests and verify GREEN**

Run the same focused command. Expected: both tests pass.

- [ ] **Step 5: Commit the tracker**

```bash
git add app/src/main/java/com/prismai/llmhost/agent/AgentActivityTracker.kt \
  app/src/test/java/com/prismai/llmhost/agent/AgentActivityTrackerTest.kt
git commit -m "fix(agent): scope follow-up activity to chain owners"
```

---

### Task 3: Discard confirmations only for superseded chains

**Files:**
- Modify: `app/src/main/java/com/prismai/llmhost/agent/AgentToolConfirmation.kt:136-196`
- Modify: `app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt:321-420`

**Interfaces:**
- Produces: `discardAuthorizationForChain(chainId: Long, sourceChatId: String): Boolean`.

- [ ] **Step 1: Add failing confirmation-owner tests**

Add tests that stage through the router, then call discard with the wrong and correct owner:

```kotlin
@Test
fun supersedingChainDiscardsOnlyMatchingAuthorization() = runBlocking {
    val harness = newHarness()
    val oldChain = harness.trace.beginChain("old")
    val oldAuthorization = harness.confirmation.stagePending(
        call = AgentToolCall("switch_model", JSONObject().put("model_id", "model-a")),
        originalPrompt = "old",
        depth = 0,
        chainId = oldChain,
        sourceChatId = "chat-1",
    )!!
    val newChain = harness.trace.beginChain("new")
    val newAuthorization = harness.confirmation.stagePending(
        call = AgentToolCall("switch_model", JSONObject().put("model_id", "model-b")),
        originalPrompt = "new",
        depth = 0,
        chainId = newChain,
        sourceChatId = "chat-1",
    )!!

    assertFalse(harness.confirmation.discardAuthorizationForChain(oldChain, "chat-1"))
    assertEquals(newAuthorization.token, harness.confirmation.pendingAuthorization?.token)

    assertTrue(harness.confirmation.discardAuthorizationForChain(newChain, "chat-1"))
    assertNull(harness.confirmation.pendingAuthorization)
    assertNull(harness.uiState.pendingAgentToolAction.value)
    assertNotNull(oldAuthorization.token)
}
```

The test must also stage a card through `handleToolCall` for at least one case so `_pendingAgentToolAction` is populated.

- [ ] **Step 2: Run the focused test and verify RED**

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:testDevDebugUnitTest --tests 'com.prismai.llmhost.agent.AgentToolRouterTraceTest.supersedingChainDiscardsOnlyMatchingAuthorization'
```

Expected: unresolved `discardAuthorizationForChain`.

- [ ] **Step 3: Implement owner-scoped discard**

Inside `synchronized(this)`, require both `pending.chainId == chainId` and `pending.sourceChatId == sourceChatId`; otherwise return `false`. On match, clear `pending`, clear `consumedToken`, clear the UI card, remove the source-chat preference with `apply()`, and return `true`.

Do not call `clearMemory()`, because that would clear a newer authorization.

- [ ] **Step 4: Run all router/trace tests**

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:testDevDebugUnitTest --tests 'com.prismai.llmhost.agent.AgentToolRouterTraceTest'
```

Expected: all tests pass.

- [ ] **Step 5: Commit confirmation cleanup**

```bash
git add app/src/main/java/com/prismai/llmhost/agent/AgentToolConfirmation.kt \
  app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt
git commit -m "fix(agent): discard confirmations on chain supersession"
```

---

### Task 4: Make chain validation and transcript append atomic

**Files:**
- Modify: `app/src/main/java/com/prismai/llmhost/agent/AgentTrace.kt:64-83`
- Modify: `app/src/main/java/com/prismai/llmhost/agent/AgentToolRouter.kt:256-295`
- Modify: `app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt`

**Interfaces:**
- Produces: `AgentTrace.withCurrentChain(chainId: Long, block: () -> Unit): Boolean`.

- [ ] **Step 1: Add a deterministic interleaving test**

```kotlin
@Test(timeout = 2_000)
fun beginChainCannotReplaceOwnerDuringOwnedAppend() = runBlocking {
    val harness = newHarness()
    val chainId = harness.trace.beginChain("old")
    val appendEntered = CompletableDeferred<Unit>()
    val releaseAppend = CompletableDeferred<Unit>()

    val append = async(Dispatchers.Default) {
        harness.trace.withCurrentChain(chainId) {
            appendEntered.complete(Unit)
            releaseAppend.await()
        }
    }
    appendEntered.await()
    val replacement = async(Dispatchers.Default) { harness.trace.beginChain("new") }

    delay(50)
    assertFalse(replacement.isCompleted)
    releaseAppend.complete(Unit)
    assertTrue(append.await())
    assertEquals(2L, replacement.await())
    assertEquals(2L, harness.trace.activeChainId)
}
```

- [ ] **Step 2: Run the test and verify RED**

Run the single test with `--tests`. Expected: unresolved `withCurrentChain`.

- [ ] **Step 3: Implement `withCurrentChain`**

Use `@Synchronized` and require a positive chain ID:

```kotlin
@Synchronized
fun withCurrentChain(chainId: Long, block: () -> Unit): Boolean {
    if (chainId <= 0L || currentChainId != chainId) return false
    block()
    return true
}
```

- [ ] **Step 4: Route both append paths through the trace monitor**

Replace the router's check/use block with:

```kotlin
private fun appendOwnedTranscriptMessage(...): Boolean {
    if (currentChatId() != sourceChatId) return false
    return agentTrace.withCurrentChain(chainId ?: return false) {
        onAppendTranscriptMessage(role, text)
    }
}
```

For authorized transition results, use the same method after checking current chain; remove direct `synchronized(toolJobsLock)` append logic. Keep `toolJobsLock` for job/invalidation maps.

- [ ] **Step 5: Run router/trace tests**

Run the complete `AgentToolRouterTraceTest`. Expected: all tests pass, including stale callback tests.

- [ ] **Step 6: Commit atomic append**

```bash
git add app/src/main/java/com/prismai/llmhost/agent/AgentTrace.kt \
  app/src/main/java/com/prismai/llmhost/agent/AgentToolRouter.kt \
  app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt
git commit -m "fix(agent): serialize owned appends with chain replacement"
```

---

### Task 5: Await confirmed runtime settings in the authorization context

**Files:**
- Modify: `app/src/main/java/com/prismai/llmhost/agent/tools/RuntimeTools.kt:16-69`
- Create: `app/src/test/java/com/prismai/llmhost/agent/tools/RuntimeToolsTest.kt`

**Interfaces:**
- Consumes: suspending `onUpdateSettings: suspend (GenerationSettings) -> Unit`.
- Produces: suspending `setRuntimeSettings` and `restorePreviousRuntimeSettings`.

- [ ] **Step 1: Write a failing await-order test**

```kotlin
@Test(timeout = 2_000)
fun setRuntimeSettingsDoesNotReturnBeforeConfirmedApplicationCompletes() = runBlocking {
    val applied = CompletableDeferred<GenerationSettings>()
    val tools = newHarness(onUpdateSettings = { applied.complete(it) })
    val before = GenerationSettings()
    val requested = before.copy(contextLength = before.contextLength + 512)

    val result = tools.setRuntimeSettings(
        AgentToolCall("set_runtime_settings"),
        confirmed = true,
    )

    assertTrue(result.success)
    assertEquals(requested.contextLength, applied.await().contextLength)
}
```

Provide a minimal `newHarness` in the test using `ServiceUiState`, `DeviceProfiler`, no-op callbacks, and safe default settings.

- [ ] **Step 2: Run the test and verify RED**

Expected: compilation failure because the callback and method are not suspending.

- [ ] **Step 3: Make only settings application suspending**

Change the constructor callback to:

```kotlin
private val onUpdateSettings: suspend (GenerationSettings) -> Unit,
```

Change `setRuntimeSettings` and `restorePreviousRuntimeSettings` to `suspend fun`. Leave unrelated runtime operations synchronous.

- [ ] **Step 4: Run RuntimeTools tests**

Run `RuntimeToolsTest`. Expected: PASS.

- [ ] **Step 5: Commit the tools change**

```bash
git add app/src/main/java/com/prismai/llmhost/agent/tools/RuntimeTools.kt \
  app/src/test/java/com/prismai/llmhost/agent/tools/RuntimeToolsTest.kt
git commit -m "fix(agent): await confirmed settings application"
```

---

### Task 6: Integrate lifecycle coordination and chain-scoped state into the service

**Files:**
- Modify: `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt:134-170,661-1088,1274-1352,1561-1575,1693-1722`
- Modify: `app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt:681-691`
- Modify/delete: `app/src/main/java/com/prismai/llmhost/agent/ChatTransitionGate.kt`
- Modify/delete: `app/src/test/java/com/prismai/llmhost/agent/ChatTransitionGateTest.kt`

**Interfaces:**
- Consumes: all coordinator, tracker, confirmation, and trace interfaces from Tasks 1-5.
- Produces: coordinator-backed public chat APIs and owner-safe `generationIdleInputs`.

- [ ] **Step 1: Add compile-time API usage tests through existing callers**

Update `ChatIdentityOperations` callback expectations only if needed for explicit outcomes. Add a small test in `ChatIdentityOperationsTest` proving a callback returning `Applied` maps to tool success and `Failed`/`Rejected` maps to a failed tool result.

Before implementation, run:

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon :app:testDevDebugUnitTest --tests 'com.prismai.llmhost.agent.tools.ChatIdentityOperationsTest'
```

Expected: existing tests pass and establish the baseline.

- [ ] **Step 2: Replace service gate fields and construction**

Create `AgentActivityTracker` and `InferenceLifecycleCoordinator` using `teardownScope` as their command scope. Remove `confirmedChatTransitionChain` and `confirmedChatTransitionThread`. Remove the direct `chatTransitionGate` field after all transitions use the coordinator.

- [ ] **Step 3: Make public chat methods return explicit request results**

Implement:

```kotlin
fun createChat(): ChatTransitionRequestResult
fun switchChat(chatId: String): ChatTransitionRequestResult
fun deleteChat(chatId: String): ChatTransitionRequestResult
fun clearTranscript(): ChatTransitionRequestResult
```

Each method performs basic UI preconditions, claims/invalidates agent ownership synchronously, and submits cleanup plus mutation through `requestChatTransition`. The mutation returns `Rejected` when `_isGenerating` or chat existence checks fail, otherwise performs the synchronous chat-manager mutation and returns `Applied`.

Do not return a new chat ID from `createChat`.

- [ ] **Step 4: Route generation admission through the coordinator**

Replace the early confirmed-transition check and direct `operationMutex.withLock` in `generateSafelyAndAwait` with:

```kotlin
var activeJob: Job? = null
coordinator.runGenerationAdmission {
    operationMutex.withLock {
        // existing background-busy check
        // existing activeGenerationSource assignment
        // existing cancelAndJoinGenerationLocked call
        if (benchmarkPreset == null && _currentChatId.value == null) {
            ensureChatForGenerationBlocking()
        }
        generationOrchestrator.generate(prompt, benchmarkPreset)
        activeJob = generationJob
    }
}
```

Do not call `prepareAgentChatTransition` from inside a coordinator command; that would self-await the actor queue.

- [ ] **Step 5: Make confirmed chat tools await concrete outcomes**

`executeConfirmedChatIdentityTool` calls `requestAndAwaitChatTransition` with thread-local-equivalent explicit mutation callbacks. Remove the thread-local flag. Map `Applied` to the existing successful `AgentToolResult`; map `Rejected`/`Failed` to `BUSY`/`FAILED`.

Because no thread-local bypass is needed inside the coordinator mutation, add private direct mutation methods (`performSwitchChat`, `performClearTranscript`, `performDeleteChat`) that the confirmed command calls directly.

- [ ] **Step 6: Make chain supersession discard pending authorization**

In `cancelAndJoinGenerationLocked`, before finalizing `traceOwner`, read the pending authorization. If it matches `traceOwner` and the current source chat, call `discardAuthorizationForChain`. Perform this only when the chain is being finalized/superseded, not when `preserveActiveTrace` keeps the chain alive.

- [ ] **Step 7: Make idle state owner-aware**

Add `private val agentActivityTracker = AgentActivityTracker()`. In `startAgentFollowUpGeneration`, resolve `chainId ?: agentTrace.activeChainId`; return without scheduling if null. Mark before launch and clear in `finally`.

In `generationIdleInputs`:

```kotlin
val pendingAuthorization = agentToolConfirmation.pendingAuthorization
val pendingConfirmation = pendingAuthorization?.let {
    agentToolConfirmation.isAuthorizationCurrent(it, chainId, _currentChatId.value)
} == true
val followUpPending = chainId != null && agentActivityTracker.hasFollowUp(chainId)
```

Use `pendingConfirmation || followUpPending` for `confirmationPending`.

- [ ] **Step 8: Preserve confirmed context for settings reload**

Split settings application:

```kotlin
private data class AppliedGenerationSettings(
    val previous: GenerationSettings,
    val next: GenerationSettings,
    val reloadModelId: String?,
)

private fun applyGenerationSettings(settings: GenerationSettings): AppliedGenerationSettings
fun updateGenerationSettings(settings: GenerationSettings)
private suspend fun updateGenerationSettingsForConfirmedAgent(settings: GenerationSettings)
```

The public method applies state and launches reload. The confirmed method applies state and calls `switchModel(modelId)` directly in the inherited coroutine context when not generating. If generating, retain existing `reloadPending` behavior.

Wire `RuntimeTools.onUpdateSettings` to `updateGenerationSettingsForConfirmedAgent`.

- [ ] **Step 9: Update UI result handling**

In `ChatScreen`, treat `ChatTransitionRequestResult.Queued` as accepted navigation intent. Do not display completion before the state flow updates. Keep the existing “cancel generation before switching” events produced by service-side request rejection.

- [ ] **Step 10: Remove the obsolete `ChatTransitionGate` and its test**

After `rg` confirms no references, delete `ChatTransitionGate.kt` and `ChatTransitionGateTest.kt`. Do not leave a duplicate serialization mechanism.

- [ ] **Step 11: Run focused tests**

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon \
  :app:testDevDebugUnitTest \
  --tests 'com.prismai.llmhost.lifecycle.*' \
  --tests 'com.prismai.llmhost.agent.*' \
  --tests 'com.prismai.llmhost.generation.*'
```

Expected: PASS.

- [ ] **Step 12: Commit service integration**

```bash
git add app/src/main/java/com/prismai/llmhost/service/InferenceService.kt \
  app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt \
  app/src/main/java/com/prismai/llmhost/agent/ChatTransitionGate.kt \
  app/src/test/java/com/prismai/llmhost/agent/ChatTransitionGateTest.kt
git commit -m "fix(service): unify generation and chat lifecycle admission"
```

---

### Task 7: Full verification, review, and PR update

**Files:**
- Review all changed files from Tasks 1-6.
- Update: `docs/superpowers/plans/2026-09-24-inference-lifecycle-race-fixes.md` checkboxes if task tracking is kept in the file.

**Interfaces:**
- Consumes: complete implementation.
- Produces: verified commits and updated PR #13.

- [x] **Step 1: Run formatting/whitespace checks**

```bash
git diff --check
rg -n 'ChatTransitionGate|confirmedChatTransitionChain|confirmedChatTransitionThread|agentFollowUpScheduled' app/src
```

Expected: no whitespace errors and no obsolete lifecycle symbols.

- [x] **Step 2: Run forced full unit matrix**

```bash
ANDROID_HOME=/home/linuxuser/android-sdk ./gradlew --no-daemon --rerun-tasks \
  :app:testDevDebugUnitTest \
  :app:testPlayDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with both test tasks executed.

- [x] **Step 3: Run native host tests**

Use the repository's current host-test configuration:

```bash
cmake -S app/src/test/cpp -B /tmp/opencode/prismlocal-native-tests \
  -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/opencode/prismlocal-native-tests
ctest --test-dir /tmp/opencode/prismlocal-native-tests --output-on-failure
```

Expected: native tests pass. If local vendored dependencies prevent configuration, report the exact failure and rely only on a fresh successful GitHub native-host check without claiming local execution.

- [ ] **Step 4: Request code review**

Use the requesting-code-review skill. The review must explicitly re-check:

- Transition-before-generation and generation-before-transition ordering.
- Chain-only confirmation removal.
- Per-chain follow-up cleanup.
- Atomic trace append versus `beginChain`.
- Confirmed coroutine context through reload.
- Public API result semantics.

- [ ] **Step 5: Apply only verified review fixes**

For every review finding, use receiving-code-review, reproduce or prove it, add a regression test, and make the smallest fix. Re-run focused tests after each change.

- [ ] **Step 6: Push PR branch**

```bash
git push origin fix/pr10-regression-hardening
```

Expected: PR #13 updates to the verified commits.

- [ ] **Step 7: Verify remote checks and report limitations**

```bash
gh pr checks 13 --repo gthgomez/PrismLocal
gh pr view 13 --repo gthgomez/PrismLocal --json headRefOid,mergeable,mergeStateStatus,url
```

Report exact check results. State explicitly that connected-device tests were not run unless they actually were.
