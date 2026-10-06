# Issue reconciliation — agent trace defaults and capability epochs (PL01)

- Audit task: PL01 "Reconcile open issues against current invariant tests"
- Base revision: `f78a60a2b1d233efbf48bdba42cf8f03ab301314` (`main`)
- Reconciliation date: 2026-09-29
- Scope: re-read the open native/session/privacy issues against `main` and record
  fixed / partially fixed / remaining status with source and test links. Issue
  closure on GitHub is a separate authorized action and is **not** assumed here;
  all issues listed below were still OPEN at reconciliation time.
- Status dimensions used: **source** (implementation exists at the base SHA),
  **test** (deterministic JVM unit coverage), **device** (physical/instrumented
  qualification). An issue is only "fixed" when source and test evidence agree;
  device-level gaps are always recorded explicitly.

## Issue-by-issue status

### #19 privacy: add retention and redaction for agent trace artifacts — mostly fixed (source + unit); device qualification outstanding

| Claim | Evidence |
| --- | --- |
| Metadata-only trace is the default (`rawContentOptIn = false`); raw prompt/arguments/result text are omitted unless explicitly enabled | `app/src/main/java/com/prismai/llmhost/agent/AgentTrace.kt` (constructor default, `content_mode` field written as `metadata_only` / `raw_opt_in`) |
| Artifacts carry `schema_version`, `owner_chat_id`, token counts and abort-reason presence instead of free content | `AgentTrace.kt` `finalizeTrace()` JSON payload |
| Queued writes are owner-bound: a chat deleted before or after finalization cannot have its trace (re)published | `AgentTrace.kt` `deletedChatIds` gate inside the persist block and `deleteForChat()` |
| Atomic publish (temp + rename) with UUID filenames (fixes the millisecond filename-collision note) | `AgentTrace.kt` `finalizeTrace()` file naming and `temp.renameTo(file)` |
| Bounded retention: at most 20 JSON artifacts, 30-day expiry, stale temp cleanup | `AgentTrace.kt` `applyRetentionLocked()` |
| Tests: metadata-only default, delete-after-publish removal, retention pruning, cancelled-scope flush | `app/src/test/java/com/prismai/llmhost/agent/AgentTraceContinuationTest.kt` (`defaultTracePersistsMetadataWithoutRawPromptArgumentsOrResult`, `deletingChatRemovesItsPublishedTraceAndBlocksQueuedPublication`, `traceRetentionRemovesExpiredArtifacts`, `canceledPersistenceScopeStillWritesArtifact`) |
| New negative control (this PR): source chat deleted **while the flush is still queued** — flush publishes nothing, leaves no temp file | `AgentTraceContinuationTest.kt` `deletingSourceChatBeforeQueuedTraceFlushPublishesNothing` |

Remaining: no encryption-at-rest of trace artifacts; metadata fields themselves
(tool names, latencies, token counts) are retained; no device/instrumented run
exercised `agent_traces/` on a physical filesystem. End-to-end device
qualification has not been performed.

### #16 agent: revoke pending confirmations when capabilities change — mostly fixed (source + unit); wiring verified by inspection, not by device run

| Claim | Evidence |
| --- | --- |
| Authorizations snapshot a capability/policy epoch (`CapabilityAuthorizationSnapshot.revision`) at staging time | `app/src/main/java/com/prismai/llmhost/CapabilityRegistry.kt` (`snapshot`, `isCurrent`, `tryBeginDispatch`), `app/src/main/java/com/prismai/llmhost/agent/AgentToolConfirmation.kt` (`stagePending` refuses non-granted snapshots) |
| Consumption revalidates chain, chat, target, fingerprint **and** the capability epoch | `AgentToolConfirmation.kt` `isAuthorizationCurrent()` / `consumePendingAndRevalidate()` |
| Dispatch admission is linearized against concurrent policy changes | `AgentToolConfirmation.kt` `tryBeginDispatch()`; consumed by `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt` (pre-dispatch gate at the confirmed-tool run path) |
| Restored/persisted cards are dead — no chain owner or token is persisted, so a restored card can never dispatch | `AgentToolConfirmation.kt` `persist()` / `restore()` |
| Tests: revocation and agent disablement between consumption and dispatch reject the operation; disablement invalidates pending confirmation before consumption | `app/src/test/java/com/prismai/llmhost/agent/AgentToolRouterTraceTest.kt` (`capabilityRevocationBetweenConsumptionAndDispatchRejectsTheOperation`, `agentDisablementBetweenConsumptionAndDispatchRejectsStillGrantedCapability`, `agentDisablementInvalidatesPendingConfirmationBeforeConsumption`); `app/src/test/java/com/prismai/llmhost/AgentToolsTest.kt` (registry-level revoke/tryBeginDispatch) |
| New negative controls (this PR): direct `revoke()` between confirmation and dispatch admits nothing and runs no tool (zero executor invocations, re-grant does not resurrect the stale epoch); a changed tool request replaces the staged authorization and the stale token cannot be consumed or dispatched | `AgentToolRouterTraceTest.kt` `capabilityRevocationBetweenConfirmationAndDispatchAdmitsNothingAndRunsNoTool`, `changedToolRequestAfterConfirmationCannotDispatchStaleToken` |

