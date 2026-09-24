# Inference Lifecycle Race Fixes Design

**Date:** 2026-09-24  
**Scope:** PR #13 follow-up  
**Status:** Approved for implementation planning

## Objective

Fix the lifecycle, confirmation, follow-up, transcript ownership, and confirmed-settings races identified during review of PR #13 without changing unrelated inference, model-management, or tool behavior.

## Confirmed Root Causes

1. Public chat mutations are queued on `ChatTransitionGate`, but generation admission does not participate in that ordering. A send can therefore start between queueing and applying a chat mutation.
2. Confirmed chat transitions use a separate atomic flag checked outside `operationMutex`, creating a second admission protocol with a TOCTOU gap.
3. Superseding an agent chain finalizes the trace but does not discard its pending confirmation authorization. Idle detection treats that stale card as active work and can wait for 30 minutes.
4. A confirmed runtime-settings operation starts its model reload in a fresh service coroutine, losing `ConfirmedAgentOperationContext`; the unconfirmed reload finalizes the active chain.
5. `AgentToolRouter` validates trace ownership and appends under `toolJobsLock`, while `AgentTrace.beginChain` mutates ownership under a different monitor. A stale callback can pass validation and append after chain replacement.
6. `agentFollowUpScheduled` is a process-wide Boolean. A stale chain's completion can clear the flag belonging to a newer chain and cause idle policy to abort valid work.

## Architecture

### `InferenceLifecycleCoordinator`

Add a small Android-independent lifecycle coordinator under the service/lifecycle package. It owns a single actor queue for chat identity transitions and generation admission.

The coordinator accepts two command classes:

- Chat transition request, containing cleanup and mutation lambdas plus a completion deferred.
- Generation admission request, containing the existing serialized generation-start lambda and a result deferred.

Commands execute in enqueue order. The coordinator does not execute an entire native generation; it performs only the admission/start section. Once native generation starts, existing generation state and `operationMutex` continue to own cancellation and execution.

This ordering provides these guarantees:

- A transition queued before generation completes or rejects before generation admission runs.
- A generation queued after a transition waits behind that transition.
- A transition queued after generation admission observes the generation state through the existing `isGenerating` guard and rejects if native generation is active.
- A non-cooperative transition cleanup cannot be bypassed by a later generation.
- Multiple chat transitions remain serialized.

The coordinator does not absorb model import, model deletion, benchmark queue operations, or native generation execution. Those remain under their current owners.

### Integration with `InferenceService`

`InferenceService` remains the composition root. It creates the coordinator with a lifecycle scope and supplies command lambdas.

`generateSafelyAndAwait` submits a generation-admission command and waits for admission. The admitted block retains `operationMutex`, cancels the previous generation, prepares a missing chat synchronously when necessary, and starts `GenerationOrchestrator`.

Public chat methods synchronously validate basic preconditions, submit a transition command, and return whether it was queued. The actual mutation and cleanup run in the coordinator.

Confirmed chat identity tools submit the same transition command and await the concrete outcome before returning an `AgentToolResult`.

### Public Result Semantics

Introduce non-Android result types:

- `ChatTransitionRequestResult.Queued`
- `ChatTransitionRequestResult.Rejected(reason)`
- `ChatTransitionOutcome.Applied`
- `ChatTransitionOutcome.Rejected(reason)`
- `ChatTransitionOutcome.Failed(reason)`

Public `createChat`, `switchChat`, `deleteChat`, and `clearTranscript` methods return `ChatTransitionRequestResult`; the value means only whether the request was accepted by the coordinator. They do not claim that mutation completed.

`createChat` stops returning a chat ID because an asynchronous transition cannot provide the new ID at request time. The existing UI caller does not use that value.

Confirmed agent tools receive `ChatTransitionOutcome` and report success only for `Applied`.

## Chain-Scoped State

### Confirmation Authorization

Add an atomic `AgentToolConfirmation.discardAuthorizationForChain(chainId, sourceChatId)` operation. It removes pending state only when both owner fields match, clears the in-memory UI card, and removes the source-chat persisted entry.

When `cancelAndJoinGenerationLocked` finalizes or supersedes a trace, discard only the authorization owned by that trace. A stale authorization must not clear a newer authorization.

