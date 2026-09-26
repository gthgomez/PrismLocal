# Generation and asynchronous ownership contract

## Identities

- **Chat identity** is the stable chat ID. Deleting a chat invalidates work that
  would persist into that chat. Switching chats changes the UI's selected chat;
  it does not retarget work already owned by another chat.
- **Generation identity** is the monotonically allocated generation ID carried
  through Kotlin, JNI, and native code. A generation owns its token stream,
  terminal result, cancellation, acknowledgement, and cleanup. Native currently
  reuses one bounded ring; a lifecycle gate serializes generation publication,
  reset/unload/cancel, and the combined drain/decode/state snapshot around it.
- **Native/model lifetime** is the loaded model runtime shared by generations
  serialized by the service. A generation keeps the runtime alive until its
  worker has stopped; unload/reset cannot replace a generation's stream state.
- **Background task identity** is the task ID plus its persisted source chat ID
  and prompt. Queue promotion preserves that owner. Per the product decision on
  2026-09-26, switching chats or backgrounding the app does not cancel or
  retarget it; explicit cancellation or source-chat deletion does. The service
  holds an engine ownership lease for the task, binds generation to its source
  chat, defers UI chat switches until release, and prevents user generation
  from replacing its output. Pending user agent tools/follow-ups keep background
  queue admission closed; while a background task owns the engine, only its
  recorded agent chain can start a follow-up. If user work wins the scheduling
  race before the task acquires the engine, the manager requeues the task rather
  than recording a false completion. Deletion of an active source chat is
  rejected; queued tasks from a deleted chat are invalidated. Legacy queued
  records without an owner fail closed rather than adopting the selected chat.
  Service admission, source-chat restore, deferred chat switch, and follow-up
  ownership are covered by `ServiceGenerationOwnershipTest`. Connected
  instrumentation of `InferenceService` is still required before device
  qualification.
- **Authorization revision** is a capability-policy revision captured with a
  confirmation. Revocation advances the revision. A confirmation must match the
  current revision and capabilities when consumed and when admitted for
  dispatch. Work already admitted may finish; completed side effects are not
  claimed to be reversible.

These identities are related by explicit ownership fields and lifecycle rules;
they are not aliases for one global epoch.

## Implementation status

- Native generation drain ownership and acknowledgement changes are in PR #13;
  connected JNI/device execution is not yet verified.
- Capability revision checks and dispatch admission are in PR #13; exact latest
  candidate checks and fresh independent review remain outstanding.
- Transcript persistence uses per-chat revisions and a publication gate in the
  current candidate. Clear invalidates older snapshots; deletion also retires
  the chat ID. The current candidate CI is pending.
- Model storage uses a process-wide mutation gate; matching WorkManager downloads
  are cancelled on deletion and stale in-process download revisions cannot
  promote. `delete_model` confirmation records version, SHA-256, and path, and
  deletion under the storage gate refuses a different installed identity.
  Android instrumentation source covers that refusal. It has been compiled in
  CI and has not been executed on a device.
- Trace minimization, owner binding, deletion suppression, and bounded retention
  are in the candidate. Trace artifacts have schema version 1, and cleanup
  runs both at initialization and publication. Exact latest-candidate review
  and checks are pending.
- Waiters now receive session-specific output captured from their own stream
  callbacks, with latest-turn aggregation scoped to an agent-chain ID. The
  bounded result store. Source-owned background admission is exercised through
  the service ownership helper. Model deletion confirmation now snapshots
  version, SHA-256, and path and revalidates that identity before removal.

## Persisted content privacy policy

- Persisted traces contain ownership, tool names, success/terminal metadata,
  token count, and timings by default. Prompts, tool arguments, and result text
  are omitted. Artifacts carry `schema_version: 1`. Raw content is included
  only when a caller explicitly opts in;
  production construction currently uses the metadata-only default.
- Each trace records its source chat ID. Chat deletion removes that chat's
  published traces and tombstones it against already queued trace writes.
- Trace names include a random identifier and are published by same-directory
  atomic rename. Retention is capped at 20 artifacts and 30 days, enforced on
  publication and on `AgentTrace` initialization. Teardown persistence uses
  the service-owned teardown scope and follows the same publication and
  deletion checks.
- Trace files remain in app-private storage and are not encrypted by this
  change. The current threat model is protection against accidental exposure
  through diagnostics/exports; minimization, ownership, retention, and deletion
  are the controls. Device compromise or access by the app's own privileged
  process is not addressed by file encryption.
- Background-task persistence keeps prompts only while a task is queued or
  running, where they are needed for restart recovery. Completed records omit
  prompts and cap persisted result summaries at 120 characters. Debug logs no
  longer include prompt previews.

## Required invariants

1. Native drain/decode/ack and terminal cleanup operate only on the requested
   generation's stream. A lifecycle transition cannot occur between ownership
   validation and the protected combined operation. The native implementation
   uses a shared ring behind a recursive lifecycle gate rather than allocating
   a separate ring per generation; only one generation is active at a time.
2. Output removal is transactional: ring positions advance only after the
   Kotlin bounded stream has accepted the corresponding payload. A batch
   acknowledgement must match both its generation ID and observed ring tail.
   Decode failure cancels and terminalizes that generation with an explicit
   native error while preserving the batch until the error payload is accepted.
   If the downstream collector closes, native cancellation leaves the batch
   unacknowledged and discards it only after the cancelled producer joins under
   the lifecycle gate; it is never delivered as another generation's data.
3. A waiter observes only its captured generation/task result and uses a
   bounded deadline that includes joins. Result storage is keyed by generation
   or agent-chain identity and bounded to recent completed outputs; active
   waiters retain their owner result until release. Adversarial interleaving
   review remains pending.
4. Queued work keeps its source chat and never resolves transcript or result
   destinations from the currently selected chat. Queue persistence, source
   deletion invalidation, and the service helper that restores the source chat
   and applies a deferred switch only after release are covered by JVM tests.
   Connected service execution remains a device-qualification item.
5. Capability validation and dispatch admission are serialized with revocation.
   A revocation that wins the ordering rejects the operation; an operation
   admitted first is considered in flight and may complete without implying
   that its effects were undone.
6. Clear/delete advances the affected chat's persistence revision or tombstone
   before removing files. Older asynchronous writes cannot recreate the chat's
   content or artifacts.
7. Model import/download promotion and deletion share a model-storage
   lifecycle boundary. A confirmed delete revalidates the reviewed version,
   SHA-256, and path inside that boundary and does not remove a different
   identity.

## Acceptance criteria

- Deterministic barriers force old-generation drain versus reset/unload/new
  generation, slow-consumer overflow, decode/delivery/ack failure, and pending
  terminal races. Every result remains scoped to its owner.
- Wait tests prove an old waiter cannot return a new session's text and that
  all joins respect the configured deadline.
- Background tests queue from chat A, switch to B, and prove execution and
  persistence remain owned by A; explicit cancel and delete invalidate A's
  pending writes.
- Authorization tests revoke capabilities/agent mode between staging,
  consumption, and dispatch admission; stale confirmation reuse is rejected.
  A barrier test establishes the ordering between revocation and admission.
- Persistence tests hold writes across clear/delete and prove old transcript,
  import, download, and trace work cannot recreate deleted state.
- Host/JVM checks establish deterministic contract behavior only. Android
  instrumentation establishes the connected emulator/device contract. Physical
  device performance, thermal behavior, and endurance require separate measured
  qualification and are never inferred from host or emulator results.