Remaining: the unit suite exercises the confirmation/registry admission contract
and the router seam, but the `InferenceService` dispatch wiring itself is not
unit-executable (Android service); it is source-inspected only. "The owner stays
valid for reporting work admitted before a later revocation"
(`isOperationOwnerCurrent`) is an intentional, documented semantic, not a
revocation gap. No device-level test of the confirmation card flow.

### #20 background: bind queued tasks to source chat and session — partially fixed

- Source: `app/src/main/java/com/prismai/llmhost/BackgroundAgentManager.kt`
  (`BackgroundTask.sourceChatId`, persisted and restored; per-chat unbind in the
  removal path), `app/src/main/java/com/prismai/llmhost/generation/ServiceGenerationOwnership.kt`
  and `app/src/main/java/com/prismai/llmhost/generation/BackgroundGenerationOwnership.kt`
  (owner-chat binding at admission/consumption).
- Tests: `app/src/test/java/com/prismai/llmhost/BackgroundAgentManagerTest.kt`,
  `BackgroundAgentPersistenceTest.kt`.
- Remaining: no dedicated queue/promotion/chat-switch race test proving a queued
  task cannot execute against the *new* chat's transcript/memory context; the
  full end-to-end ownership chain (enqueue → promote → generate) is not covered
  by one deterministic test. Issue stays open.

### #18 chat: make transcript persistence deletion-safe — partially fixed

- Source: `app/src/main/java/com/prismai/llmhost/chat/TranscriptWriteGate.kt`
  (revision snapshot / publish / invalidate-and-run lifecycle gating transcript
  writes against chat destruction).
- Tests: `app/src/test/java/com/prismai/llmhost/chat/TranscriptWriteGateTest.kt`.
- Remaining: whether every `ChatManager` delete/clear path funnels through the
  gate is not proven by a single storage-level regression with a controlled
  persistence barrier for all paths (last-chat deletion snapshot ordering in
  particular). Issue stays open.

### #21, #14, #15, #17 (native ABI/streams, native ring isolation, session coordination, model-storage coordination) — remaining

- These require native/JNI redesign and connected-device verification. Per
  issue #21/#14, local native assemble/device verification is unavailable in
  this environment (incomplete vendored `llama.cpp` submodule and no attached
  device). They remain open and visible; PL01 makes no change to them.

## Negative controls added by this PR

1. **Delete source chat before queued trace flush** (`AgentTraceContinuationTest.deletingSourceChatBeforeQueuedTraceFlushPublishesNothing`):
   the owning chat is deleted before its chain's trace flush runs; the flush
   must publish no JSON artifact, leave no `.tmp` residue and set no
   `lastAgentTracePath`. Deletion is ordered before finalization so the
   `deletedChatIds` gate check inside the persist block provably runs after
   deletion — this makes the control discriminating rather than racy (the flush
   itself is dispatched on `Dispatchers.IO`, so a "delete while queued" literal
   interleaving cannot be made deterministic at unit level; the delete-then-new-
   chain ordering in the pre-existing
   `deletingChatRemovesItsPublishedTraceAndBlocksQueuedPublication` covers the
   same gate deterministically).
   Sensitivity: with both `deletedChatIds` gates removed from `AgentTrace`, this
   test fails (artifact gets published) — observed during development.
2. **Revoke capability between confirmation and dispatch** (`AgentToolRouterTraceTest.capabilityRevocationBetweenConfirmationAndDispatchAdmitsNothingAndRunsNoTool`):
   after `consumePendingAndRevalidate` succeeds, `registry.revoke(MODEL_DOWNLOAD)`
   must make `tryBeginDispatch` fail, make `isCurrent` fail, produce zero tool
   executor invocations, and stay failed after the capability is re-granted
   (epoch moved). Sensitivity: removing the `snapshot.revision == policyRevision`
   check from `CapabilityRegistry.isCurrent` makes this test fail (the stale
   authorization is admitted after re-grant) — observed during development.
3. **Changed tool request** (`AgentToolRouterTraceTest.changedToolRequestAfterConfirmationCannotDispatchStaleToken`):
   staging a changed call replaces the authorization (different fingerprint);
   the stale token can neither be consumed nor dispatched and no tool side
   effects occur; the replacement is consumable only under its own token.

## Acceptance snapshot (PL01)

- Metadata-only default verified: unit-level, in-process (see #19 table). Device
  end-to-end: not run.
- Opt-in raw traces bounded and deletable: bounded retention and per-chat
  deletion verified at unit level (`applyRetentionLocked`, `deleteForChat`).
- Revoked/changed tool request cannot dispatch: verified at unit level for the
  confirmation/registry contract; service dispatch wiring source-inspected.
- Remaining JNI/device issues remain open and visible: yes (#14, #15, #17, #21
  unchanged and open at reconciliation time).

## Verification

- Command: `./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest`
  (results recorded in the PL01 dispatch report; not restated here to avoid
  drift between this document and the actual run receipts).
