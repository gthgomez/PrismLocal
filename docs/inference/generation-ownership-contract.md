# Generation and asynchronous ownership contract

## Identities

- **Chat identity** is the stable chat ID. Deleting a chat invalidates work that
  would persist into that chat. Switching chats changes the UI's selected chat;
  it does not retarget work already owned by another chat.
- **Generation identity** is the monotonically allocated generation ID carried
  through Kotlin, JNI, and native code. A generation owns its token stream,
  terminal result, cancellation, acknowledgement, and cleanup.
- **Native/model lifetime** is the loaded model runtime shared by generations
  serialized by the service. A generation keeps the runtime alive until its
  worker has stopped; unload/reset cannot replace a generation's stream state.
- **Background task identity** is the task ID plus its source chat ID and
  immutable prompt. Queue promotion preserves this owner. Per the product
  decision on 2026-09-26, switching chats or backgrounding the app does not
  cancel or retarget it; explicit cancellation or source-chat deletion does.
- **Authorization revision** is a capability-policy revision captured with a
  confirmation. Revocation advances the revision. A confirmation must match the
  current revision and capabilities when consumed and when admitted for
  dispatch. Work already admitted may finish; completed side effects are not
  claimed to be reversible.

These identities are related by explicit ownership fields and lifecycle rules;
they are not aliases for one global epoch.

## Required invariants

1. Native drain/decode/ack and terminal cleanup operate only on the requested
   generation's stream. A lifecycle transition cannot occur between ownership
   validation and its protected operation.
2. Output removal is transactional: ring positions advance only after the
   consumer has accepted the corresponding payload. Decode/delivery failure
   retains the output for retry or terminalizes that same generation with an
   explicit error; it is never silently attributed to another generation.
3. A waiter observes only its captured generation/task result and uses a
   bounded deadline that includes joins.
4. Queued work keeps its source chat and never resolves transcript or result
   destinations from the currently selected chat.
5. Capability validation and dispatch admission are serialized with revocation.
   A revocation that wins the ordering rejects the operation; an operation
   admitted first is considered in flight and may complete without implying
   that its effects were undone.
6. Clear/delete advances the affected chat's persistence revision or tombstone
   before removing files. Older asynchronous writes cannot recreate the chat's
   content or artifacts.
7. Model import/download promotion and deletion share a model-storage
   lifecycle boundary and revalidate model identity inside it.

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