`generationIdleInputs` evaluates a pending confirmation only when its chain and source chat still match the active trace and current chat. A stale pending object does not keep a generation session active.

### Follow-Up Scheduling

Replace the global `agentFollowUpScheduled` Boolean with a concurrent set of chain IDs. A follow-up is added before launching and removed only by its owning coroutine.

Idle detection considers follow-up work pending only when the set contains the current active chain ID. A stale chain's completion cannot clear a newer chain's marker.

If no active chain exists, no follow-up is launched; callers must resolve an owner before scheduling.

### Transcript Ownership

Add an `AgentTrace.withCurrentChain` critical section. It validates the supplied chain under the trace monitor and invokes the append callback before releasing that same monitor.

Both ordinary owned tool results and authorized transition results use this method. Because `beginChain` requires the same monitor, chain replacement and owned append are linearized: either the old append completes before replacement, or the stale append is rejected.

## Confirmed Runtime Settings

Split runtime-settings work into:

- A synchronous state application function that validates, publishes, persists, and determines whether reload is required.
- A public non-suspending UI wrapper that launches reload asynchronously.
- A suspending confirmed-agent path that applies settings and awaits model reload in the current coroutine context.

`RuntimeTools.setRuntimeSettings` and `restorePreviousRuntimeSettings` become suspending only as needed to await the confirmed path. Other runtime tool methods remain synchronous.

Because reload occurs under `ConfirmedAgentOperationContext`, `switchModel` sees and preserves the owning chain. A reload failure returns a failed tool result and does not report a successful settings operation.

## Error Handling

- Coordinator shutdown rejects new commands with a stable reason.
- Invalid chat IDs and active-generation conflicts are request rejections.
- Exceptions or `false` mutation results after admission are concrete failed outcomes.
- Asynchronous UI transition failures publish a service event and log context.
- A stale confirmation token never clears a newer pending token.
- A stale tool result never appends after chain replacement.
- Cancellation of a confirmed transition produces a failed tool result unless already completed, while preserving normal coroutine cancellation.

## Test Strategy

Implementation follows red-green-refactor for each concern.

1. **Coordinator tests**
   - Transition queued before generation executes first.
   - Generation queued after transition waits for cleanup and mutation.
   - Generation admission before transition causes the transition's active-generation guard to reject.
   - Non-cooperative cleanup blocks later generation admission.
   - Shutdown rejects new work and completes or cancels outstanding requests according to coroutine cancellation.

2. **Confirmation tests**
   - Superseding chain A discards only chain A authorization.
   - A newer chain/token remains actionable.
   - Stale authorization does not keep idle state active.
   - Source-chat mismatch remains invalid.

3. **Follow-up tracking tests**
   - Concurrent chains retain independent scheduled markers.
   - Stale completion removes only its own marker.
   - Idle state considers only the active chain's marker.

4. **Trace ownership tests**
   - Owned append succeeds for the current chain/chat.
   - Begin-chain replacement makes the old append fail.
   - Authorized transition append remains chain-scoped.

5. **Confirmed settings tests**
   - Reload-required settings preserve `ConfirmedAgentOperationContext`.
   - The chain remains current through reload and follow-up scheduling.
   - Reload failure returns a failed tool result.

6. **Regression verification**
   - `./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest`
   - Native host CTest through the repository's documented test path.
   - GitHub checks for the pushed PR branch.
   - Connected-device tests remain unclaimed unless explicitly run.

## Compatibility

- Android min/target SDK, flavors, JNI symbols, native error mappings, and public StateFlow properties remain unchanged.
- UI remains nonblocking when requesting chat transitions.
- Agent confirmation and tool protocol formats remain unchanged.
- Existing model/chat persistence formats remain unchanged.
- Existing model and native generation cancellation behavior remains unchanged.

## Non-Goals

- Refactoring the overall `InferenceService` composition.
- Changing native ring-buffer or llama.cpp session isolation.
- Changing persistence schemas.
- Adding broad instrumentation or telemetry beyond transition errors needed for correct operation.
- Running destructive or device-only validation without an attached test device.
